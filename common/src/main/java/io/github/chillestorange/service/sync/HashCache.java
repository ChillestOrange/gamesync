package io.github.chillestorange.service.sync;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Persists content fingerprints keyed by relative path + mtime, so unchanged
 * files are never re-fingerprinted between sync cycles. The actual algorithm
 * is supplied by whichever CloudStorageProvider is in use (Drive uses md5; a
 * future provider might use something else entirely), rather than being
 * hardcoded here — this class stays provider-agnostic.
 * <p>
 * Note: deserializing a record-valued Map requires Gson 2.10+. If your mod's
 * bundled Gson is older, swap Entry for a plain class with a no-arg constructor
 * and getters instead.
 */
public final class HashCache {

    private final Path cacheFile;
    private final Gson gson = new Gson();
    private final Map<String, Entry> entries;
    private final FingerprintFunction fingerprintFunction;
    public HashCache(Path cacheFile, FingerprintFunction fingerprintFunction) {
        this.cacheFile = cacheFile;
        this.fingerprintFunction = fingerprintFunction;
        this.entries = load(cacheFile, gson);
    }

    private static Map<String, Entry> load(Path file, Gson gson) {
        if (!Files.exists(file)) return new HashMap<>();
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, Entry> loaded = gson.fromJson(json, new TypeToken<Map<String, Entry>>() {
            }.getType());
            return loaded != null ? loaded : new HashMap<>();
        } catch (IOException | JsonSyntaxException e) {
            return new HashMap<>();
        }
    }

    private static double roundToMillisPrecision(double seconds) {
        return Math.round(seconds * 1000.0) / 1000.0;
    }

    public void save() {
        try {
            Path parent = cacheFile.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(cacheFile, gson.toJson(entries), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Non-fatal — worst case is a few unnecessary re-fingerprints next cycle.
        }
    }

    /**
     * Returns the fingerprint of localFile, reusing the cached value when mtime is unchanged.
     */
    public String getFingerprint(Path localFile, String relativeKey) throws IOException {
        double mtime = roundToMillisPrecision(Files.getLastModifiedTime(localFile).toMillis() / 1000.0);
        Entry cached = entries.get(relativeKey);
        if (cached != null && cached.mtime() == mtime) {
            return cached.fingerprint();
        }

        String fingerprint = fingerprintFunction.compute(localFile);
        entries.put(relativeKey, new Entry(mtime, fingerprint));
        return fingerprint;
    }

    @FunctionalInterface
    public interface FingerprintFunction {
        String compute(Path file) throws IOException;
    }

    public record Entry(double mtime, String fingerprint) {
    }
}