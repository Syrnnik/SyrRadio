package com.syrnnik.syrradio.server;

import com.syrnnik.syrradio.SyrRadio;
import com.syrnnik.syrradio.config.ServerConfig;
import com.syrnnik.syrradio.net.ChunkPacket;
import com.syrnnik.syrradio.net.HelloPacket;
import com.syrnnik.syrradio.net.Net;
import com.syrnnik.syrradio.net.NoticePacket;
import com.syrnnik.syrradio.net.StatePacket;
import com.syrnnik.syrradio.net.TrackInfo;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Радио на сервере: очередь, текущий трек и раздача файлов игрокам.
 *
 * Всё состояние меняется только в серверном потоке. Ответы bridge приходят из
 * чужих потоков и возвращаются сюда через server.execute().
 */
public final class RadioServer {
    private static final RadioServer INSTANCE = new RadioServer();
    /** Запас после конца трека: клиенты успевают доиграть, а пауза между треками почти не слышна. */
    private static final long END_SLACK_MS = 200;

    public static RadioServer get() {
        return INSTANCE;
    }

    /** Состояние одного игрока с модом. */
    private static final class Listener {
        final ServerPlayer player;
        boolean compatible;
        boolean listening = true;
        /** trackId → сколько кусков уже отправлено. */
        final Map<Integer, Integer> sent = new HashMap<>();
        final Set<Integer> complete = new HashSet<>();

        Listener(ServerPlayer player) {
            this.player = player;
        }

        boolean wantsAudio() {
            return compatible && listening;
        }

        void forget(int trackId) {
            sent.remove(trackId);
            complete.remove(trackId);
        }
    }

    private final BridgeClient bridge = new BridgeClient();
    private final Map<UUID, Listener> listeners = new HashMap<>();
    private final Deque<Entry> queue = new ArrayDeque<>();
    private MinecraftServer server;
    private int nextId = 1;
    private Entry current;
    private boolean started;
    /** Когда начал (или начнёт) играть текущий трек, по часам сервера. */
    private long startAt;
    private int ticks;

    private RadioServer() {}

    // ---------------------------------------------------------------- игроки

    public void onLogin(ServerPlayer player) {
        listeners.remove(player.getUUID());
        if (Net.hasMod(player)) {
            listeners.put(player.getUUID(), new Listener(player));
            Net.send(player, new HelloPacket(Net.PROTOCOL));
        }
    }

    public void onLogout(ServerPlayer player) {
        listeners.remove(player.getUUID());
    }

    public void onHello(ServerPlayer player, int protocol) {
        Listener l = listeners.get(player.getUUID());
        if (l == null) {
            return;
        }
        l.compatible = protocol == Net.PROTOCOL;
        if (l.compatible) {
            Net.send(player, buildState());
        } else {
            SyrRadio.LOG.info("SyrRadio: у {} другая версия протокола ({} вместо {}), радио ему не шлём",
                player.getGameProfile().getName(), protocol, Net.PROTOCOL);
        }
    }

    public boolean isCompatible(ServerPlayer player) {
        Listener l = listeners.get(player.getUUID());
        return l != null && l.compatible;
    }

    public void setListening(ServerPlayer player, boolean listening) {
        Listener l = listeners.get(player.getUUID());
        if (l == null) {
            return;
        }
        l.listening = listening;
        if (!listening) {
            // Клиент выбрасывает файлы, когда радио выключают: при включении шлём заново.
            l.sent.clear();
            l.complete.clear();
        }
    }

    // ---------------------------------------------------------------- очередь

    public void add(String url, CommandSourceStack src) {
        server = src.getServer();
        String clean = url.trim();
        String who = src.getTextName();
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            notify(src, Component.translatableWithFallback("syrradio.notice.not_url", "Paste a track link (https://...)"), true);
            return;
        }
        notify(src, Component.translatableWithFallback("syrradio.notice.resolving", "Looking up the track…"), false);
        // Номер берём сразу: по нему трек встанет в очередь в порядке добавления,
        // даже если площадка ответила позже, чем на следующий трек.
        int id = nextId++;
        bridge.resolve(clean).whenComplete((meta, err) -> server.execute(() -> {
            if (err != null) {
                notify(src, Component.translatableWithFallback("syrradio.notice.failed", "Couldn't add: %s",
                    BridgeClient.message(err)), true);
                return;
            }
            Entry e = new Entry(id, clean, meta, who);
            enqueue(e);
            SyrRadio.LOG.info("SyrRadio: {} добавил {} ({})", who, e.displayName(), clean);
            notify(src, Component.translatableWithFallback("syrradio.notice.added", "Added: %s", e.displayName()), false);
            broadcastState();
        }));
    }

    private void enqueue(Entry e) {
        List<Entry> items = new ArrayList<>(queue);
        int at = items.size();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id > e.id) {
                at = i;
                break;
            }
        }
        items.add(at, e);
        queue.clear();
        queue.addAll(items);
    }

    /** Пропустить трек trackId, если он сейчас играет. */
    public boolean skip(int trackId, String who) {
        if (current == null || current.id != trackId) {
            return false;
        }
        SyrRadio.LOG.info("SyrRadio: {} пропустил {}", who, current.displayName());
        finishCurrent();
        broadcastState();
        return true;
    }

    public boolean skipCurrent(String who) {
        return current != null && skip(current.id, who);
    }

    public boolean remove(int trackId) {
        for (Entry e : queue) {
            if (e.id == trackId) {
                queue.remove(e);
                dropAudio(e);
                broadcastState();
                return true;
            }
        }
        return false;
    }

    /** Убрать трек по номеру в очереди, как его показывает /syrradio queue (с единицы). */
    public boolean removeAt(int position) {
        int i = 1;
        for (Entry e : queue) {
            if (i++ == position) {
                return remove(e.id);
            }
        }
        return false;
    }

    public void stopAll() {
        if (current != null) {
            finishCurrent();
        }
        queue.forEach(this::dropAudio);
        queue.clear();
        broadcastState();
    }

    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        if (current != null) {
            lines.add("▶ " + current.displayName() + " [" + current.requestedBy + "]");
        }
        int i = 1;
        for (Entry e : queue) {
            lines.add(i++ + ". " + e.displayName() + " [" + e.requestedBy + "]");
        }
        return lines;
    }

    // ---------------------------------------------------------------- цикл

    public void tick(MinecraftServer server) {
        this.server = server;
        long now = System.currentTimeMillis();

        if (current == null && !queue.isEmpty()) {
            current = queue.poll();
            started = false;
            broadcastState();
        }

        if (current != null) {
            prepare(current);
            if (current.state == Entry.State.READY && !started) {
                startAt = now + graceMs(current);
                started = true;
                SyrRadio.LOG.info("SyrRadio: играет {}", current.displayName());
                broadcastState();
            } else if (started && now - startAt >= current.durationMs + END_SLACK_MS) {
                finishCurrent();
                broadcastState();
            }
        }

        // Следующий трек готовим и рассылаем заранее, чтобы между треками не было паузы.
        Entry next = queue.peek();
        if (next != null) {
            prepare(next);
        }

        pumpChunks(next);

        // Раз в 10 секунд напоминаем всем позицию: это лечит рассинхрон, если он набежал.
        if (++ticks % 200 == 0 && current != null && started) {
            broadcastState();
        }
    }

    public void shutdown() {
        current = null;
        started = false;
        queue.clear();
        listeners.clear();
        server = null;
    }

    private void prepare(Entry e) {
        if (e.state != Entry.State.QUEUED) {
            return;
        }
        e.state = Entry.State.PREPARING;
        bridge.audio(e.url).whenComplete((audio, err) -> server.execute(() -> {
            boolean alive = e == current || queue.contains(e);
            if (!alive) {
                return;
            }
            if (err != null) {
                e.state = Entry.State.FAILED;
                Component why = BridgeClient.message(err);
                SyrRadio.LOG.warn("SyrRadio: не скачался {}: {}", e.displayName(), why.getString());
                broadcastNotice(Component.translatableWithFallback("syrradio.notice.track_failed",
                    "Couldn't load «%s»: %s", e.displayName(), why), true);
                if (e == current) {
                    current = null;
                    started = false;
                } else {
                    queue.remove(e);
                }
                broadcastState();
                return;
            }
            e.data = audio.data();
            if (audio.durationMs() > 0) {
                e.durationMs = audio.durationMs();
            }
            e.state = Entry.State.READY;
            broadcastState();
        }));
    }

    /**
     * Сколько подождать со стартом, чтобы слушатели успели получить файл.
     * Если у всех он уже есть (его разослали заранее), почти не ждём.
     */
    private long graceMs(Entry e) {
        long missingBytes = 0;
        for (Listener l : listeners.values()) {
            if (l.wantsAudio() && !l.complete.contains(e.id)) {
                int sentChunks = l.sent.getOrDefault(e.id, 0);
                missingBytes = Math.max(missingBytes, e.data.length - (long) sentChunks * Net.CHUNK_SIZE);
            }
        }
        if (missingBytes <= 0) {
            return 150;
        }
        long bytesPerSecond = (long) ServerConfig.CHUNKS_PER_TICK.get() * Net.CHUNK_SIZE * 20;
        return Math.min(15_000, missingBytes * 1000 / bytesPerSecond + 1000);
    }

    private void finishCurrent() {
        Entry e = current;
        current = null;
        if (e == null) {
            return;
        }
        if (started) {
            double played = Math.max(0, Math.min(e.durationMs, System.currentTimeMillis() - startAt)) / 1000.0;
            if (played >= 1) {
                bridge.played(e.url, played);
            }
        }
        started = false;
        dropAudio(e);
    }

    private void dropAudio(Entry e) {
        e.data = null;
        for (Listener l : listeners.values()) {
            l.forget(e.id);
        }
    }

    private void pumpChunks(Entry next) {
        int budget = ServerConfig.CHUNKS_PER_TICK.get();
        for (Listener l : listeners.values()) {
            if (!l.wantsAudio()) {
                continue;
            }
            int left = budget;
            left = sendChunks(l, current, left);
            sendChunks(l, next, left);
        }
    }

    private int sendChunks(Listener l, Entry e, int left) {
        if (e == null || e.data == null || left <= 0 || l.complete.contains(e.id)) {
            return left;
        }
        int total = e.chunkCount();
        int idx = l.sent.getOrDefault(e.id, 0);
        while (left > 0 && idx < total) {
            int from = idx * Net.CHUNK_SIZE;
            int to = Math.min(e.data.length, from + Net.CHUNK_SIZE);
            Net.send(l.player, new ChunkPacket(e.id, idx, e.data.length, Arrays.copyOfRange(e.data, from, to)));
            idx++;
            left--;
        }
        l.sent.put(e.id, idx);
        if (idx >= total) {
            l.complete.add(e.id);
        }
        return left;
    }

    // ---------------------------------------------------------------- рассылка

    private StatePacket buildState() {
        TrackInfo cur = null;
        long pos = 0;
        if (current != null) {
            cur = current.info(started);
            pos = started ? System.currentTimeMillis() - startAt : 0;
        }
        List<TrackInfo> q = new ArrayList<>();
        for (Entry e : queue) {
            q.add(e.info(false));
        }
        return new StatePacket(cur, pos, q);
    }

    private void broadcastState() {
        StatePacket state = buildState();
        for (Listener l : listeners.values()) {
            if (l.compatible) {
                Net.send(l.player, state);
            }
        }
    }

    private void broadcastNotice(Component text, boolean error) {
        for (Listener l : listeners.values()) {
            if (l.compatible) {
                Net.send(l.player, new NoticePacket(text, error));
            }
        }
    }

    /** Игроку с модом — подписью в игре, остальным (консоль, RCON) — обычным ответом команды. */
    private void notify(CommandSourceStack src, Component text, boolean error) {
        if (src.getEntity() instanceof ServerPlayer p && isCompatible(p)) {
            Net.send(p, new NoticePacket(text, error));
        } else if (error) {
            src.sendFailure(text);
        } else {
            src.sendSuccess(() -> text, false);
        }
    }
}
