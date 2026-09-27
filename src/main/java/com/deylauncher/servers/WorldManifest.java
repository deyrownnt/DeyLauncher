package com.deylauncher.servers;

import java.util.ArrayList;
import java.util.List;

/**
 * What a synced world looks like in the repo: {@code servers/&lt;id&gt;/world/manifest.json}.
 *
 * <p>A world is stored as a <b>chunked</b> archive rather than as one file, because GitHub's Contents
 * API refuses (or times out on) very large single files and a real Minecraft world routinely passes the
 * comfortable limit for one commit. Splitting the archive into fixed-size parts means a large world still
 * uploads, and each part carries its own SHA-256 so a download can be verified piece by piece and a
 * single corrupt part can be re-fetched instead of restarting the whole transfer.
 *
 * <p>{@link #settingsOnly} records an honest downgrade: if the full archive is beyond
 * {@link WorldChunker#MAX_TOTAL_BYTES}, the launcher pushes the parts of the server that are small and
 * valuable (world metadata, player data, properties, addons, configs) and says so, instead of failing
 * with a pile of bytes nobody can move.
 */
public class WorldManifest {

    /** Bumped only if the format stops being readable by older builds. */
    public static final int CURRENT_VERSION = 1;

    public int version = CURRENT_VERSION;

    /** True when the region/world files were left out because the full archive was too large. */
    public boolean settingsOnly;

    /** The archive's name on the host that pushed it, e.g. {@code world-2026-09-26T18-40.zip}. */
    public String fileName;

    /** Total size of the archive the parts came from, in bytes. */
    public long totalBytes;

    /** SHA-256 of the whole archive, so a download can prove it reassembled correctly. */
    public String sha256;

    public int partCount;

    public List<Part> parts = new ArrayList<>();

    public long createdAt = System.currentTimeMillis();
    /** Username of whoever pushed this snapshot, for "updated by" text. */
    public String pushedBy;
    /** Why this snapshot was settings-only (or null), shown verbatim in the UI. */
    public String note;

    /** The Minecraft version the owning server was set to when this was pushed. */
    public String minecraftVersion;
    /** The server software (Vanilla/Purpur/Fabric/Forge/NeoForge) the snapshot belongs to. */
    public String serverType;

    /** One archive part, in order. */
    public static class Part {
        public int index;
        public String name;
        public long size;
        public String sha256;

        public Part() {
        }

        public Part(int index, String name, long size, String sha256) {
            this.index = index;
            this.name = name;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    /** True when this manifest actually describes a downloadable snapshot. */
    public boolean isUsable() {
        return parts != null && !parts.isEmpty() && totalBytes > 0;
    }

    /** The parts in the order they must be concatenated; a null list is treated as empty. */
    public List<Part> orderedParts() {
        List<Part> ordered = new ArrayList<>(parts == null ? List.of() : parts);
        ordered.sort(java.util.Comparator.comparingInt(p -> p.index));
        return ordered;
    }

    /** A one-line description for the UI, e.g. {@code "settings + world · 128.4 MB in 4 parts"}. */
    public String describe() {
        return (settingsOnly ? "settings only · " : "full world · ")
                + WorldChunker.humanSize(totalBytes) + " in " + partCount + " part"
                + (partCount == 1 ? "" : "s");
    }
}
