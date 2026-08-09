package io.github.chillestorange.service;

import io.github.chillestorange.logging.GameSyncLogger;
import io.github.chillestorange.service.cloud.CloudItem;
import io.github.chillestorange.service.cloud.CloudStorageFactory;
import io.github.chillestorange.service.cloud.CloudStorageFactory.Credentials;
import io.github.chillestorange.service.cloud.CloudStorageFactory.ProviderType;
import io.github.chillestorange.service.cloud.CloudStorageProvider;
import io.github.chillestorange.service.sync.*;
import io.github.chillestorange.service.sync.SyncDiffEngine.FolderTask;
import io.github.chillestorange.util.FormatUtils;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * This is the call site that replaces:
 * <p>
 * new ProcessBuilder(file_accesser.exe path...).start();
 * <p>
 * with:
 * <p>
 * GameSyncService.initialize(...);                      // once, at mod startup
 * GameSyncService.runSyncCycle(worldPath, worldName);    // every trigger after that
 * <p>
 * wired into your AutosaveSyncListener / WorldSaveMixin / WorldJoinMixin
 * wherever the process used to get launched.
 */
public final class GameSyncService {

    // Replaces lock.py's PID-file lock entirely. That existed because
    // file_accesser.exe could be launched as a brand-new OS process every
    // cycle; here everything runs in one JVM, so a flag is enough to stop two
    // sync cycles overlapping (e.g. an autosave-triggered sync racing a
    // world-join-triggered one).
    private static final AtomicBoolean SYNC_RUNNING = new AtomicBoolean(false);
    private static final ExecutorService SYNC_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gamesync-cycle");
        t.setDaemon(true);
        return t;
    });
    private static final String LEVEL_DAT = "level.dat";
    private static final String REMOTE_LEVEL_DAT = "remote_level.dat";

    // Resolved-folder-id cache, one entry per world name. Safe without extra
    // locking: SYNC_RUNNING plus the single-threaded SYNC_EXECUTOR already
    // guarantee only one doSync() runs at a time, so there's no concurrent-
    // creation race on the cloud side to guard against here.
    private static final Map<String, String> worldFolderIdCache = new ConcurrentHashMap<>();

    private static volatile CloudStorageProvider provider;
    private static volatile HashCache hashCache;
    // The app's shared root folder on the cloud side. Each synced world gets
    // its own subfolder created/found underneath this one — see
    // resolveWorldFolderId. This used to be passed straight into
    // SyncDiffEngine as the sync target itself, back when the mod only ever
    // synced a single world directly inside it.
    private static volatile String appRootFolderId;
    private static volatile Path configDir;

    private GameSyncService() {
    }

    public static boolean isSyncing() {
        return SYNC_RUNNING.get();
    }

    /**
     * Call once at mod startup (e.g. from GameSyncClient's initializer), not
     * on every sync cycle. Building the provider, authenticator, and hash
     * cache fresh every cycle was forced when this was a freshly-launched
     * process each time; now that it's one long-lived JVM, doing that every
     * cycle would just mean re-reading token/hash-cache JSON off disk and
     * spinning up duplicate HttpClient instances for no reason.
     */
    public static void initialize(
            ProviderType providerType, Credentials credentials, String appRootFolderId, Path configDir
    ) {
        HttpClient sharedHttpClient = HttpClient.newHttpClient();
        provider = CloudStorageFactory.create(providerType, credentials, sharedHttpClient);
        hashCache = new HashCache(configDir.resolve("sync_hash_cache.json"), provider::computeLocalFingerprint);
        GameSyncService.appRootFolderId = appRootFolderId;
        GameSyncService.configDir = configDir;
    }

    /**
     * Runs one full sync cycle for the given world. Hops onto a background
     * thread internally and never blocks the calling thread — safe to call
     * directly from a mixin callback on the client thread.
     */
    public static CompletableFuture<Void> runSyncCycle(Path worldPath, String worldName) {
        return runSyncCycle(worldPath, worldName, () -> {
        }, _ -> {
        });
    }

    public static CompletableFuture<Void> runSyncCycle(
            Path worldPath, String worldName, Runnable onSuccess, Consumer<Throwable> onFailure) {

        if (provider == null) {
            throw new IllegalStateException("GameSyncService.initialize(...) must be called before runSyncCycle(...)");
        }
        if (!SYNC_RUNNING.compareAndSet(false, true)) {
            GameSyncLogger.info("Sync already running, skipping this trigger");
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                doSync(worldPath, worldName);
                onSuccess.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                GameSyncLogger.error("Sync cycle interrupted", e);
                onFailure.accept(e);
            } catch (Exception e) {
                GameSyncLogger.error("Sync cycle failed", e);
                onFailure.accept(e);
            } finally {
                SYNC_RUNNING.set(false);
            }
        }, SYNC_EXECUTOR);
    }

    private static void doSync(Path worldPath, String worldName) throws IOException, InterruptedException {
        String worldFolderId = resolveWorldFolderId(worldName);

        // Check for level.dat specifically, not just the directory. On a world-join
        // trigger, Minecraft creates the world directory before writing level.dat,
        // so Files.exists(worldPath) can return true while level.dat doesn't exist
        // yet — which caused a NoSuchFileException when the old check just tested
        // the directory.
        boolean firstRun = !Files.exists(worldPath.resolve(LEVEL_DAT));
        SyncDirection direction;

        if (firstRun) {
            // The original had a real bug here: it called orchestrator.sync(direction="-1")
            // with a comment saying "force download everything", but "-1" is the no-op
            // direction in that codebase, so sync() returned immediately and first-time
            // download never actually happened. Fixed here by setting DOWNLOAD directly.
            GameSyncLogger.info("World not found locally, downloading from cloud storage");
            Files.createDirectories(worldPath);
            direction = SyncDirection.DOWNLOAD;
        } else {
            CloudItem remoteLevelDatItem = provider.findByNameInFolder(LEVEL_DAT, worldFolderId).orElse(null);

            if (remoteLevelDatItem == null) {
                // Local world exists but the remote has nothing yet (new/empty
                // remote folder, or level.dat missing there for any other reason).
                // There's no remote level.dat to compare against, so there's
                // nothing to base a direction decision on — force a full upload
                // rather than failing the cycle.
                GameSyncLogger.info("level.dat not found remotely, forcing upload of local world");
                direction = SyncDirection.UPLOAD;
            } else {
                Path remoteLevelDat = configDir.resolve(REMOTE_LEVEL_DAT);
                provider.downloadFile(remoteLevelDatItem.id(), remoteLevelDat);

                LevelSync.Summary local = LevelSync.read(worldPath.resolve(LEVEL_DAT));
                LevelSync.Summary remote = LevelSync.read(remoteLevelDat);

                GameSyncLogger.debug("Level.dat comparison: local ticks={} remote ticks={}", local.time(), remote.time());

                direction = LevelSync.compare(local, remote);
            }
        }

        if (direction == SyncDirection.NO_OP) {
            GameSyncLogger.info("Worlds already in sync, nothing to do");
            return;
        }

        GameSyncLogger.info("Starting sync, direction: {}", direction);

        Map<String, List<CloudItem>> tree = provider.fetchTree(worldFolderId);
        GameSyncLogger.info("Remote tree fetched: {} folders mapped", tree.size());

        SyncDiffEngine diffEngine = new SyncDiffEngine();
        SyncDiffEngine.Result diff = diffEngine.buildChangeset(
                worldPath, worldFolderId, tree, hashCache, direction);

        GameSyncLogger.info("{} uploads, {} downloads, {} folder(s) to create. Total size: {}",
                diff.toUpload().size(), diff.toDownload().size(),
                diff.folderTasks().size(), FormatUtils.formatBytes(diff.totalBytes()));

        // Folder creation happens synchronously here, before the transfer pool
        // starts — two threads racing to create the same folder on either side
        // causes intermittent errors.
        for (FolderTask task : diff.folderTasks()) {
            switch (task) {
                case FolderTask.CreateLocal(Path path) -> Files.createDirectories(path);
                case FolderTask.CreateRemote(Path localPath, String parentFolderId, String name) -> {
                    String newFolderId = provider.createFolder(parentFolderId, name,
                            Files.getLastModifiedTime(localPath).toInstant());

                    // The diff couldn't see inside this folder while building the
                    // changeset, since it didn't exist remotely yet. Now that it has a
                    // real id, walk its local contents directly so anything inside
                    // gets uploaded this same cycle instead of waiting one cycle late.
                    diffEngine.discoverNewLocalFolderContents(provider, localPath, newFolderId, diff.toUpload());
                }
            }
        }

        new FileTransferManager(provider).runTransfers(diff.toUpload(), diff.toDownload());

        hashCache.save();
        GameSyncLogger.info("Sync cycle complete");
    }

    /**
     * Finds the world's own subfolder under the configured app root, creating it
     * on first sync for that world. Everything else in doSync operates entirely
     * inside this folder rather than the shared root, so worlds never see each
     * other's files — this is what makes multi-world sync a straightforward
     * addition: it's just a loop over world names calling this per name,
     * nothing about SyncDiffEngine or the walk logic needs to change.
     * <p>
     * Cached per world name for the lifetime of the JVM to avoid a Drive lookup
     * on every autosave cycle once the folder's been resolved once.
     */
    private static String resolveWorldFolderId(String worldName) throws IOException, InterruptedException {
        String cached = worldFolderIdCache.get(worldName);
        if (cached != null) {
            return cached;
        }

        CloudItem existing = provider.findByNameInFolder(worldName, appRootFolderId).orElse(null);
        String worldFolderId;

        if (existing != null) {
            if (!existing.isFolder()) {
                throw new IOException("A file named '" + worldName + "' already exists in the app root folder, "
                        + "blocking creation of that world's sync folder");
            }
            worldFolderId = existing.id();
            GameSyncLogger.debug("Found existing remote folder for world {}: {}", worldName, worldFolderId);
        } else {
            GameSyncLogger.info("No remote folder found for world '{}', creating one", worldName);
            worldFolderId = provider.createFolder(appRootFolderId, worldName, Instant.now());
        }

        worldFolderIdCache.put(worldName, worldFolderId);
        return worldFolderId;
    }
}