package io.github.chillestorange.service.cloud;

import io.github.chillestorange.config.GameSyncConfig;
import io.github.chillestorange.logging.GameSyncLogger;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Provider-agnostic contract for a cloud storage backend. GoogleDriveProvider
 * (in the gdrive subpackage) is the only implementation today, but every
 * method here is deliberately phrased in terms general enough to fit Dropbox,
 * OneDrive, etc. — folders containing named items, each with an id and a
 * modified time. SyncDiffEngine, FileTransferManager, and GameSyncService all
 * depend on this interface, never on a concrete provider.
 */
public interface CloudStorageProvider {

    /**
     * One BFS pass over the whole tree under rootFolderId, run concurrently
     * across a worker pool. Implemented here as a default method built from
     * listChildren, rather than as an abstract method every provider has to
     * satisfy — the traversal itself has nothing provider-specific in it, so
     * a future Dropbox/OneDrive implementation only needs listChildren and
     * gets tree-fetching for free.
     */
    default Map<String, List<CloudItem>> fetchTree(String rootFolderId) throws IOException, InterruptedException {
        Map<String, List<CloudItem>> tree = new ConcurrentHashMap<>();
        Set<String> visited = ConcurrentHashMap.newKeySet();

        // Using Thread pool with number of workers from the mod menu
        ExecutorService executor = Executors.newFixedThreadPool(GameSyncConfig.maxWorkers());

        try {
            fetchFolderAsync(rootFolderId, tree, visited, executor).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            } else if (cause instanceof InterruptedException) {
                throw (InterruptedException) cause;
            }
            throw new RuntimeException(e);
        } finally {
            executor.shutdownNow();
        }

        return tree;
    }

    /**
     * Recursive async helper for fetchTree.
     */
    private CompletableFuture<Void> fetchFolderAsync(String folderId,
                                                     Map<String, List<CloudItem>> tree,
                                                     Set<String> visited,
                                                     ExecutorService executor) {
        if (!visited.add(folderId)) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                return listChildren(folderId);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, executor).thenCompose(children -> {
            tree.put(folderId, children);
            GameSyncLogger.debug("Fetched folder {} ({} children)", folderId, children.size());

            List<CompletableFuture<Void>> childTasks = new ArrayList<>();
            for (CloudItem item : children) {
                if (item.isFolder()) {
                    childTasks.add(fetchFolderAsync(item.id(), tree, visited, executor));
                }
            }

            return CompletableFuture.allOf(childTasks.toArray(new CompletableFuture[0]));
        });
    }

    List<CloudItem> listChildren(String folderId) throws IOException, InterruptedException;

    Optional<CloudItem> findByNameInFolder(String filename, String folderId) throws IOException, InterruptedException;

    void downloadFile(String fileId, Path destination) throws IOException, InterruptedException;

    /**
     * Create a new file, or overwrite an existing one's content.
     *
     * @param existingFileId the remote id if this file is already known to
     * exist (SyncDiffEngine already resolved this while
     * building the changeset), or null for a brand-new
     * upload. Passing the known id avoids a redundant
     * existence-check lookup the caller already did the
     * work for.
     */
    String uploadOrReplace(Path localFile, String existingFileId, String folderId, String filename, Instant modifiedTime)
            throws IOException, InterruptedException;

    String createFolder(String parentFolderId, String name, Instant modifiedTime) throws IOException, InterruptedException;

    /**
     * Computes a local fingerprint comparable to a remote CloudItem's
     * contentFingerprint, using whatever algorithm this provider's remote side
     * uses. Implementations with no cheap way to do this may return null or
     * an empty string, which tells the diff engine to skip the "don't
     * re-upload if content didn't actually change" optimization and just
     * trust the modified-time comparison instead.
     */
    String computeLocalFingerprint(Path localFile) throws IOException;
}