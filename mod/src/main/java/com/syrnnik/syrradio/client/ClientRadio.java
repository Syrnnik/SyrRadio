package com.syrnnik.syrradio.client;

import com.syrnnik.syrradio.SyrRadio;
import com.syrnnik.syrradio.config.ClientConfig;
import com.syrnnik.syrradio.net.ChunkPacket;
import com.syrnnik.syrradio.net.HelloPacket;
import com.syrnnik.syrradio.net.ListeningPacket;
import com.syrnnik.syrradio.net.Net;
import com.syrnnik.syrradio.net.NoticePacket;
import com.syrnnik.syrradio.net.RepeatMode;
import com.syrnnik.syrradio.net.StatePacket;
import com.syrnnik.syrradio.net.TrackInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Радио на клиенте: что прислал сервер, какие файлы докачаны и что играет.
 *
 * Проигрывание сверяется с сервером каждый тик: если звук должен идти, а не
 * идёт (сменили измерение, звуковой движок перезапустился, файл докачался
 * позже старта), он запускается заново с нужной секунды.
 */
public final class ClientRadio {
    private static final ClientRadio INSTANCE = new ClientRadio();

    public static ClientRadio get() {
        return INSTANCE;
    }

    /** Файл трека, который ещё качается. */
    private static final class Assembly {
        final byte[] data;
        final boolean[] got;
        int received;

        Assembly(int totalSize) {
            data = new byte[totalSize];
            got = new boolean[(totalSize + Net.CHUNK_SIZE - 1) / Net.CHUNK_SIZE];
        }
    }

    private boolean serverHasMod;
    private int serverProtocol;

    private List<TrackInfo> tracks = List.of();
    private int cursor;
    private boolean started;
    private boolean paused;
    private RepeatMode repeat = RepeatMode.OFF;
    private boolean shuffle;
    private int serial = -1;
    private int nextId = -1;
    /** Момент (по часам клиента), когда у текущего трека была позиция 0. */
    private long startLocal;
    private long pausedPos;

    private final Map<Integer, Assembly> loading = new HashMap<>();
    private final Map<Integer, byte[]> ready = new HashMap<>();

    private RadioSound playing;
    private int playingSerial = -1;
    private long lastStartAttempt;
    private int announcedId = -1;
    private boolean listeningSent = true;

    private Component notice;
    private boolean noticeError;
    private long noticeAt;

    private ClientRadio() {}

    // ---------------------------------------------------------------- пакеты

    void onHello(int protocol) {
        serverHasMod = true;
        serverProtocol = protocol;
        Net.toServer(new HelloPacket(Net.PROTOCOL));
        if (isCompatible()) {
            listeningSent = isListening();
            Net.toServer(new ListeningPacket(listeningSent));
        }
        refreshScreen();
    }

    void onState(StatePacket p) {
        int prevSerial = serial;
        tracks = p.tracks();
        cursor = p.cursor();
        started = p.started();
        paused = p.paused();
        repeat = p.repeat();
        shuffle = p.shuffle();
        serial = p.serial();
        nextId = p.nextId();

        long now = System.currentTimeMillis();
        if (paused) {
            pausedPos = p.positionMs();
            stopPlaying();
        } else if (started) {
            long expected = now - p.positionMs();
            if (serial != prevSerial || playing == null) {
                startLocal = expected;
            } else if (Math.abs(expected - startLocal) > 3000) {
                // Набежал заметный рассинхрон — перезапускаемся с правильной позиции.
                // Порог не меньше пары секунд: сразу после входа, пока клиент
                // догружает мир, пакеты приходят с опозданием, и на меньшем пороге
                // трек дёргался бы на ровном месте.
                startLocal = expected;
                stopPlaying();
            }
        }
        if (serial != prevSerial) {
            stopPlaying();
            lastStartAttempt = 0;
        }

        // Держим в памяти только текущий и следующий трек.
        Set<Integer> keep = new HashSet<>();
        TrackInfo cur = current();
        if (cur != null) {
            keep.add(cur.id());
        }
        keep.add(nextId);
        ready.keySet().retainAll(keep);
        loading.keySet().retainAll(keep);
        refreshScreen();
    }

    void onChunk(ChunkPacket p) {
        if (!isListening() || ready.containsKey(p.trackId())) {
            return;
        }
        Assembly a = loading.computeIfAbsent(p.trackId(), id -> new Assembly(p.totalSize()));
        if (p.index() < 0 || p.index() >= a.got.length || a.got[p.index()]) {
            return;
        }
        System.arraycopy(p.data(), 0, a.data, p.index() * Net.CHUNK_SIZE, p.data().length);
        a.got[p.index()] = true;
        if (++a.received == a.got.length) {
            loading.remove(p.trackId());
            ready.put(p.trackId(), a.data);
        }
    }

    void onNotice(NoticePacket p) {
        notice = p.text();
        noticeError = p.error();
        noticeAt = System.currentTimeMillis();
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof RadioScreen)) {
            Component c = p.error() ? p.text().copy().withStyle(ChatFormatting.RED) : p.text();
            mc.gui.setOverlayMessage(c, false);
        }
    }

    // ---------------------------------------------------------------- цикл

    void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        // Громкость 0 = радио у себя выключено: просим сервер не слать файлы.
        boolean listening = isListening();
        if (isCompatible() && listening != listeningSent) {
            listeningSent = listening;
            Net.toServer(new ListeningPacket(listening));
            if (!listening) {
                loading.clear();
                ready.clear();
            }
        }

        TrackInfo cur = current();
        if (!listening || cur == null || !started || paused) {
            stopPlaying();
            return;
        }
        long now = System.currentTimeMillis();
        long offset = now - startLocal;
        if (offset < 0 || offset >= cur.durationMs()) {
            stopPlaying();
            return;
        }
        if (playing != null && playingSerial == serial && mc.getSoundManager().isActive(playing)) {
            return;
        }
        byte[] data = ready.get(cur.id());
        if (data == null) {
            return; // ещё качается
        }
        if (now - lastStartAttempt < 2000) {
            return; // не долбим движок, если звук почему-то сразу гаснет
        }
        // Под конец трека перезапуск не нужен: кусок в секунду не стоит щелчка.
        if (playing != null && playingSerial == serial && cur.durationMs() - offset < 1500) {
            return;
        }
        stopPlaying();
        lastStartAttempt = now;
        SyrRadio.LOG.info("SyrRadio: играю «{}» с {} с ({} КБ)", cur.displayName(), offset / 1000, data.length / 1024);
        playing = new RadioSound(data, offset);
        playingSerial = serial;
        mc.getSoundManager().play(playing);
        if (announcedId != cur.id()) {
            announcedId = cur.id();
            mc.gui.setNowPlaying(Component.literal(cur.displayName()));
        }
    }

    private void stopPlaying() {
        if (playing != null) {
            SyrRadio.LOG.info("SyrRadio: остановил трек");
            Minecraft.getInstance().getSoundManager().stop(playing);
            playing = null;
        }
        playingSerial = -1;
    }

    void reset() {
        stopPlaying();
        serverHasMod = false;
        serverProtocol = 0;
        tracks = List.of();
        cursor = 0;
        started = false;
        paused = false;
        serial = -1;
        nextId = -1;
        loading.clear();
        ready.clear();
        announcedId = -1;
        notice = null;
        listeningSent = true;
    }

    // ---------------------------------------------------------------- для экрана

    boolean isListening() {
        return volume() > 0.001;
    }

    void setVolume(double v) {
        ClientConfig.VOLUME.set(v);
        ClientConfig.VOLUME.save();
    }

    double volume() {
        return ClientConfig.VOLUME.get();
    }

    boolean serverHasMod() {
        return serverHasMod;
    }

    boolean isCompatible() {
        return serverHasMod && serverProtocol == Net.PROTOCOL;
    }

    int serverProtocol() {
        return serverProtocol;
    }

    List<TrackInfo> tracks() {
        return tracks;
    }

    int cursor() {
        return cursor;
    }

    TrackInfo current() {
        return cursor >= 0 && cursor < tracks.size() ? tracks.get(cursor) : null;
    }

    boolean isStarted() {
        return started;
    }

    boolean isPaused() {
        return paused;
    }

    RepeatMode repeat() {
        return repeat;
    }

    boolean shuffle() {
        return shuffle;
    }

    long positionMs() {
        if (!started) {
            return 0;
        }
        return paused ? pausedPos : System.currentTimeMillis() - startLocal;
    }

    /** Сколько процентов текущего трека докачано, или -1, если уже весь. */
    int loadingPercent() {
        TrackInfo cur = current();
        if (cur == null || ready.containsKey(cur.id())) {
            return -1;
        }
        Assembly a = loading.get(cur.id());
        return a == null ? 0 : a.received * 100 / a.got.length;
    }

    Component notice() {
        return notice != null && System.currentTimeMillis() - noticeAt < 8000 ? notice : null;
    }

    boolean noticeError() {
        return noticeError;
    }

    private void refreshScreen() {
        if (Minecraft.getInstance().screen instanceof RadioScreen s) {
            s.onRadioChanged();
        }
    }
}
