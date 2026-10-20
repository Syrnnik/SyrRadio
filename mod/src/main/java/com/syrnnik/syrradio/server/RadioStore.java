package com.syrnnik.syrradio.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.syrnnik.syrradio.SyrRadio;
import com.syrnnik.syrradio.net.RepeatMode;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Список треков в папке мира (syrradio.json): переживает перезапуск сервера.
 * Сами файлы не храним — после перезапуска треки скачиваются заново.
 */
final class RadioStore {
    record SavedTrack(int id, String url, String title, String artist, String source, String requestedBy, long durationMs) {}

    record Saved(int nextId, int cursor, RepeatMode repeat, boolean shuffle, List<SavedTrack> tracks) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;

    RadioStore(MinecraftServer server) {
        this.file = server.getWorldPath(LevelResource.ROOT).resolve("syrradio.json");
    }

    Saved load() {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            Saved s = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Saved.class);
            return s == null || s.tracks() == null ? null : s;
        } catch (IOException | RuntimeException e) {
            SyrRadio.LOG.warn("SyrRadio: не прочитался {}, начинаю с пустого списка", file, e);
            return null;
        }
    }

    void save(Saved saved) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, GSON.toJson(saved), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            SyrRadio.LOG.warn("SyrRadio: не сохранился {}", file, e);
        }
    }
}
