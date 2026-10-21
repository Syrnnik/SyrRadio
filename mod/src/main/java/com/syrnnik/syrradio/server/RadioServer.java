package com.syrnnik.syrradio.server;

import com.syrnnik.syrradio.SyrRadio;
import com.syrnnik.syrradio.config.ServerConfig;
import com.syrnnik.syrradio.net.ChunkPacket;
import com.syrnnik.syrradio.net.ControlPacket;
import com.syrnnik.syrradio.net.HelloPacket;
import com.syrnnik.syrradio.net.Net;
import com.syrnnik.syrradio.net.NoticePacket;
import com.syrnnik.syrradio.net.RepeatMode;
import com.syrnnik.syrradio.net.StatePacket;
import com.syrnnik.syrradio.net.TrackInfo;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Радио на сервере: список треков с курсором «сейчас играет», режимы плеера
 * и раздача файлов игрокам.
 *
 * Треки до курсора уже играли, после — будут. Курсор, равный размеру списка,
 * значит «всё доиграло»: новый трек встанет на это место и сразу заиграет.
 *
 * Всё состояние меняется только в серверном потоке. Ответы bridge приходят из
 * чужих потоков и возвращаются сюда через server.execute().
 */
public final class RadioServer {
    private static final RadioServer INSTANCE = new RadioServer();
    /** Запас после конца трека: клиенты успевают доиграть, а пауза между треками почти не слышна. */
    private static final long END_SLACK_MS = 200;
    /** «Назад» позже этой секунды начинает трек заново, раньше — включает предыдущий. */
    private static final long PREV_RESTART_MS = 3000;
    /** Сколько треков хранить в списке: лишние сыгранные уходят с начала. */
    private static final int MAX_TRACKS = 200;

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
    private final List<Entry> list = new ArrayList<>();
    private MinecraftServer server;
    private RadioStore store;

    private int nextId = 1;
    private int cursor;
    private RepeatMode repeat = RepeatMode.OFF;
    private boolean shuffle;

    /** Текущий трек запущен (играет или стоит на паузе с позицией). */
    private boolean started;
    /** Когда у текущего трека была (или будет) позиция 0, по часам сервера. */
    private long startAt;
    private boolean paused;
    /** Пауза поставлена не человеком, а потому что слушать некому. */
    private boolean autoPaused;
    private long pausedPos;
    /** Растёт при каждом запуске трека — клиенты по нему перезапускают звук. */
    private int serial;

    private int ticks;
    private boolean dirty;

    /** Кому и когда (номер тика) написать, что мода нет или он старый. */
    private record JoinNotice(long dueTick, boolean outdated) {}

    private final Map<UUID, JoinNotice> joinNotices = new HashMap<>();
    /** Через сколько тиков после входа писать: при входе моды TFG сыплют в чат, сообщение там утонет. */
    private static final int JOIN_NOTICE_DELAY = 100;

    private RadioServer() {}

    // ---------------------------------------------------------------- жизненный цикл

    public void start(MinecraftServer server) {
        this.server = server;
        this.store = new RadioStore(server);
        RadioStore.Saved saved = store.load();
        list.clear();
        if (saved != null) {
            for (RadioStore.SavedTrack t : saved.tracks()) {
                list.add(new Entry(t.id(), t.url(), t.title(), t.artist(), t.source(), t.requestedBy(), t.durationMs()));
            }
            nextId = Math.max(saved.nextId(), list.stream().mapToInt(e -> e.id + 1).max().orElse(1));
            cursor = Math.max(0, Math.min(saved.cursor(), list.size()));
            repeat = saved.repeat() == null ? RepeatMode.OFF : saved.repeat();
            shuffle = saved.shuffle();
            SyrRadio.LOG.info("SyrRadio: восстановлено {} треков, текущий — {}", list.size(), cursor + 1);
        }
        started = false;
        paused = false;
        autoPaused = false;
    }

    public void shutdown() {
        save();
        list.clear();
        listeners.clear();
        cursor = 0;
        started = false;
        paused = false;
        server = null;
        store = null;
    }

    private void markDirty() {
        dirty = true;
    }

    private void save() {
        if (store == null) {
            return;
        }
        List<RadioStore.SavedTrack> tracks = new ArrayList<>();
        for (Entry e : list) {
            tracks.add(new RadioStore.SavedTrack(e.id, e.url, e.title, e.artist, e.source, e.requestedBy, e.durationMs));
        }
        store.save(new RadioStore.Saved(nextId, cursor, repeat, shuffle, tracks));
        dirty = false;
    }

    // ---------------------------------------------------------------- игроки

    public void onLogin(ServerPlayer player) {
        listeners.remove(player.getUUID());
        joinNotices.remove(player.getUUID());
        if (Net.hasMod(player)) {
            listeners.put(player.getUUID(), new Listener(player));
            Net.send(player, new HelloPacket(Net.PROTOCOL));
        } else {
            joinNotices.put(player.getUUID(), new JoinNotice(ticks + JOIN_NOTICE_DELAY, false));
        }
    }

    public void onLogout(ServerPlayer player) {
        listeners.remove(player.getUUID());
        joinNotices.remove(player.getUUID());
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
            joinNotices.put(player.getUUID(), new JoinNotice(ticks + JOIN_NOTICE_DELAY, true));
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
            // Клиент выбрасывает файлы, когда радио у себя глушит: при включении шлём заново.
            l.sent.clear();
            l.complete.clear();
        }
    }

    private boolean hasListeners() {
        for (Listener l : listeners.values()) {
            if (l.wantsAudio()) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- список

    private Entry current() {
        return cursor >= 0 && cursor < list.size() ? list.get(cursor) : null;
    }

    private int indexOf(int id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id == id) {
                return i;
            }
        }
        return -1;
    }

    /** Какой трек заиграет после текущего сам собой — его готовим и рассылаем заранее. */
    private Entry upcoming() {
        Entry cur = current();
        if (cur == null) {
            return null;
        }
        if (repeat == RepeatMode.ONE) {
            return cur;
        }
        if (cursor + 1 < list.size()) {
            return list.get(cursor + 1);
        }
        // На новом круге с перемешиванием порядок ещё не известен.
        return repeat == RepeatMode.ALL && !shuffle ? list.get(0) : null;
    }

    private long position() {
        if (!started) {
            return 0;
        }
        return paused ? pausedPos : System.currentTimeMillis() - startAt;
    }

    public void add(String url, CommandSourceStack src) {
        server = src.getServer();
        String clean = url.trim();
        String who = src.getTextName();
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            notify(src, Component.translatableWithFallback("syrradio.notice.not_url", "Paste a track link (https://...)"), true);
            return;
        }
        notify(src, Component.translatableWithFallback("syrradio.notice.resolving", "Looking up the track…"), false);
        // Номер берём сразу: по нему трек встанет в порядке добавления,
        // даже если площадка ответила позже, чем на следующий трек.
        int id = nextId++;
        bridge.resolve(clean).whenComplete((meta, err) -> server.execute(() -> {
            if (err != null) {
                notify(src, Component.translatableWithFallback("syrradio.notice.failed", "Couldn't add: %s",
                    BridgeClient.message(err)), true);
                return;
            }
            Entry e = new Entry(id, clean, meta, who);
            append(e);
            SyrRadio.LOG.info("SyrRadio: {} добавил {} ({})", who, e.displayName(), clean);
            notify(src, Component.translatableWithFallback("syrradio.notice.added", "Added: %s", e.displayName()), false);
            changed();
        }));
    }

    private void append(Entry e) {
        // В конец, но перед теми ещё не игравшими треками, что добавлены позже
        // (их ответ от площадки пришёл раньше).
        int at = list.size();
        while (at - 1 > cursor && list.get(at - 1).id > e.id) {
            at--;
        }
        list.add(at, e);
        // Список не бесконечный: лишнее срезаем со стороны сыгранного.
        while (list.size() > MAX_TRACKS && cursor > 0) {
            dropAudio(list.remove(0));
            cursor--;
        }
    }

    public void control(ControlPacket.Action action, int trackId, String who) {
        switch (action) {
            case PREV -> {
                Entry cur = current();
                if (cur == null || cur.id == trackId) {
                    prev(who);
                }
            }
            case NEXT -> {
                Entry cur = current();
                if (cur != null && cur.id == trackId) {
                    next(who);
                }
            }
            case PAUSE -> togglePause(who);
            case REPEAT -> setRepeat(repeat.next());
            case SHUFFLE -> setShuffle(!shuffle);
            case PLAY -> play(trackId, who);
            case CLEAR -> clear(who);
        }
    }

    public boolean next(String who) {
        if (current() == null) {
            return false;
        }
        SyrRadio.LOG.info("SyrRadio: {} → следующий", who);
        advance();
        changed();
        return true;
    }

    public boolean prev(String who) {
        if (list.isEmpty()) {
            return false;
        }
        SyrRadio.LOG.info("SyrRadio: {} → назад", who);
        Entry cur = current();
        if (cur != null && position() > PREV_RESTART_MS) {
            stopCurrent();
        } else {
            stopCurrent();
            if (cursor > 0) {
                cursor--;
            } else if (repeat == RepeatMode.ALL) {
                cursor = list.size() - 1;
            }
        }
        retainAudio();
        changed();
        return true;
    }

    public boolean togglePause(String who) {
        if (current() == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (paused) {
            if (started) {
                startAt = now - pausedPos + 150;
            }
            paused = false;
            autoPaused = false;
            SyrRadio.LOG.info("SyrRadio: {} снял с паузы", who);
        } else {
            pausedPos = started ? now - startAt : 0;
            paused = true;
            SyrRadio.LOG.info("SyrRadio: {} поставил на паузу", who);
        }
        changed();
        return true;
    }

    /** Перемотать текущий трек. Клиенты перезапустят звук с новой позиции по serial. */
    public boolean seek(int trackId, long positionMs, String who) {
        Entry cur = current();
        if (cur == null || cur.id != trackId || !started) {
            return false;
        }
        long pos = Math.max(0, Math.min(positionMs, Math.max(0, cur.durationMs - 500)));
        if (paused) {
            pausedPos = pos;
        } else {
            startAt = System.currentTimeMillis() - pos + 150;
        }
        serial++;
        SyrRadio.LOG.info("SyrRadio: {} перемотал на {} с", who, pos / 1000);
        changed();
        return true;
    }

    public void setRepeat(RepeatMode mode) {
        repeat = mode;
        retainAudio();
        changed();
    }

    public void setShuffle(boolean on) {
        shuffle = on;
        if (on && cursor + 1 < list.size()) {
            // Перемешиваем только то, что ещё не играло, — и так, чтобы было видно в списке.
            Collections.shuffle(list.subList(cursor + 1, list.size()));
        }
        retainAudio();
        changed();
    }

    /** Включить трек trackId прямо сейчас. */
    public boolean play(int trackId, String who) {
        int i = indexOf(trackId);
        if (i < 0) {
            return false;
        }
        stopCurrent();
        cursor = i;
        Entry e = list.get(i);
        if (e.state == Entry.State.FAILED) {
            e.state = Entry.State.QUEUED; // дадим ещё шанс
        }
        SyrRadio.LOG.info("SyrRadio: {} включил {}", who, e.displayName());
        retainAudio();
        changed();
        return true;
    }

    public boolean move(int trackId, int toIndex) {
        int from = indexOf(trackId);
        if (from < 0) {
            return false;
        }
        Entry cur = current();
        Entry e = list.remove(from);
        list.add(Math.max(0, Math.min(toIndex, list.size())), e);
        if (cur != null) {
            cursor = list.indexOf(cur); // текущий трек остаётся текущим, где бы он ни оказался
        }
        retainAudio();
        changed();
        return true;
    }

    public boolean remove(int trackId) {
        int i = indexOf(trackId);
        if (i < 0) {
            return false;
        }
        boolean wasCurrent = i == cursor;
        if (wasCurrent) {
            stopCurrent();
        }
        Entry e = list.remove(i);
        dropAudio(e);
        if (i < cursor) {
            cursor--;
        }
        // Убрали играющий последний трек при повторе очереди — круг начинается заново.
        if (wasCurrent && cursor >= list.size() && repeat == RepeatMode.ALL && !list.isEmpty()) {
            cursor = 0;
        }
        retainAudio();
        changed();
        return true;
    }

    public boolean playAt(int position, String who) {
        return position >= 1 && position <= list.size() && play(list.get(position - 1).id, who);
    }

    public boolean removeAt(int position) {
        return position >= 1 && position <= list.size() && remove(list.get(position - 1).id);
    }

    /** Очистить весь список, вместе с играющим треком: радио замолкает. */
    public void clear(String who) {
        stopCurrent();
        for (Entry e : list) {
            dropAudio(e);
        }
        list.clear();
        cursor = 0;
        SyrRadio.LOG.info("SyrRadio: {} очистил список", who);
        changed();
    }

    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Entry e = list.get(i);
            // ▶ — играет, ⏸ — пауза, … — выбран, но ждёт слушателей или ещё скачивается.
            String mark = i == cursor ? (paused ? "⏸ " : started ? "▶ " : "… ") : i < cursor ? "· " : "  ";
            lines.add(mark + (i + 1) + ". " + e.displayName() + " [" + e.requestedBy + "]");
        }
        lines.add("repeat=" + repeat.name().toLowerCase() + ", shuffle=" + (shuffle ? "on" : "off"));
        return lines;
    }

    // ---------------------------------------------------------------- переходы

    /** Остановить текущий трек (с отметкой о прослушивании), курсор не трогаем. */
    private void stopCurrent() {
        Entry e = current();
        if (e != null && started) {
            double played = Math.max(0, Math.min(e.durationMs, position())) / 1000.0;
            if (played >= 1) {
                bridge.played(e.url, played);
            }
        }
        started = false;
        paused = false;
        autoPaused = false;
    }

    /** Перейти к следующему треку (по кнопке или когда текущий доиграл). */
    private void advance() {
        stopCurrent();
        int n = list.size();
        if (cursor + 1 < n) {
            cursor++;
        } else if (repeat == RepeatMode.ALL && n > 0) {
            if (shuffle) {
                Collections.shuffle(list); // новый круг — новый порядок
            }
            cursor = 0;
        } else {
            cursor = n;
        }
        retainAudio();
    }

    /**
     * Держим файлы только у текущего, следующего и предыдущего трека (чтобы
     * «назад» не ждал повторного скачивания), остальные отпускаем.
     */
    private void retainAudio() {
        Entry cur = current();
        Entry next = upcoming();
        Entry prev = cursor > 0 && cursor - 1 < list.size() ? list.get(cursor - 1) : null;
        for (Entry e : list) {
            if (e != cur && e != next && e != prev && e.data != null) {
                dropAudio(e);
            }
        }
    }

    private void dropAudio(Entry e) {
        e.data = null;
        if (e.state == Entry.State.READY) {
            e.state = Entry.State.QUEUED;
        }
        for (Listener l : listeners.values()) {
            l.forget(e.id);
        }
    }

    // ---------------------------------------------------------------- цикл

    public void tick(MinecraftServer server) {
        this.server = server;
        long now = System.currentTimeMillis();
        boolean anyone = hasListeners();

        Entry cur = current();
        if (cur != null && cur.state == Entry.State.FAILED) {
            skipFailed();
            cur = current();
        }

        if (cur != null) {
            prepare(cur);
            if (!anyone) {
                // Слушать некому: не играем в пустой мир и не шлём площадкам «прослушивания».
                if (started && !paused) {
                    pausedPos = now - startAt;
                    paused = true;
                    autoPaused = true;
                }
            } else if (autoPaused) {
                startAt = now - pausedPos + graceMs(cur);
                paused = false;
                autoPaused = false;
                changed();
            }

            if (anyone && cur.state == Entry.State.READY && !started && !paused) {
                startAt = now + graceMs(cur);
                started = true;
                serial++;
                SyrRadio.LOG.info("SyrRadio: играет {}", cur.displayName());
                changed();
            } else if (started && !paused && now - startAt >= cur.durationMs + END_SLACK_MS) {
                if (repeat == RepeatMode.ONE) {
                    stopCurrent(); // тот же трек заново: serial вырастет при старте
                } else {
                    advance();
                }
                changed();
            }
        }

        Entry next = upcoming();
        if (next != null && next != cur) {
            prepare(next);
        }

        pumpChunks(cur, next);

        ticks++;
        sendJoinNotices(server);
        // Раз в 10 секунд напоминаем всем позицию: это лечит рассинхрон, если он набежал.
        if (ticks % 200 == 0 && started && !paused) {
            broadcastState();
        }
        if (dirty && ticks % 100 == 0) {
            save();
        }
    }

    /** «У тебя нет мода» / «мод старый» — лично игроку, через несколько секунд после входа. */
    private void sendJoinNotices(MinecraftServer server) {
        if (joinNotices.isEmpty()) {
            return;
        }
        var it = joinNotices.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getValue().dueTick() > ticks) {
                continue;
            }
            it.remove();
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null) {
                continue;
            }
            // Язык игры клиент присылает сразу после входа — к этому моменту он уже известен.
            boolean ru = p.getLanguage().toLowerCase().startsWith("ru");
            boolean outdated = e.getValue().outdated();
            String text = (ru
                ? (outdated ? ServerConfig.OUTDATED_MOD_RU : ServerConfig.MISSING_MOD_RU)
                : (outdated ? ServerConfig.OUTDATED_MOD_EN : ServerConfig.MISSING_MOD_EN)).get();
            if (text.isBlank()) {
                continue;
            }
            p.sendSystemMessage(joinMessage(text, ServerConfig.DOWNLOAD_URL.get()));
        }
    }

    /** Белый текст, «SyrRadio» в нём оранжевым, ссылка синяя и кликабельная. */
    private static Component joinMessage(String text, String url) {
        MutableComponent msg = Component.empty().withStyle(ChatFormatting.WHITE);
        String name = "SyrRadio";
        int from = 0;
        for (int at = text.indexOf(name); at >= 0; at = text.indexOf(name, from)) {
            msg.append(text.substring(from, at));
            msg.append(Component.literal(name).withStyle(ChatFormatting.GOLD));
            from = at + name.length();
        }
        msg.append(text.substring(from));
        if (!url.isBlank()) {
            msg.append(" ").append(Component.literal(url).withStyle(s -> s
                .withColor(ChatFormatting.BLUE)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(url)))));
        }
        return msg;
    }

    /** Текущий трек не скачался — идём дальше, но не по кругу бесконечно. */
    private void skipFailed() {
        for (int guard = 0; guard <= list.size(); guard++) {
            Entry cur = current();
            if (cur == null || cur.state != Entry.State.FAILED) {
                return;
            }
            if (cursor + 1 >= list.size() && repeat != RepeatMode.ALL) {
                cursor = list.size();
                started = false;
                changed();
                return;
            }
            advance();
        }
        cursor = list.size(); // всё в списке сломано
        started = false;
        changed();
    }

    private void prepare(Entry e) {
        if (e.state != Entry.State.QUEUED) {
            return;
        }
        e.state = Entry.State.PREPARING;
        bridge.audio(e.url).whenComplete((audio, err) -> server.execute(() -> {
            if (!list.contains(e)) {
                return;
            }
            if (err != null) {
                e.state = Entry.State.FAILED;
                Component why = BridgeClient.message(err);
                SyrRadio.LOG.warn("SyrRadio: не скачался {}: {}", e.displayName(), why.getString());
                broadcastNotice(Component.translatableWithFallback("syrradio.notice.track_failed",
                    "Couldn't load «%s»: %s", e.displayName(), why), true);
                changed();
                return;
            }
            if (audio.durationMs() > 0) {
                e.durationMs = audio.durationMs();
            }
            if (e != current() && e != upcoming()) {
                e.state = Entry.State.QUEUED; // пока качали, трек уехал из «текущий/следующий»
                changed();
                return;
            }
            e.data = audio.data();
            e.state = Entry.State.READY;
            changed();
        }));
    }

    /**
     * Сколько подождать со стартом, чтобы слушатели успели получить файл.
     * Если у всех он уже есть (его разослали заранее), почти не ждём.
     */
    private long graceMs(Entry e) {
        if (e.data == null) {
            return 150;
        }
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

    private void pumpChunks(Entry cur, Entry next) {
        int budget = ServerConfig.CHUNKS_PER_TICK.get();
        for (Listener l : listeners.values()) {
            if (!l.wantsAudio()) {
                continue;
            }
            int left = sendChunks(l, cur, budget);
            if (next != cur) {
                sendChunks(l, next, left);
            }
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

    private void changed() {
        markDirty();
        broadcastState();
    }

    private StatePacket buildState() {
        List<TrackInfo> tracks = new ArrayList<>(list.size());
        for (Entry e : list) {
            tracks.add(e.info());
        }
        Entry next = upcoming();
        return new StatePacket(tracks, cursor, started, paused, position(), repeat, shuffle, serial,
            next == null ? -1 : next.id);
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
