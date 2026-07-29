package io.github.chillestorange.service.cloud.gdrive;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chillestorange.logging.GameSyncLogger;
import io.github.chillestorange.service.cloud.CloudAuthenticator;
import io.github.chillestorange.service.cloud.CloudItem;
import io.github.chillestorange.service.cloud.CloudStorageProvider;
import io.github.chillestorange.service.cloud.OAuth2Authenticator;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Google Drive v3 REST implementation of CloudStorageProvider. No Google SDK
 * dependency — just java.net.http.HttpClient and Gson.
 * <p>
 * GoogleDriveAuthenticator is nested here rather than in its own file — it's
 * three endpoint constants and a super() call, so splitting it out had no real
 * benefit. CloudStorageFactory is the only place that constructs it by name.
 */
public final class GoogleDriveProvider implements CloudStorageProvider {

    private static final String FILES_ENDPOINT = "https://www.googleapis.com/drive/v3/files";
    private static final String UPLOAD_ENDPOINT = "https://www.googleapis.com/upload/drive/v3/files";
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    private final CloudAuthenticator auth;
    private final HttpClient httpClient;
    public GoogleDriveProvider(CloudAuthenticator auth, HttpClient httpClient) {
        this.auth = auth;
        this.httpClient = httpClient;
    }

    /**
     * A fixed boundary reused across every request risked, in principle, a
     * binary file's content happening to contain that exact byte sequence and
     * confusing Drive's multipart parser. Vanishingly unlikely in practice for
     * Minecraft save data, but a random boundary per request costs nothing and
     * removes the risk entirely.
     */
    private static String newBoundary() {
        return "GameSyncBoundary" + UUID.randomUUID();
    }

    private static CloudItem toCloudItem(JsonObject obj) {
        List<String> parents = new ArrayList<>();
        if (obj.has("parents")) {
            for (var p : obj.getAsJsonArray("parents")) parents.add(p.getAsString());
        }
        Instant modified = obj.has("modifiedTime") ? Instant.parse(obj.get("modifiedTime").getAsString()) : Instant.EPOCH;
        boolean isFolder = obj.has("mimeType") && FOLDER_MIME.equals(obj.get("mimeType").getAsString());
        long size = obj.has("size") ? Long.parseLong(obj.get("size").getAsString()) : 0L;
        return new CloudItem(
                obj.get("id").getAsString(),
                obj.get("name").getAsString(),
                isFolder,
                modified,
                obj.has("md5Checksum") ? obj.get("md5Checksum").getAsString() : "",
                size,
                parents
        );
    }

    private static String escapeQueryValue(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Override
    public List<CloudItem> listChildren(String folderId) throws IOException, InterruptedException {
        String query = "'" + folderId + "' in parents and trashed=false";
        String fields = "nextPageToken,files(id,name,mimeType,modifiedTime,md5Checksum,size,parents)";
        List<CloudItem> result = new ArrayList<>();
        String pageToken = null;

        do {
            String url = FILES_ENDPOINT
                    + "?q=" + enc(query)
                    + "&fields=" + enc(fields)
                    + "&pageSize=1000"
                    + "&supportsAllDrives=true&includeItemsFromAllDrives=true"
                    + (pageToken != null ? "&pageToken=" + enc(pageToken) : "");

            JsonObject body = getJson(url);
            if (body.has("files")) {
                for (var el : body.getAsJsonArray("files")) {
                    result.add(toCloudItem(el.getAsJsonObject()));
                }
            }
            // Drive caps each response at 1000 items regardless of pageSize — a folder
            // with more (plausible for region/ in a long-played, widely-explored world)
            // needs this loop or everything past the first page silently never syncs.
            pageToken = body.has("nextPageToken") ? body.get("nextPageToken").getAsString() : null;
        } while (pageToken != null);

        if (GameSyncLogger.isDebugEnabled()) GameSyncLogger.debug("Fetched folder {} ({} children)", folderId, result.size());

        return result;
    }

    @Override
    public Optional<CloudItem> findByNameInFolder(String filename, String folderId) throws IOException, InterruptedException {
        String query = "name='" + escapeQueryValue(filename) + "' and '" + folderId + "' in parents and trashed=false";
        String fields = "files(id,name,mimeType,modifiedTime,md5Checksum,size,parents)";
        String url = FILES_ENDPOINT + "?q=" + enc(query) + "&fields=" + enc(fields)
                + "&supportsAllDrives=true&includeItemsFromAllDrives=true";

        JsonObject body = getJson(url);
        if (body.has("files") && body.getAsJsonArray("files").size() > 0) {
            return Optional.of(toCloudItem(body.getAsJsonArray("files").get(0).getAsJsonObject()));
        }
        return Optional.empty();
    }

    /**
     * Downloads to a temp file in the same directory and atomically moves it
     * into place only on success. Writing straight to destination would leave
     * a truncated, corrupt file there if the download was interrupted partway
     * (network drop, JVM killed mid-sync) — bad news for something like a
     * .mca region file that Minecraft will try to read on next load.
     */
    @Override
    public void downloadFile(String fileId, Path destination) throws IOException, InterruptedException {
        Path parent = destination.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);

        Path tempFile = destination.resolveSibling(destination.getFileName() + ".gamesync-tmp-" + System.nanoTime());
        String url = FILES_ENDPOINT + "/" + fileId + "?alt=media&supportsAllDrives=true";

        try {
            HttpRequest request = authedRequest(url).GET().build();
            HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(tempFile));
            if (response.statusCode() != 200) {
                throw new IOException("Drive download failed for " + fileId + ": HTTP " + response.statusCode());
            }
            try {
                Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Some filesystems (certain network/FUSE mounts) don't support atomic
                // moves. A plain move is still strictly better than writing straight to
                // destination — the corruption window is now just the move, not the download.
                Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * Files only — folders never reach here. SyncDiffEngine only ever emits
     * TransferTasks for files; folder creation is handled separately via
     * createFolder(), called directly by GameSyncService's FolderTask handling.
     * <p>
     * Note this no longer does its own "does this already exist?" lookup —
     * existingFileId already carries that answer from SyncDiffEngine, which
     * resolved it once while building the changeset. Re-querying here would
     * just be a second Drive API call per file for an answer already known.
     */
    @Override
    public String uploadOrReplace(Path localFile, String existingFileId, String folderId, String filename, Instant modifiedTime)
            throws IOException, InterruptedException {

        if (!Files.exists(localFile)) {
            throw new IOException("Local file not found: " + localFile);
        }

        byte[] fileBytes = Files.readAllBytes(localFile);

        if (existingFileId != null) {
            return replaceFileContent(existingFileId, fileBytes, modifiedTime);
        }
        return createFileWithContent(folderId, filename, fileBytes, modifiedTime);
    }

    @Override
    public String createFolder(String parentFolderId, String name, Instant modifiedTime)
            throws IOException, InterruptedException {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("name", name);
        metadata.addProperty("mimeType", FOLDER_MIME);
        JsonArray parents = new JsonArray();
        parents.add(parentFolderId);
        metadata.add("parents", parents);
        metadata.addProperty("modifiedTime", DateTimeFormatter.ISO_INSTANT.format(modifiedTime));

        HttpRequest request = authedRequest(FILES_ENDPOINT + "?supportsAllDrives=true")
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(metadata.toString(), StandardCharsets.UTF_8))
                .build();

        JsonObject response = sendForJson(request);
        return response.get("id").getAsString();
    }

    @Override
    public String computeLocalFingerprint(Path localFile) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(Files.readAllBytes(localFile));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("MD5 not available", e);
        }
    }

    private String createFileWithContent(String folderId, String filename, byte[] content, Instant modifiedTime)
            throws IOException, InterruptedException {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("name", filename);
        JsonArray parents = new JsonArray();
        parents.add(folderId);
        metadata.add("parents", parents);
        metadata.addProperty("modifiedTime", DateTimeFormatter.ISO_INSTANT.format(modifiedTime));

        String boundary = newBoundary();
        byte[] body = buildMultipartBody(metadata, content, boundary);

        HttpRequest request = authedRequest(UPLOAD_ENDPOINT + "?uploadType=multipart&supportsAllDrives=true")
                .header("Content-Type", "multipart/related; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        JsonObject response = sendForJson(request);
        return response.get("id").getAsString();
    }

    private String replaceFileContent(String fileId, byte[] content, Instant modifiedTime)
            throws IOException, InterruptedException {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("modifiedTime", DateTimeFormatter.ISO_INSTANT.format(modifiedTime));

        String boundary = newBoundary();
        byte[] body = buildMultipartBody(metadata, content, boundary);

        HttpRequest request = authedRequest(UPLOAD_ENDPOINT + "/" + fileId + "?uploadType=multipart&supportsAllDrives=true")
                .header("Content-Type", "multipart/related; boundary=" + boundary)
                .method("PATCH", HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        JsonObject response = sendForJson(request);
        return response.get("id").getAsString();
    }

    private byte[] buildMultipartBody(JsonObject metadata, byte[] content, String boundary) {
        String head = "--" + boundary + "\r\n"
                + "Content-Type: application/json; charset=UTF-8\r\n\r\n"
                + metadata.toString() + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        String tail = "\r\n--" + boundary + "--";

        byte[] headBytes = head.getBytes(StandardCharsets.UTF_8);
        byte[] tailBytes = tail.getBytes(StandardCharsets.UTF_8);

        byte[] result = new byte[headBytes.length + content.length + tailBytes.length];
        System.arraycopy(headBytes, 0, result, 0, headBytes.length);
        System.arraycopy(content, 0, result, headBytes.length, content.length);
        System.arraycopy(tailBytes, 0, result, headBytes.length + content.length, tailBytes.length);
        return result;
    }

    private HttpRequest.Builder authedRequest(String url) throws IOException, InterruptedException {
        return HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + auth.getValidAccessToken());
    }

    private JsonObject getJson(String url) throws IOException, InterruptedException {
        HttpRequest request = authedRequest(url).GET().build();
        return sendForJson(request);
    }

    private JsonObject sendForJson(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Drive API error: HTTP " + response.statusCode() + " " + response.body());
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    /**
     * Supplies Google's specific OAuth2 endpoints/scope to the shared
     * OAuth2Authenticator flow. See that class for the actual loopback-server,
     * token storage, and refresh logic — nothing Drive-specific lives here.
     * <p>
     * Setup: create an OAuth Client ID of type "Desktop app" in Google Cloud
     * Console (APIs & Services > Credentials) with the Drive API enabled.
     * Loopback redirect URIs (http://127.0.0.1:&lt;any port&gt;) are allowed for
     * Desktop app clients without pre-registering an exact port.
     * <p>
     * Caveat: while the consent screen is in "Testing" status, Google expires
     * refresh tokens after 7 days regardless of activity. Toggle to "In
     * production" (no verification required for personal/limited-use apps) to
     * avoid weekly re-auth.
     */
    public static final class GoogleDriveAuthenticator extends OAuth2Authenticator {

        private static final String AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
        private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
        private static final String SCOPE = "https://www.googleapis.com/auth/drive";

        public GoogleDriveAuthenticator(String clientId, String clientSecret, Path tokenStorePath, HttpClient httpClient) {
            super(AUTH_ENDPOINT, TOKEN_ENDPOINT, SCOPE, clientId, clientSecret, tokenStorePath, httpClient);
        }
    }
}