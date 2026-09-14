package com.deylauncher.modpack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

/**
 * The modpack layouts DeyLauncher can read, detected purely from what's inside the file/folder the
 * user added -- never from the file extension alone (a Modrinth pack and a CurseForge pack are both
 * ".zip", and people rename things).
 *
 * Detection is deliberately cheap (a table-of-contents probe of the zip), so it can run on a
 * dropped file before any parsing or downloading starts.
 */
public enum ModpackFormat {

    /** Modrinth's own pack format: a zip with {@code modrinth.index.json} at its root. */
    MODRINTH_MRPACK("Modrinth .mrpack", true),
    /** A Modrinth-style pack shipped as a plain .zip (same index, different name). */
    MODRINTH_ZIP("Modrinth pack (.zip)", true),
    /** CurseForge export: {@code manifest.json} with minecraft.version + modLoaders[] + overrides/. */
    CURSEFORGE_ZIP("CurseForge pack", true),
    /** A MultiMC / Prism Launcher exported instance: {@code mmc-pack.json} (+ minecraft/ payload). */
    MULTIMC_PRISM_ZIP("MultiMC / Prism instance", true),
    /** An ATLauncher instance/pack export: {@code instance.json} (+ mods/ ...). */
    ATLAUNCHER_ZIP("ATLauncher pack", true),
    /** An already-extracted instance folder (e.g. a MultiMC instance dir handed over as-is). */
    FOLDER("Pack folder", false),
    /** Not a pack -- or a zip with nothing we recognise inside it. */
    UNKNOWN("Unrecognised file", false);

    private final String displayName;
    private final boolean archive;

    ModpackFormat(String displayName, boolean archive) {
        this.displayName = displayName;
        this.archive = archive;
    }

    /** Human-readable name shown in the install preview. */
    public String displayName() {
        return displayName;
    }

    /** True when the payload lives inside a zip (as opposed to a folder on disk). */
    public boolean archive() {
        return archive;
    }

    /** Sniffs a dropped file/folder and reports which layout it is (UNKNOWN when nothing matches). */
    public static ModpackFormat detect(Path path) {
        if (path == null || !Files.exists(path)) return UNKNOWN;
        if (Files.isDirectory(path)) return FOLDER;
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".mrpack")) return MODRINTH_MRPACK;
        if (!name.endsWith(".zip")) return UNKNOWN;
        if (hasEntry(path, "modrinth.index.json")) return MODRINTH_ZIP;
        if (hasEntry(path, "manifest.json")) return CURSEFORGE_ZIP;
        if (hasEntry(path, "mmc-pack.json") || hasEntry(path, "instance.cfg")) return MULTIMC_PRISM_ZIP;
        if (hasEntry(path, "instance.json")) return ATLAUNCHER_ZIP;
        return UNKNOWN;
    }

    /** True when we can at least attempt to install this (an installable zip or a folder). */
    public static boolean installable(Path path) {
        return detect(path) != UNKNOWN;
    }

    private static boolean hasEntry(Path zip, String entryName) {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            return zf.getEntry(entryName) != null;
        } catch (IOException e) {
            // Unreadable / not really a zip -- detection just says "unknown" rather than throwing,
            // so a bad drop can be reported to the user instead of blowing up the UI.
            return false;
        }
    }
}