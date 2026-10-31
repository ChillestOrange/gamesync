package io.github.chillestorange.config;

import com.google.gson.GsonBuilder;
import dev.isxander.yacl3.config.v2.api.ConfigClassHandler;
import dev.isxander.yacl3.config.v2.api.SerialEntry;
import dev.isxander.yacl3.config.v2.api.serializer.GsonConfigSerializerBuilder;
import io.github.chillestorange.GameSyncConstants;
import io.github.chillestorange.platform.PlatformServices;
import io.github.chillestorange.service.cloud.CloudStorageFactory.Credentials;
import io.github.chillestorange.service.cloud.CloudStorageFactory.ProviderType;
import io.github.chillestorange.service.cloud.s3.S3Provider;
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

    @SerialEntry(comment = "World names to sync. Must match each save folder name exactly. " +
            "Add one entry per world you want GameSync to manage.")
    public List<String> targetWorlds = new ArrayList<>();

    @SerialEntry(comment = "Triggers an additional sync at given intervals.")
    public boolean autosaveSyncEnabled = false;

    @SerialEntry(comment = "Minimum interval between autosave-triggered syncs, in ticks." +
            "20 ticks = 1 second, so the default of 6000 equals 5 minutes.")
    public int autosaveIntervalTicks = 6000;

    @SerialEntry(comment = "Target cloud provider for sync operations: GOOGLE_DRIVE, or S3 for any S3-compatible " +
            "storage (AWS S3, Cloudflare R2, Backblaze B2, MinIO).")
    public String cloudProvider = "GOOGLE_DRIVE";

    @SerialEntry(comment = "Root Google Drive folder ID, taken from the folder's URL: " +
            "drive.google.com/drive/folders/<FOLDER_ID>. Each synced world gets its own " +
            "subfolder created automatically under this one.")
    public String remoteFolderId = "";

    @SerialEntry(comment = "OAuth 2.0 Client ID from a 'Desktop app' credential, generated in Google Cloud " +
            "Console under APIs & Services > Credentials. Required for Drive authentication.")
    public String clientId = "";

    @SerialEntry(comment = "OAuth 2.0 Client Secret paired with the Client ID above. Per Google's own " +
            "documentation, desktop-app client secrets are not confidential by design, but this " +
            "file should still not be shared or committed to version control.")
    public String clientSecret = "";

    @SerialEntry(comment = "S3 API endpoint, e.g. https://<ACCOUNT_ID>.r2.cloudflarestorage.com for Cloudflare R2 " +
            "or https://s3.<REGION>.amazonaws.com for AWS.")
    public String s3Endpoint = "";

    @SerialEntry(comment = "S3 region used for request signing. Cloudflare R2 uses 'auto'; for AWS use the " +
            "bucket's region, e.g. eu-central-1.")
    public String s3Region = "auto";

    @SerialEntry(comment = "Name of the bucket worlds are stored in.")
    public String s3Bucket = "";

    @SerialEntry(comment = "Key prefix inside the bucket. Each synced world is stored under <prefix>/<world name>/. " +
            "Leave empty to use the bucket root.")
    public String s3Prefix = "gamesync";

    @SerialEntry(comment = "Access key ID of an S3 API token with read and write access to the bucket.")
    public String s3AccessKeyId = "";

    @SerialEntry(comment = "Secret access key paired with the access key ID above. Unlike Google's desktop-app " +
            "client secret, this IS confidential: anyone who has it can read and overwrite your bucket.")
    public String s3SecretAccessKey = "";

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
    public static List<String> targetWorlds() {
        return HANDLER.instance().targetWorlds;
    }

    /**
     * Whether the given save-folder name is one of the worlds configured for
     * syncing. Centralized here rather than having each mixin call
     * targetWorlds().contains(...) directly, so the membership check has one
     * place to live if it ever needs to become case-insensitive, trimmed, etc.
     */
    public static boolean isTargetWorld(String levelId) {
        return HANDLER.instance().targetWorlds.contains(levelId);
    }

    /**
     * Adds or removes a world from targetWorlds and saves.
     */
    public static void setTargetWorld(String levelId, boolean enabled) {
        List<String> worlds = new ArrayList<>(HANDLER.instance().targetWorlds);
        worlds.remove(levelId);
        if (enabled) worlds.add(levelId);
        HANDLER.instance().targetWorlds = worlds;
        save();
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
                            "'. Valid values: GOOGLE_DRIVE, S3", e);
        }
    }

    /**
     * Drive folder id for Drive, normalized key prefix for S3.
     */
    public static String remoteRootId() {
        return switch (providerType()) {
            case GOOGLE_DRIVE -> HANDLER.instance().remoteFolderId;
            case S3 -> S3Provider.normalizePrefix(HANDLER.instance().s3Prefix);
        };
    }

    /**
     * Builds the credential object GameSyncService.initialize() needs.
     * tokenStorePath is derived from configDir() so it doesn't need its
     * own config field — the user never needs to know where tokens live.
     */
    public static Credentials credentials() {
        GameSyncConfig c = HANDLER.instance();
        return switch (providerType()) {
            case GOOGLE_DRIVE -> new Credentials.OAuthCredentials(
                    c.clientId,
                    c.clientSecret,
                    configDir().resolve("drive_tokens.json")
            );
            case S3 -> new Credentials.S3Credentials(
                    c.s3Endpoint, c.s3Region, c.s3Bucket, c.s3AccessKeyId, c.s3SecretAccessKey
            );
        };
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