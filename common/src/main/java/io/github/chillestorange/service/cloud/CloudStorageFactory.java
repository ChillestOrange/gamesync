package io.github.chillestorange.service.cloud;

import io.github.chillestorange.service.cloud.gdrive.GoogleDriveProvider;
import io.github.chillestorange.service.cloud.s3.S3Provider;

import java.net.http.HttpClient;
import java.nio.file.Path;

/**
 * Maps a provider choice + credentials to a concrete CloudStorageProvider.
 * This is the one place that needs a new branch when a second provider gets
 * implemented — nothing in service.sync ever needs to know it exists.
 * <p>
 * CloudProviderType and CloudCredentials are nested here rather than in their
 * own files — both exist purely as parameters to create(...) and have no
 * meaning independent of this factory.
 */
public final class CloudStorageFactory {

    private CloudStorageFactory() {}

    /**
     * Add a new provider by adding a value here, implementing CloudStorageProvider
     * (and CloudAuthenticator / extending OAuth2Authenticator if it's OAuth-based)
     * in its own subpackage, and wiring a branch into create() below.
     */
    public enum ProviderType {
        GOOGLE_DRIVE,
        S3
    }

    /**
     * Credential shapes vary by provider style — OAuth2 providers (Drive, Dropbox,
     * OneDrive) need a client id/secret/token store, while access-key based S3
     * needs an endpoint, bucket and key pair instead. Sealed so the compiler
     * catches a provider being handed the wrong credential shape.
     */
    public sealed interface Credentials {
        record OAuthCredentials(String clientId, String clientSecret, Path tokenStorePath) implements Credentials {}

        record S3Credentials(String endpoint, String region, String bucket, String accessKeyId,
                             String secretAccessKey) implements Credentials {}
    }

    public static CloudStorageProvider create(ProviderType type, Credentials credentials, HttpClient sharedHttpClient) {
        return switch (type) {
            case GOOGLE_DRIVE -> {
                if (!(credentials instanceof Credentials.OAuthCredentials(
                        String clientId, String clientSecret, Path tokenStorePath
                ))) {
                    throw new IllegalArgumentException("Google Drive requires OAuthCredentials");
                }
                GoogleDriveProvider.GoogleDriveAuthenticator auth = new GoogleDriveProvider.GoogleDriveAuthenticator(
                        clientId, clientSecret, tokenStorePath, sharedHttpClient);
                yield new GoogleDriveProvider(auth, sharedHttpClient);
            }
            case S3 -> {
                if (!(credentials instanceof Credentials.S3Credentials(
                        String endpoint, String region, String bucket, String accessKeyId, String secretAccessKey
                ))) {
                    throw new IllegalArgumentException("S3 requires S3Credentials");
                }
                yield new S3Provider(endpoint, region, bucket, accessKeyId, secretAccessKey, sharedHttpClient);
            }
        };
    }
}