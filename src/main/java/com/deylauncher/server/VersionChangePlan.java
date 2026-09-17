package com.deylauncher.server;

import com.deylauncher.version.VersionManifest;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Pure decision logic for changing a server's Minecraft version: is the new version newer or older
 * than the one the world and mods in this folder were made by, and what has to be set aside so an
 * older server can start at all?
 *
 * <p>This is the part that has to be right for the reported bug to stop happening. A world is written
 * in one data version and an older server cannot read a newer one, and mods built for a newer version
 * refuse to load in an older one -- so a downgrade is not just "download another jar": the world has to
 * be backed up (never deleted) and the leftover mods have to be moved out of the way. Nothing here
 * touches the disk, so every rule below is unit-testable.
 */
public final class VersionChangePlan {

    /** Which way a version change goes, from the point of view of the world already on disk. */
    public enum Kind {
        /** Same version -- nothing to plan. */
        SAME,
        /** Newer server, which can read and upgrade an older world. */
        UPGRADE,
        /** Older server, which cannot read a newer world -- the case that used to crash. */
        DOWNGRADE,
        /** Ids we cannot order (e.g. snapshots) -- treat as risky: offer the same backup a downgrade gets. */
        UNKNOWN;

        /** True when this change needs the "back up the world first" treatment. */
        public boolean needsWorldBackupOffer() {
            return this == DOWNGRADE || this == UNKNOWN;
        }
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * Orders two version ids from the point of view of "can an older server read what's on disk":
     *
     * <ol>
     *   <li>the live manifest's own order when both ids are in it -- it is published newest-first, so
     *       it settles even ids whose numbering isn't comparable;</li>
     *   <li>otherwise a plain numeric tuple comparison, which covers Mojang's own schemes
     *       ("1.21.11" &gt; "1.21.5", "26.2" &gt; "1.21.1");</li>
     *   <li>otherwise {@link Kind#UNKNOWN}, which is treated as risky rather than guessed.</li>
     * </ol>
     */
    public static Kind classify(String current, String chosen, List<VersionManifest.VersionEntry> manifest) {
        if (current == null || chosen == null || current.isBlank() || chosen.isBlank()) return Kind.UNKNOWN;
        if (current.equalsIgnoreCase(chosen)) return Kind.SAME;
        Integer byManifest = compareByManifestOrder(current, chosen, manifest);
        if (byManifest != null) {
            return byManifest == 0 ? Kind.SAME : (byManifest < 0 ? Kind.DOWNGRADE : Kind.UPGRADE);
        }
        Integer byNumber = compareNumeric(current, chosen);
        if (byNumber != null) {
            return byNumber == 0 ? Kind.SAME : (byNumber < 0 ? Kind.DOWNGRADE : Kind.UPGRADE);
        }
        return Kind.UNKNOWN;
    }

    /** Negative when {@code chosen} is older than {@code current}, positive when newer, null when absent. */
    private static Integer compareByManifestOrder(String current, String chosen,
                                                 List<VersionManifest.VersionEntry> manifest) {
        if (manifest == null || manifest.isEmpty()) return null;
        int currentIndex = indexOf(manifest, current);
        int chosenIndex = indexOf(manifest, chosen);
        if (currentIndex < 0 || chosenIndex < 0) return null;
        // Newest first, so a SMALLER index means a NEWER version -- the sign has to be flipped here.
        return Integer.compare(currentIndex, chosenIndex) * -1;
    }

    private static int indexOf(List<VersionManifest.VersionEntry> manifest, String id) {
        for (int i = 0; i < manifest.size(); i++) {
            VersionManifest.VersionEntry entry = manifest.get(i);
            if (entry != null && id.equalsIgnoreCase(entry.id())) return i;
        }
        return -1;
    }

    /** Negative/positive as above, 0 when equal, null when either id isn't a plain dotted number. */
    private static Integer compareNumeric(String current, String chosen) {
        int[] a = numericParts(current);
        int[] b = numericParts(chosen);
        if (a == null || b == null) return null;
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int left = i < a.length ? a[i] : 0;
            int right = i < b.length ? b[i] : 0;
            if (left != right) return Integer.compare(left, right);
        }
        return 0;
    }

    private static int[] numericParts(String id) {
        String[] parts = id.trim().split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 9) return null;
            for (int c = 0; c < parts[i].length(); c++) {
                if (!Character.isDigit(parts[i].charAt(c))) return null;
            }
            out[i] = Integer.parseInt(parts[i]);
        }
        return out.length == 0 ? null : out;
    }

    /**
     * The folders a world lives in, for the given {@code level-name} from server.properties: the
     * level itself plus the Nether/End siblings a Bukkit-family server (Purpur etc.) creates next to
     * it. Defaults to "world" when the property is missing, which is what every server type uses.
     */
    public static List<String> worldDirNames(String levelName) {
        String name = (levelName == null || levelName.isBlank()) ? "world" : levelName.trim();
        return List.of(name, name + "_nether", name + "_the_end");
    }

    /**
     * The name a folder is moved to when it has to be set aside, e.g.
     * {@code world-1.21.11-20260916-190432} -- the original name, the version that wrote it, and when.
     * Never deleted, only renamed, so nothing a user made is ever destroyed by a version change.
     */
    public static String backupDirName(String original, String version, LocalDateTime when) {
        String base = (original == null || original.isBlank()) ? "world" : original.trim();
        String tag = (version == null || version.isBlank()) ? "previous" : version.trim();
        return base + "-" + tag + "-" + STAMP.format(when != null ? when : LocalDateTime.now());
    }
}