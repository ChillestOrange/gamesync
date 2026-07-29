package io.github.chillestorange.service.cloud;

import java.time.Instant;
import java.util.List;

/**
 * Provider-agnostic stand-in for a remote file or folder. isFolder and
 * contentFingerprint are deliberately plain fields rather than derived from
 * something Drive-specific like a mimeType string, so other providers can
 * populate them however they determine those facts (Dropbox/OneDrive use a
 * different discriminator for folders; S3 has no real folders at all).
 * <p>
 * contentFingerprint's algorithm is provider-defined (Drive: md5Checksum,
 * Dropbox: its own content_hash, OneDrive: quickXorHash, etc.) — it's only
 * ever compared against a local fingerprint computed by that same provider's
 * CloudStorageProvider.computeLocalFingerprint, never across providers.
 */
public record CloudItem(
        String id,
        String name,
        boolean isFolder,
        Instant modifiedTime,
        String contentFingerprint,
        long size,
        List<String> parents
) {}