package io.github.chillestorange.service.cloud.s3;

import io.github.chillestorange.logging.GameSyncLogger;
import io.github.chillestorange.service.cloud.CloudItem;
import io.github.chillestorange.service.cloud.CloudStorageProvider;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * S3 implementation of CloudStorageProvider (AWS, Cloudflare R2, Backblaze B2,
 * MinIO). No AWS SDK, just HttpClient and S3SigV4.
 * <p>
 * S3 has no folders, so a folder id is a key prefix ending in "/" ("" for the
 * bucket root) and a file id is the full key. Uses path-style URLs.
 * <p>
 * Remote modified time is LastModified, which is the upload time since S3
 * can't set it. The ETag fingerprint prevents redundant transfers because of it.
 */
public final class S3Provider implements CloudStorageProvider {

    private static final String EMPTY_PAYLOAD_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final HttpClient httpClient;
    private final S3SigV4 signer;
    private final String endpoint;
    private final String bucket;

    public S3Provider(String endpoint, String region, String bucket, String accessKeyId, String secretAccessKey,
                      HttpClient httpClient) {
        if (endpoint.isBlank() || bucket.isBlank() || accessKeyId.isBlank() || secretAccessKey.isBlank()) {
            throw new IllegalArgumentException("S3 requires an endpoint, bucket, access key id and secret access key");
        }
        String trimmed = endpoint.trim();
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        this.endpoint = trimmed;
        this.bucket = bucket.trim();
        this.signer = new S3SigV4(accessKeyId.trim(), secretAccessKey.trim(), region.isBlank() ? "auto" : region.trim());
        this.httpClient = httpClient;
    }

    /**
     * "/gamesync" -> "gamesync/", "" stays "" (bucket root).
     */
    public static String normalizePrefix(String prefix) {
        String p = prefix.trim();
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p.isEmpty() ? "" : p + "/";
    }

    @Override
    public List<CloudItem> listChildren(String folderId) throws IOException, InterruptedException {
        List<CloudItem> result = list(folderId, "");
        if (GameSyncLogger.isDebugEnabled()) GameSyncLogger.debug("Fetched folder {} ({} children)", folderId, result.size());
        return result;
    }

    // The prefix also matches "world2/" when looking for "world", so filter on exact name.
    @Override
    public Optional<CloudItem> findByNameInFolder(String filename, String folderId) throws IOException, InterruptedException {
        return list(folderId, filename).stream()
                .filter(item -> item.name().equals(filename))
                .findFirst();
    }

    // Same temp file + atomic move as the Drive provider.
    @Override
    public void downloadFile(String fileId, Path destination) throws IOException, InterruptedException {
        Path parent = destination.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);

        Path tempFile = destination.resolveSibling(destination.getFileName() + ".gamesync-tmp-" + System.nanoTime());

        try {
            HttpRequest request = signedRequest("GET", fileId, Map.of(), EMPTY_PAYLOAD_SHA256).GET().build();
            HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(tempFile));
            if (response.statusCode() != 200) {
                throw new IOException("S3 download failed for " + fileId + ": HTTP " + response.statusCode()
                        + " " + Files.readString(tempFile));
            }
            try {
                Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    // PUT creates or overwrites, so existingFileId isn't needed.
    @Override
    public String uploadOrReplace(Path localFile, String existingFileId, String folderId, String filename, Instant modifiedTime)
            throws IOException, InterruptedException {

        if (!Files.exists(localFile)) {
            throw new IOException("Local file not found: " + localFile);
        }

        byte[] fileBytes = Files.readAllBytes(localFile);
        String key = folderId + filename;

        HttpRequest request = signedRequest("PUT", key, Map.of(), S3SigV4.hex(digest("SHA-256", fileBytes)))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(fileBytes))
                .build();
        send(request);
        return key;
    }

    @Override
    public String createFolder(String parentFolderId, String name, Instant modifiedTime) {
        return parentFolderId + name + "/";
    }

    // MD5, same as the ETag for single part uploads.
    @Override
    public String computeLocalFingerprint(Path localFile) throws IOException {
        return S3SigV4.hex(digest("MD5", Files.readAllBytes(localFile)));
    }

    /**
     * ListObjectsV2 with "/" as delimiter, so only direct children come back.
     * CommonPrefixes are folders. Max 1000 keys per page.
     */
    private List<CloudItem> list(String folderId, String namePrefix) throws IOException, InterruptedException {
        List<CloudItem> result = new ArrayList<>();
        String continuationToken = null;

        do {
            Map<String, String> query = new TreeMap<>();
            query.put("list-type", "2");
            query.put("prefix", folderId + namePrefix);
            query.put("delimiter", "/");
            if (continuationToken != null) query.put("continuation-token", continuationToken);

            HttpRequest request = signedRequest("GET", "", query, EMPTY_PAYLOAD_SHA256).GET().build();
            Element root = parseXml(send(request));

            for (Element contents : childElements(root, "Contents")) {
                String key = childText(contents, "Key");
                // Folder marker objects
                if (key.equals(folderId) || key.endsWith("/")) continue;

                String etag = childText(contents, "ETag").replace("\"", "");
                result.add(new CloudItem(
                        key,
                        key.substring(folderId.length()),
                        false,
                        Instant.parse(childText(contents, "LastModified")),
                        etag,
                        Long.parseLong(childText(contents, "Size")),
                        List.of(folderId)
                ));
            }

            for (Element commonPrefix : childElements(root, "CommonPrefixes")) {
                String prefix = childText(commonPrefix, "Prefix");
                String name = prefix.substring(folderId.length(), prefix.length() - 1);
                result.add(new CloudItem(prefix, name, true, Instant.EPOCH, "", 0L, List.of(folderId)));
            }

            continuationToken = "true".equals(childText(root, "IsTruncated"))
                    ? childText(root, "NextContinuationToken")
                    : null;
        } while (continuationToken != null);

        return result;
    }

    private HttpRequest.Builder signedRequest(String method, String key, Map<String, String> query, String payloadSha256) {
        String canonicalUri = "/" + S3SigV4.encodePath(bucket) + (key.isEmpty() ? "" : "/" + S3SigV4.encodePath(key));
        String canonicalQuery = S3SigV4.canonicalQuery(query);
        URI uri = URI.create(endpoint + canonicalUri + (canonicalQuery.isEmpty() ? "" : "?" + canonicalQuery));

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri);
        signer.sign(method, uri, canonicalUri, canonicalQuery, payloadSha256, Instant.now())
                .forEach(builder::header);
        return builder;
    }

    private byte[] send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("S3 API error: HTTP " + response.statusCode() + " "
                    + new String(response.body(), StandardCharsets.UTF_8));
        }
        return response.body();
    }

    private static byte[] digest(String algorithm, byte[] data) throws IOException {
        try {
            return MessageDigest.getInstance(algorithm).digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(algorithm + " not available", e);
        }
    }

    private static Element parseXml(byte[] body) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // No DTDs (XXE)
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
            return doc.getDocumentElement();
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Could not parse S3 response", e);
        }
    }

    // getElementsByTagName would also match nested elements.
    private static List<Element> childElements(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && name.equals(element.getTagName())) {
                result.add(element);
            }
        }
        return result;
    }

    private static String childText(Element parent, String name) {
        List<Element> matches = childElements(parent, name);
        return matches.isEmpty() ? "" : matches.getFirst().getTextContent();
    }
}
