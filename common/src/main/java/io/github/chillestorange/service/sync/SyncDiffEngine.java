package io.github.chillestorange.service.sync;

import io.github.chillestorange.logging.GameSyncLogger;
import io.github.chillestorange.service.cloud.CloudItem;
import io.github.chillestorange.service.cloud.CloudStorageProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Walks the local world folder and the in-memory remote tree together,
 * building flat upload/download/folder-creation task lists. No network calls
 * happen here — every remote lookup is a map access into the tree fetched up
 * front by CloudStorageProvider.fetchTree. Depends only on the
 * provider-agnostic CloudItem, never on anything Drive-specific.
 * <p>
 * FolderTask and TransferTask are nested here rather than in a separate
 * SyncTasks file — they exist purely to be the contents of Result, so
 * splitting them out had no real benefit.
 */
public final class SyncDiffEngine {

    private static final Set<String> IGNORED_FILES = Set.of("session.lock");
    // Defensive fallback in case a file is still mid-write when scanned.
    private static final long SKIP_IF_MODIFIED_WITHIN_MILLIS = 3000;

    public Result buildChangeset(
            Path localRoot,
            String rootFolderId,
            Map<String, List<CloudItem>> remoteTree,
            HashCache hashCache,
            SyncDirection direction
    ) throws IOException {
        List<TransferTask> toUpload = new ArrayList<>();
        List<TransferTask> toDownload = new ArrayList<>();
        List<FolderTask> folderTasks = new ArrayList<>();
        AtomicLong totalBytes = new AtomicLong();

        walk(localRoot, localRoot, rootFolderId, remoteTree, hashCache, direction,
                toUpload, toDownload, folderTasks, totalBytes);

        return new Result(toUpload, toDownload, folderTasks, totalBytes.get());
    }

    private void walk(
            Path localRoot, Path localPath, String folderId,
            Map<String, List<CloudItem>> remoteTree, HashCache hashCache, SyncDirection direction,
            List<TransferTask> toUpload, List<TransferTask> toDownload, List<FolderTask> folderTasks,
            AtomicLong totalBytes
    ) throws IOException {

        List<CloudItem> children = remoteTree.getOrDefault(folderId, List.of());
        Map<String, CloudItem> serverMap = new HashMap<>();
        for (CloudItem item : children) serverMap.put(item.name(), item);

        Set<String> localItems = new HashSet<>();
        if (Files.isDirectory(localPath)) {
            try (var stream = Files.list(localPath)) {
                stream.forEach(p -> localItems.add(p.getFileName().toString()));
            }
        }

        Set<String> allNames = new HashSet<>(localItems);
        allNames.addAll(serverMap.keySet());

        for (String name : allNames) {
            if (IGNORED_FILES.contains(name)) continue;

            Path localFile = localPath.resolve(name);
            CloudItem serverItem = serverMap.get(name);

            boolean existsLocally = Files.exists(localFile);
            boolean existsOnServer = serverItem != null;
            boolean isFolder = (existsOnServer && serverItem.isFolder())
                    || (existsLocally && Files.isDirectory(localFile));

            if (isFolder) {
                String remoteId = existsOnServer ? serverItem.id() : null;

                if (!existsLocally && direction == SyncDirection.DOWNLOAD) {
                    GameSyncLogger.debug("Queueing local folder creation: {}", localFile);
                    folderTasks.add(new FolderTask.CreateLocal(localFile));
                }
                if (!existsOnServer && direction == SyncDirection.UPLOAD) {
                    GameSyncLogger.debug("Queueing remote folder creation: {}", localFile);
                    folderTasks.add(new FolderTask.CreateRemote(localFile, folderId, name));
                }

                // Recurse regardless — even with one side missing, files inside
                // still need walking once the matching folder exists.
                if (remoteId != null) {
                    walk(localRoot, localFile, remoteId, remoteTree, hashCache, direction,
                            toUpload, toDownload, folderTasks, totalBytes);
                }
                continue;
            }

            if (existsLocally) {
                long ageMillis = System.currentTimeMillis() - Files.getLastModifiedTime(localFile).toMillis();
                if (ageMillis < SKIP_IF_MODIFIED_WITHIN_MILLIS) {
                    GameSyncLogger.debug("Skipping mid-write file: {} (age {}ms)", localFile, ageMillis);
                    continue; // mid-write guard
                }
            }

            // total size of everything scanned, local size wins when both exist
            if (existsLocally) {
                totalBytes.addAndGet(Files.size(localFile));
            } else if (existsOnServer) {
                totalBytes.addAndGet(serverItem.size());
            }

            String relKey = localRoot.relativize(localFile).toString();

            if (existsLocally && existsOnServer) {
                // Truncate to whole seconds before comparing — avoids spurious "changed"
                // results from sub-second precision mismatches between local mtime and
                // the remote timestamp.
                Instant remoteModified = serverItem.modifiedTime().truncatedTo(ChronoUnit.SECONDS);
                Instant localModified = Files.getLastModifiedTime(localFile).toInstant().truncatedTo(ChronoUnit.SECONDS);

                if (direction == SyncDirection.UPLOAD && localModified.isAfter(remoteModified)) {
                    if (contentLikelyChanged(localFile, relKey, serverItem, hashCache)) {
                        GameSyncLogger.debug("Queueing upload: {} (local={} remote={})", relKey, localModified, remoteModified);
                        toUpload.add(new TransferTask(localFile, serverItem.id(), folderId, name, null));
                    } else {
                        // Kept at plain DEBUG, not verbose: explains why a file whose
                        // mtime changed still didn't get re-uploaded.
                        GameSyncLogger.debug("Fingerprint match, skipping upload: {}", localFile);
                    }
                } else if (direction == SyncDirection.DOWNLOAD && remoteModified.isAfter(localModified)) {
                    GameSyncLogger.debug("Queueing download: {} (local={} remote={})", relKey, localModified, remoteModified);
                    toDownload.add(new TransferTask(localFile, serverItem.id(), folderId, name, serverItem.modifiedTime()));
                }

            } else if (existsLocally) {
                if (direction == SyncDirection.UPLOAD) {
                    GameSyncLogger.debug("Queueing upload for local-only file: {}", relKey);
                    toUpload.add(new TransferTask(localFile, null, folderId, name, null));
                }
            } else { // existsOnServer only
                if (direction == SyncDirection.DOWNLOAD) {
                    GameSyncLogger.debug("Queueing download for remote-only file: {}", relKey);
                    toDownload.add(new TransferTask(localFile, serverItem.id(), folderId, name, serverItem.modifiedTime()));
                }
            }
        }
    }

    /**
     * Called right after GameSyncService creates a brand-new remote folder —
     * walk() couldn't see inside it yet since its recursion is gated on a
     * remote id that didn't exist at diff time. Without this, files inside a
     * newly-created folder would sit unsynced until next cycle. No remote
     * comparison needed: nothing can exist remotely under a folder that
     * didn't exist a moment ago.
     */
    public void discoverNewLocalFolderContents(
            CloudStorageProvider provider, Path localFolder, String remoteFolderId, List<TransferTask> toUpload
    ) throws IOException, InterruptedException {
        if (!Files.isDirectory(localFolder)) return;

        List<Path> children;
        try (var stream = Files.list(localFolder)) {
            children = stream.toList();
        }

        for (Path child : children) {
            String name = child.getFileName().toString();
            if (IGNORED_FILES.contains(name)) continue;

            if (Files.isDirectory(child)) {
                GameSyncLogger.debug("Creating remote subfolder: {}", child);
                String newSubfolderId = provider.createFolder(remoteFolderId, name, Files.getLastModifiedTime(child).toInstant());
                discoverNewLocalFolderContents(provider, child, newSubfolderId, toUpload);
            } else {
                long ageMillis = System.currentTimeMillis() - Files.getLastModifiedTime(child).toMillis();
                if (ageMillis < SKIP_IF_MODIFIED_WITHIN_MILLIS) {
                    continue; // mid-write guard, same as the main walk
                }
                toUpload.add(new TransferTask(child, null, remoteFolderId, name, null));
            }
        }
    }

    /**
     * True if the file should actually be uploaded. When the provider supplies
     * a remote content fingerprint, this verifies content actually changed
     * (catches Minecraft touching a file's mtime without rewriting its data —
     * common with region files on save). When the provider has no fingerprint
     * support (computeLocalFingerprint returns null/empty), this just trusts
     * the mtime comparison the caller already made.
     */
    private boolean contentLikelyChanged(Path localFile, String relKey, CloudItem serverItem, HashCache hashCache) throws IOException {
        String remoteFingerprint = serverItem.contentFingerprint();
        if (remoteFingerprint == null || remoteFingerprint.isEmpty()) {
            return true;
        }
        String localFingerprint = hashCache.getFingerprint(localFile, relKey);
        return !localFingerprint.equals(remoteFingerprint);
    }

    /**
     * Folders that exist on one side but not the other and need creating.
     */
    public sealed interface FolderTask {
        record CreateLocal(Path localPath) implements FolderTask {
        }

        record CreateRemote(Path localPath, String parentFolderId, String name) implements FolderTask {
        }
    }

    /**
     * A single queued file transfer. remoteId is null for brand-new uploads.
     */
    public record TransferTask(
            Path localPath,
            String remoteId,
            String parentFolderId,
            String name,
            Instant remoteModifiedTime
    ) {
    }

    public record Result(
            List<TransferTask> toUpload,
            List<TransferTask> toDownload,
            List<FolderTask> folderTasks,
            long totalBytes
    ) {
    }
}