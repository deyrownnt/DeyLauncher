package com.deylauncher.servers;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Splitting a world archive into uploadable parts, and putting it back together -- pure logic, no
 * network and no filesystem, so the risky half of world sync is unit-testable.
 *
 * <p>Why parts at all: GitHub's Contents API stores one file per request, and the practical ceiling for
 * a single base64-encoded commit is far below what a played-in Minecraft world weighs. Fixed-size parts
 * turn "the world is 400 MB" from an impossible single upload into ten ordinary ones, each independently
 * verifiable ({@link WorldManifest.Part#sha256}), resumable, and re-fetchable on its own.
 *
 * <p>The trade-off is stated plainly rather than hidden: parts mean more API calls and more repo
 * objects, so the part size here (40 MB) is the compromise -- big enough that an ordinary survival world
 * is two or three parts, small enough that one failed part is not a long wait.
 */
public final class WorldChunker {

    /** Size of one uploaded part. 40 MB keeps an average world to a handful of parts. */
    public static final int DEFAULT_PART_BYTES = 40 * 1024 * 1024;

    /**
     * Beyond this, a full-world push is refused and downgraded to settings-only. It is a deliberate
     * policy limit, not a technical cap: the repo is shared by every DeyLauncher user and the token's
     * rate limit is shared with them, so an unbounded world would degrade the feature for everyone.
     */
    public static final long MAX_TOTAL_BYTES = 900L * 1024L * 1024L;

    private WorldChunker() {
    }

    /** The repo file name for one part, zero-padded so a directory listing stays in order. */
    public static String partName(int index) {
        return String.format(java.util.Locale.ROOT, "part-%03d.bin", index);
    }

    /** Splits {@code payload} into parts of at most {@code partBytes}, hashing each one. */
    public static List<WorldManifest.Part> split(byte[] payload, int partBytes) {
        int size = partBytes <= 0 ? DEFAULT_PART_BYTES : partBytes;
        List<WorldManifest.Part> parts = new ArrayList<>();
        if (payload == null || payload.length == 0) return parts;
        int index = 0;
        for (int offset = 0; offset < payload.length; offset += size) {
            int length = Math.min(size, payload.length - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(payload, offset, chunk, 0, length);
            parts.add(new WorldManifest.Part(index, partName(index), length, sha256(chunk)));
            index++;
        }
        return parts;
    }

    /**
     * Rebuilds the archive from its parts. Throws when the result does not match
     * {@code expectedTotalBytes} or {@code expectedSha256}, because a silently truncated world would
     * overwrite a good one on somebody's PC.
     */
    public static byte[] join(List<byte[]> parts, long expectedTotalBytes, String expectedSha256) {
        long total = 0;
        for (byte[] p : parts) total += p == null ? 0 : p.length;
        if (expectedTotalBytes > 0 && total != expectedTotalBytes) {
            throw new IllegalStateException("The downloaded world is incomplete (" + total + " of "
                    + expectedTotalBytes + " bytes) -- some parts are missing or were truncated.");
        }
        byte[] out = new byte[(int) total];
        int offset = 0;
        for (byte[] p : parts) {
            if (p == null) continue;
            System.arraycopy(p, 0, out, offset, p.length);
            offset += p.length;
        }
        if (expectedSha256 != null && !expectedSha256.isBlank()
                && !expectedSha256.equalsIgnoreCase(sha256(out))) {
            throw new IllegalStateException("The downloaded world failed its checksum -- download it again "
                    + "before using it.");
        }
        return out;
    }

    /** Builds the manifest describing a payload that is about to be uploaded. */
    public static WorldManifest manifestFor(byte[] payload, boolean settingsOnly, String fileName,
                                            String pushedBy, String note, int partBytes) {
        WorldManifest m = new WorldManifest();
        m.settingsOnly = settingsOnly;
        m.fileName = fileName;
        m.totalBytes = payload == null ? 0 : payload.length;
        m.sha256 = payload == null ? null : sha256(payload);
        m.parts = split(payload, partBytes);
        m.partCount = m.parts.size();
        m.pushedBy = pushedBy;
        m.note = note;
        return m;
    }

    /** Lower-case hex SHA-256 of {@code data} (null in, null out). */
    public static String sha256(byte[] data) {
        if (data == null) return null;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return null; // every JRE ships SHA-256; a null hash simply disables the integrity check
        }
    }

    /** A short, human size such as {@code "12.4 MB"} for the sync UI. */
    public static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(java.util.Locale.ROOT, "%.1f MB", mb);
        return String.format(java.util.Locale.ROOT, "%.2f GB", mb / 1024.0);
    }

    /** The archive name for a snapshot: sortable, and free of characters a repo file name dislikes. */
    public static String archiveName(String label, long timestamp) {
        String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", java.util.Locale.ROOT)
                .format(new java.util.Date(timestamp));
        String safe = label == null ? "world" : label.replaceAll("[^A-Za-z0-9_-]", "_");
        return safe + "-" + stamp + ".zip";
    }
}
