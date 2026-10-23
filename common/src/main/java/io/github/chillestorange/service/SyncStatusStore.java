package io.github.chillestorange.service;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import io.github.chillestorange.config.GameSyncConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Last sync result per world (by save folder name), saved to sync_status.json.
 * Written from the sync thread and read from the render thread.
 */
public final class SyncStatusStore {

    private static final Gson GSON = new Gson();
    private static final Path FILE = GameSyncConfig.configDir().resolve("sync_status.json");
    private static final Map<String, WorldStatus> STATUSES = load();

    private SyncStatusStore() {
    }

    /**
     * Timestamps in epoch millis, 0 = never.
     */
    public record WorldStatus(long lastSuccessMillis, long lastFailureMillis, String lastError) {
        public boolean lastAttemptFailed() {
            return lastFailureMillis > lastSuccessMillis;
        }
    }

    public static WorldStatus get(String worldName) {
        return STATUSES.get(worldName);
    }

    static void recordSuccess(String worldName) {
        STATUSES.compute(worldName, (k, old) -> new WorldStatus(
                System.currentTimeMillis(), old != null ? old.lastFailureMillis() : 0L, null));
        save();
    }

    static void recordFailure(String worldName, Throwable error) {
        String message = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        STATUSES.compute(worldName, (k, old) -> new WorldStatus(
                old != null ? old.lastSuccessMillis() : 0L, System.currentTimeMillis(), message));
        save();
    }

    private static Map<String, WorldStatus> load() {
        Map<String, WorldStatus> map = new ConcurrentHashMap<>();
        if (!Files.exists(FILE)) return map;
        try {
            Map<String, WorldStatus> loaded = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, WorldStatus>>() {
                    }.getType());
            if (loaded != null) map.putAll(loaded);
        } catch (IOException | JsonSyntaxException e) {
            // Start fresh
        }
        return map;
    }

    private static synchronized void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(STATUSES), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Non-fatal
        }
    }
}
