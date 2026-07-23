package io.github.chillestorange.config;

import com.google.gson.GsonBuilder;
import dev.isxander.yacl3.config.v2.api.ConfigClassHandler;
import dev.isxander.yacl3.config.v2.api.SerialEntry;
import dev.isxander.yacl3.config.v2.api.serializer.GsonConfigSerializerBuilder;
import io.github.chillestorange.GameSyncConstants;
import io.github.chillestorange.platform.PlatformServices;
import io.github.chillestorange.service.cloud.CloudStorageFactory.Credentials;
import io.github.chillestorange.service.cloud.CloudStorageFactory.ProviderType;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class GameSyncConfig {

    public static final ConfigClassHandler<GameSyncConfig> HANDLER =
            ConfigClassHandler.createBuilder(GameSyncConfig.class)
                    .id(Identifier.fromNamespaceAndPath(
                            GameSyncConstants.MOD_ID,
                            "config"
                    ))
                    .serializer(config -> GsonConfigSerializerBuilder.create(config)
                            .setPath(configFilePath())
                            .appendGsonBuilder(GsonBuilder::setPrettyPrinting)
                            .setJson5(true)
                            .build())
                    .build();

    @SerialEntry(comment = "World name to sync. Must match the save folder name exactly.")
    public String targetWorld = "";

    @SerialEntry(comment = "Triggers an additional sync at given intervals.")
    public boolean autosaveSyncEnabled = false;

    @SerialEntry(comment = "Minimum interval between autosave-triggered syncs, in ticks." +
            "20 ticks = 1 second, so the default of 6000 equals 5 minutes.")
    public int autosaveIntervalTicks = 6000;

    @SerialEntry(comment = "Target cloud provider for sync operations. Currently the only supported value is GOOGLE_DRIVE.")
    public String cloudProvider = "GOOGLE_DRIVE";

    @SerialEntry(comment = "Destination Google Drive folder ID, taken from the folder's URL: " +
            "drive.google.com/drive/folders/<FOLDER_ID>")
    public String remoteFolderId = "";

    @SerialEntry(comment = "OAuth 2.0 Client ID from a 'Desktop app' credential, generated in Google Cloud " +
            "Console under APIs & Services > Credentials. Required for Drive authentication.")
    public String clientId = "";

    @SerialEntry(comment = "OAuth 2.0 Client Secret paired with the Client ID above. Per Google's own " +
            "documentation, desktop-app client secrets are not confidential by design, but this " +
            "file should still not be shared or committed to version control.")
    public String clientSecret = "";

    @SerialEntry(comment = "[ Advanced ] File count threshold above which sync execution switches from " +
            "sequential to multithreaded.")
    public int threadThreshold = 5;

    @SerialEntry(comment = "[ Advanced ] Maximum number of concurrent file transfers in multithreaded mode.")
    public int maxWorkers = 12;

    @SerialEntry(comment = "[ Advanced ] Maximum retry attempts per file before the transfer is marked as failed.")
    public int maxRetries = 3;

    @SerialEntry(comment = "[ Advanced ] Delay between retry attempts, in milliseconds.")
    public long retryDelay = 1500;

    @SerialEntry(comment = "[ Advanced ] Enables verbose debug logging for diagnostics.")
    public boolean debugMode = false;

    public static boolean load() {
        boolean result = HANDLER.load();
        writeSpacedConfig();
        return result;
    }

    public static void save() {
        HANDLER.save();
        writeSpacedConfig();
    }

    // Accessors.
    public static String targetWorld() {
        return HANDLER.instance().targetWorld;
    }

    public static boolean autosaveSyncEnabled() {
        return HANDLER.instance().autosaveSyncEnabled;
    }

    public static int autosaveIntervalTicks() {
        return HANDLER.instance().autosaveIntervalTicks;
    }

    public static int threadThreshold() {
        return HANDLER.instance().threadThreshold;
    }

    public static int maxWorkers() {
        return HANDLER.instance().maxWorkers;
    }

    public static int maxRetries() {
        return HANDLER.instance().maxRetries;
    }

    public static long retryDelay() {
        return HANDLER.instance().retryDelay;
    }

    public static boolean debugMode() {
        return HANDLER.instance().debugMode;
    }

    /**
     * Converts the cloudProvider string from config into a typed ProviderType.
     * Throws IllegalArgumentException with a clear message if the value in the
     * JSON doesn't match any known provider, rather than silently NPE-ing later
     * inside a sync cycle.
     */
    public static ProviderType providerType() {
        String raw = HANDLER.instance().cloudProvider.trim().toUpperCase();
        try {
            return ProviderType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown cloudProvider in gamesync.json5: '" + raw +
                            "'. Valid values: GOOGLE_DRIVE", e);
        }
    }

    public static String remoteFolderId() {
        return HANDLER.instance().remoteFolderId;
    }

    /**
     * Builds the credential object GameSyncService.initialize() needs.
     * tokenStorePath is derived from configDir() so it doesn't need its
     * own config field — the user never needs to know where tokens live.
     */
    public static Credentials credentials() {
        return new Credentials.OAuthCredentials(
                HANDLER.instance().clientId,
                HANDLER.instance().clientSecret,
                configDir().resolve("drive_tokens.json")
        );
    }

    /**
     * Dedicated subdirectory under Fabric's config dir for GameSync runtime
     * files (OAuth tokens, hash cache). Kept separate from gamesync.json5
     * itself so these files don't appear alongside user-edited config.
     */
    public static Path configDir() {
        return PlatformServices.PLATFORM.getConfigDirectory().resolve(GameSyncConstants.MOD_ID);
    }

    private static void writeSpacedConfig() {
        Path file = configFilePath();
        if (!Files.isRegularFile(file)) {
            return;
        }

        try {
            List<String> original = Files.readAllLines(file);
            List<String> spaced = getSpacedConfig(original);

            Files.writeString(file, String.join("\n", spaced) + "\n");
        } catch (IOException ignored) {
            // Formatting is cosmetic only, so a failure here is safe to ignore.
        }
    }

    private static @NonNull List<String> getSpacedConfig(List<String> original) {
        List<String> spaced = new ArrayList<>(original.size() + 16);

        for (String line : original) {
            boolean isCommentLine = line.startsWith("\t// ");
            boolean previousWasComment = !spaced.isEmpty() && spaced.getLast().startsWith("\t// ");
            boolean previousWasOpeningBrace = !spaced.isEmpty() && spaced.getLast().equals("{");
            boolean previousWasBlank = !spaced.isEmpty() && spaced.getLast().isEmpty();

            if (isCommentLine && !previousWasComment && !previousWasOpeningBrace && !previousWasBlank) {
                spaced.add("");
            }
            spaced.add(line);
        }
        return spaced;
    }

    private static Path configFilePath() {
        return PlatformServices.PLATFORM.getConfigDirectory().resolve(GameSyncConstants.MOD_ID + ".json5");
    }
}