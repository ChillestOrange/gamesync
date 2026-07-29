package io.github.chillestorange.util;

/**
 * Generic formatting utilities.
 */
public final class FormatUtils {

    private static final long BYTES_PER_UNIT = 1024L;
    private static final String[] UNITS = {"KB", "MB", "GB", "TB"};

    private FormatUtils() {
    }

    /**
     * Formats a byte count as a human-readable string, scaling to the largest
     * unit (B, KB, MB, GB, TB) that keeps the value below 1024.
     *
     * @param bytes the byte count to format
     * @return a formatted string such as {@code "1.25 GB"}
     * @throws IllegalArgumentException if bytes is negative
     */
    public static String formatBytes(long bytes) {

        if (bytes < 0) {
            throw new IllegalArgumentException("bytes cannot be negative: " + bytes);
        }

        if (bytes < BYTES_PER_UNIT) {
            return bytes + " B";
        }

        double value = bytes;
        int unitIndex = -1;
        do {
            value /= BYTES_PER_UNIT;
            unitIndex++;
        } while (value >= BYTES_PER_UNIT && unitIndex < UNITS.length - 1);

        return String.format(java.util.Locale.ROOT, "%.2f %s", value, UNITS[unitIndex]);
    }
}
