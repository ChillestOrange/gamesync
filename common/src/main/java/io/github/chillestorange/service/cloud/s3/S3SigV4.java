package io.github.chillestorange.service.cloud.s3;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * AWS SigV4 header signing for S3.
 * https://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-header-based-auth.html
 * <p>
 * Host is signed but not returned, HttpClient sets it from the URI.
 */
final class S3SigV4 {

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final String SIGNED_HEADERS = "host;x-amz-content-sha256;x-amz-date";

    private final String accessKeyId;
    private final String secretAccessKey;
    private final String region;

    S3SigV4(String accessKeyId, String secretAccessKey, String region) {
        this.accessKeyId = accessKeyId;
        this.secretAccessKey = secretAccessKey;
        this.region = region;
    }

    /**
     * Returns the headers to add. canonicalUri/canonicalQuery must be the
     * already encoded strings the uri was built from.
     */
    Map<String, String> sign(String method, URI uri, String canonicalUri, String canonicalQuery,
                             String payloadSha256, Instant now) {
        String amzDate = AMZ_DATE.format(now);
        String dateStamp = amzDate.substring(0, 8);
        String scope = dateStamp + "/" + region + "/s3/aws4_request";

        String canonicalRequest = method + "\n"
                + canonicalUri + "\n"
                + canonicalQuery + "\n"
                + "host:" + hostHeader(uri) + "\n"
                + "x-amz-content-sha256:" + payloadSha256 + "\n"
                + "x-amz-date:" + amzDate + "\n"
                + "\n"
                + SIGNED_HEADERS + "\n"
                + payloadSha256;

        String stringToSign = "AWS4-HMAC-SHA256\n"
                + amzDate + "\n"
                + scope + "\n"
                + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

        byte[] signingKey = hmac(("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8), dateStamp);
        signingKey = hmac(signingKey, region);
        signingKey = hmac(signingKey, "s3");
        signingKey = hmac(signingKey, "aws4_request");
        String signature = hex(hmac(signingKey, stringToSign));

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-amz-date", amzDate);
        headers.put("x-amz-content-sha256", payloadSha256);
        headers.put("Authorization", "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/" + scope
                + ", SignedHeaders=" + SIGNED_HEADERS + ", Signature=" + signature);
        return headers;
    }

    // Keeps "/", S3 encodes keys only once.
    static String encodePath(String path) {
        return encode(path, false);
    }


    static String canonicalQuery(Map<String, String> params) {
        StringJoiner joiner = new StringJoiner("&");
        params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> joiner.add(encode(e.getKey(), true) + "=" + encode(e.getValue(), true)));
        return joiner.toString();
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String hostHeader(URI uri) {
        int port = uri.getPort();
        boolean defaultPort = port == -1
                || (port == 443 && "https".equals(uri.getScheme()))
                || (port == 80 && "http".equals(uri.getScheme()));
        return defaultPort ? uri.getHost() : uri.getHost() + ":" + port;
    }

    // RFC 3986, uppercase hex
    private static String encode(String value, boolean encodeSlash) {
        StringBuilder sb = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~' || (c == '/' && !encodeSlash)) {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return sb.toString();
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
