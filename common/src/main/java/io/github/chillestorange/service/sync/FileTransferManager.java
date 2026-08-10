package io.github.chillestorange.service.sync;

import io.github.chillestorange.config.GameSyncConfig;
import io.github.chillestorange.logging.GameSyncLogger;
import io.github.chillestorange.service.cloud.CloudStorageProvider;
import io.github.chillestorange.service.sync.SyncDiffEngine.TransferTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Executes queued upload/download tasks, sequentially for small changesets
 * or via a thread pool for larger ones, with per-file retries. Depends only
 * on CloudStorageProvider, never a concrete provider.
 * <p>
 * java.net.http.HttpClient is thread-safe and shared across workers, so no
 * per-thread provider sessions are needed. Retry loops distinguish a
 * genuinely retryable IOException from thread interruption, which should
 * stop the retry loop rather than be slept through and retried.
 */
public final class FileTransferManager {

    private static final int THREAD_THRESHOLD = GameSyncConfig.threadThreshold();
    private static final int MAX_WORKERS = GameSyncConfig.maxWorkers();
    private static final int MAX_RETRIES = GameSyncConfig.maxRetries();
    private static final long RETRY_DELAY_MILLIS = GameSyncConfig.retryDelay();

    private final CloudStorageProvider provider;

    public FileTransferManager(CloudStorageProvider provider) {
        this.provider = provider;
    }

    public void runTransfers(List<TransferTask> toUpload, List<TransferTask> toDownload) {
        // Sort uploads so level.dat always goes last — a partial sync should
        // never leave the remote level.dat pointing at a world state that's
        // ahead of the region files backing it.
        List<TransferTask> orderedUploads = toUpload.stream()
                .sorted(Comparator.comparingInt(t -> t.name().equalsIgnoreCase("level.dat") ? 1 : 0))
                .toList();

        int total = orderedUploads.size() + toDownload.size();
        if (total == 0) {
            // Diff had folder tasks but every candidate file matched on fingerprint.
            GameSyncLogger.debug("World already in sync, nothing to transfer");
            return;
        }

        boolean useThreads = total >= THREAD_THRESHOLD;
        GameSyncLogger.info(orderedUploads.size() + " to upload, " + toDownload.size() + " to download ("
                + (useThreads ? "threaded, " + MAX_WORKERS + " workers" : "sequential") + ")");

        if (!useThreads) {
            for (TransferTask task : orderedUploads) uploadOne(task);
            for (TransferTask task : toDownload) downloadOne(task);
        } else {
            ExecutorService pool = Executors.newFixedThreadPool(MAX_WORKERS);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (TransferTask task : orderedUploads) futures.add(pool.submit(() -> uploadOne(task)));
                for (TransferTask task : toDownload) futures.add(pool.submit(() -> downloadOne(task)));
                for (Future<?> f : futures) {
                    try {
                        f.get();
                    } catch (Exception ignored) {
                        // Already logged inside uploadOne/downloadOne's own handling.
                    }
                }
            } finally {
                pool.shutdown();
            }
        }

        GameSyncLogger.info("All transfers completed");
    }

    private void uploadOne(TransferTask task) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                provider.uploadOrReplace(task.localPath(), task.remoteId(), task.parentFolderId(), task.name(),
                        Files.getLastModifiedTime(task.localPath()).toInstant());
                GameSyncLogger.debug("[UP] " + task.name());
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                GameSyncLogger.warn("Upload interrupted: " + task.name());
                return; // don't retry — the thread is being told to stop
            } catch (IOException e) {
                if (attempt < MAX_RETRIES - 1) {
                    GameSyncLogger.warn("Upload retry " + (attempt + 1) + "/" + MAX_RETRIES + ": " + task.name());
                    if (sleep()) return;
                } else {
                    GameSyncLogger.error("Upload failed after " + MAX_RETRIES + " attempts: " + task.name(), e);
                }
            }
        }
    }

    private void downloadOne(TransferTask task) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                provider.downloadFile(task.remoteId(), task.localPath());

                // Preserve the remote's modified timestamp locally so future syncs
                // compare correctly.
                long ts = task.remoteModifiedTime().toEpochMilli();
                Files.setLastModifiedTime(task.localPath(), FileTime.fromMillis(ts));

                GameSyncLogger.debug("[DN] " + task.name());
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                GameSyncLogger.warn("Download interrupted: " + task.name());
                return;
            } catch (IOException e) {
                if (attempt < MAX_RETRIES - 1) {
                    GameSyncLogger.warn("Download retry " + (attempt + 1) + "/" + MAX_RETRIES + ": " + task.name());
                    if (sleep()) return;
                } else {
                    GameSyncLogger.error("Download failed after " + MAX_RETRIES + " attempts: " + task.name(), e);
                }
            }
        }
    }

    /** Returns true if interrupted while waiting, so the caller can stop retrying. */
    private static boolean sleep() {
        try {
            Thread.sleep(FileTransferManager.RETRY_DELAY_MILLIS);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }
}