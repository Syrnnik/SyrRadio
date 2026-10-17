package com.syrnnik.syrradio.client;

import com.syrnnik.syrradio.SyrRadio;
import com.syrnnik.syrradio.config.ClientConfig;
import com.syrnnik.syrradio.net.ChunkPacket;
import com.syrnnik.syrradio.net.HelloPacket;
import com.syrnnik.syrradio.net.ListeningPacket;
import com.syrnnik.syrradio.net.Net;
import com.syrnnik.syrradio.net.NoticePacket;
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
    private TrackInfo current;
    /** Момент (по часам клиента), когда у текущего трека была позиция 0. */
    private long startLocal;
    private List<TrackInfo> queue = List.of();

    private final Map<Integer, Assembly> loading = new HashMap<>();
    private final Map<Integer, byte[]> ready = new HashMap<>();

    private RadioSound playing;
    private int playingId = -1;
    private long lastStartAttempt;
    private int announcedId = -1;

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
            Net.toServer(new ListeningPacket(isEnabled()));
        }
        refreshScreen();
    }

    void onState(StatePacket p) {
        int prevId = current == null ? -1 : current.id();
        current = p.current();
        queue = p.queue();
        if (current != null) {
            long expected = System.currentTimeMillis() - p.positionMs();
            if (current.id() != prevId) {
                startLocal = expected;
            } else if (Math.abs(expected - startLocal) > 1500) {
                // Набежал рассинхрон — перезапускаемся с правильной позиции.
                startLocal = expected;
                stopPlaying();
            }
        }
        // Держим в памяти только текущий и следующий трек.
        Set<Integer> keep = new HashSet<>();
        if (current != null) {
            keep.add(current.id());
        }
        if (!queue.isEmpty()) {
            keep.add(queue.get(0).id());
        }
        ready.keySet().retainAll(keep);
        loading.keySet().retainAll(keep);
        refreshScreen();
    }

    void onChunk(ChunkPacket p) {
        if (!isEnabled() || ready.containsKey(p.trackId())) {
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
        if (!isEnabled() || current == null || current.status() != TrackInfo.Status.READY) {
            stopPlaying();
            return;
        }
        long offset = System.currentTimeMillis() - startLocal;
        if (offset < 0 || offset >= current.durationMs()) {
            stopPlaying();
            return;
        }
        if (playing != null && playingId == current.id() && mc.getSoundManager().isActive(playing)) {
            return;
        }
        if (playing != null && playingId != current.id()) {
            stopPlaying();
        }
        byte[] data = ready.get(current.id());
        if (data == null) {
            return; // ещё качается
        }
        long now = System.currentTimeMillis();
        if (now - lastStartAttempt < 2000) {
            return; // не долбим движок, если звук почему-то сразу гаснет
        }
        // Под конец трека перезапуск не нужен: кусок в секунду не стоит щелчка.
        if (playing != null && current.durationMs() - offset < 1500) {
            return;
        }
        lastStartAttempt = now;
        SyrRadio.LOG.info("SyrRadio: играю «{}» с {} с ({} КБ)", current.displayName(), offset / 1000, data.length / 1024);
        playing = new RadioSound(data, offset);
        playingId = current.id();
        mc.getSoundManager().play(playing);
        if (announcedId != current.id()) {
            announcedId = current.id();
            mc.gui.setNowPlaying(Component.literal(current.displayName()));
        }
    }

    private void stopPlaying() {
        if (playing != null) {
            SyrRadio.LOG.info("SyrRadio: остановил трек {}", playingId);
            Minecraft.getInstance().getSoundManager().stop(playing);
            playing = null;
        }
        playingId = -1;
    }

    void reset() {
        stopPlaying();
        serverHasMod = false;
        serverProtocol = 0;
        current = null;
        queue = List.of();
        loading.clear();
        ready.clear();
        announcedId = -1;
        notice = null;
    }

    // ---------------------------------------------------------------- для экрана

    boolean isEnabled() {
        return ClientConfig.ENABLED.get();
    }

    void setEnabled(boolean enabled) {
        ClientConfig.ENABLED.set(enabled);
        ClientConfig.ENABLED.save();
        if (!enabled) {
            stopPlaying();
            loading.clear();
            ready.clear();
        }
        if (isCompatible()) {
            Net.toServer(new ListeningPacket(enabled));
        }
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

    TrackInfo current() {
        return current;
    }

    List<TrackInfo> queue() {
        return queue;
    }

    long positionMs() {
        return System.currentTimeMillis() - startLocal;
    }

    /** Сколько процентов текущего трека докачано, или -1, если уже весь. */
    int loadingPercent() {
        if (current == null || ready.containsKey(current.id())) {
            return -1;
        }
        Assembly a = loading.get(current.id());
        return a == null ? 0 : a.received * 100 / a.got.length;
    }

    boolean isPlaying() {
        return playing != null && Minecraft.getInstance().getSoundManager().isActive(playing);
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
