package com.deylauncher.modpack;

import java.nio.file.Path;
import java.util.List;

/**
 * Everything the launcher needs to know about a modpack before (and after) installing it: the
 * Minecraft version + loader it was built for, the files to download, the files to unpack, and the
 * human-facing name/version shown in the install preview.
 *
 * Produced by {@link ModpackReader} (pure reading, no network) and consumed by
 * {@link ModpackInstaller}. The icon is filled in separately by {@link PackIcons} because, for a
 * Modrinth pack, it may need one network round-trip -- keeping {@code ModpackReader} offline-only.
 */
public record ModpackInfo(ModpackFormat format, String name, String version, String mcVersion,
                          String loaderName, String loaderVersion,
                          List<ModpackFile> downloads, List<ModpackFile> bundled,
                          int unresolvedCount, Path iconPath, Path source, String note) {

    /** The value this pack needs in the launcher's LOADER dropdown ("Vanilla"/"Fabric"/"Forge"/
     *  "NeoForge"), or null when it needs a loader DeyLauncher doesn't install yet (Quilt). */
    public String launcherLoader() {
        if (loaderName == null || loaderName.isBlank() || loaderName.equalsIgnoreCase("vanilla")) return "Vanilla";
        if (loaderName.equalsIgnoreCase("fabric")) return "Fabric";
        if (loaderName.equalsIgnoreCase("forge")) return "Forge";
        if (loaderName.equalsIgnoreCase("neoforge")) return "NeoForge";
        return null; // Quilt / anything else -- we refuse the install instead of guessing
    }

    public boolean supportedLoader() {
        return launcherLoader() != null;
    }

    public boolean knowsMinecraftVersion() {
        return mcVersion != null && !mcVersion.isBlank();
    }

    /** Why this pack can't be installed by us at all, or null when it's fine (shown in the preview).
     *  A pack with no Minecraft version / no loader is NOT blocked: the installer then targets
     *  whatever version+loader is currently selected in the launcher (see the modpack window). */
    public String blockingProblem() {
        if (!supportedLoader()) {
            return "This pack needs " + loaderName + ", which DeyLauncher doesn't install yet -- "
                    + "only Vanilla, Fabric, Forge and NeoForge are supported.";
        }
        return null;
    }

    /** True when the pack itself declared a loader, rather than us defaulting to Vanilla. */
    public boolean loaderDeclared() {
        return loaderName != null && !loaderName.isBlank() && !loaderName.equalsIgnoreCase("vanilla");
    }

    /**
     * True when this pack's files have to be RESOLVED from the internet while installing, rather than
     * already carrying their own download links: CurseForge exports list everything as project/file id
     * pairs, so {@code downloads} is empty until {@link ModpackResolver} has looked them up.
     */
    public boolean resolvesRemotely() {
        return format == ModpackFormat.CURSEFORGE_ZIP;
    }

    /** A copy of this pack with its remote files resolved into real downloads (records are immutable). */
    public ModpackInfo withDownloads(List<ModpackFile> resolvedDownloads, int unresolved,
                                     String resolvedNote) {
        return new ModpackInfo(format, name, version, mcVersion, loaderName, loaderVersion,
                resolvedDownloads, bundled, unresolved, iconPath, source, resolvedNote);
    }

    /** How many files the pack wants (declared downloads + bundled ones). */
    public int fileCount() {
        return downloads.size() + bundled.size();
    }

    /** A copy of this pack with the resolved icon attached (records are immutable). */
    public ModpackInfo withIcon(Path resolvedIcon) {
        return new ModpackInfo(format, name, version, mcVersion, loaderName, loaderVersion,
                downloads, bundled, unresolvedCount, resolvedIcon, source, note);
    }
}