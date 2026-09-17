package com.deylauncher.modpack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The record of "which modpack is installed into this client instance", written to
 * {@code ~/.deylauncher/instances/<version>-<loader>/modpack.json} after a successful install.
 *
 * It exists for two reasons:
 * <ol>
 *   <li>the modpack menu lists installed packs (name + version + icon), and</li>
 *   <li>{@link #pinnedLoaderVersion} lets the launch path reuse the EXACT loader build the pack was
 *       made for (an {@code .mrpack} pins e.g. {@code fabric-loader 0.15.11}) instead of blindly
 *       grabbing the newest one -- which is what "fully compatible with the modpack" actually needs.</li>
 * </ol>
 * Follows the same plain-Gson file shape as {@code ServerStore}'s server.json.
 */
public class ModpackMeta {

    public String name;
    public String version;
    public String mcVersion;
    public String loader;        // launcher loader value: "Vanilla" / "Fabric" / "Forge"
    public String loaderVersion; // the exact loader build the pack asked for (may be null)
    public String format;        // ModpackFormat enum name
    public String iconPath;      // cached icon PNG (also copied to <instance>/icon.png)
    public String source;        // the pack file/folder this instance was built from
    public String note;
    public long installedAt;

    /**
     * Every file this pack owns on disk (its downloads and its bundled overrides), with the URL/hash/
     * size needed to fetch each one again. Written at install time so a LATER launch can notice a mod
     * that has gone missing and put it back automatically -- without needing the original pack file,
     * and without anyone downloading jars by hand. See {@link ModpackVerifier}.
     */
    public List<ModpackFile> files;

    /**
     * Pack files that couldn't be fetched automatically (removed upstream, or published nowhere
     * public), by name/id. Kept so the launcher can say exactly what is missing instead of quietly
     * launching a pack that isn't complete.
     */
    public List<String> unresolved;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * The instance folder layout the launcher uses for a version+loader, e.g. 1.21.1-fabric.
     *
     * <p>Refuses an empty Minecraft version on purpose. {@code resolve("")} returns the SAME path in
     * Java, so a blank version would quietly resolve to the {@code instances} folder itself -- and the
     * two things that call this with a pack record (repair and delete) would then verify, or delete,
     * every instance the user has instead of one pack.
     */
    public static Path instanceDirFor(Path launcherRoot, String mcVersion, String loader) {
        if (mcVersion == null || mcVersion.isBlank()) {
            throw new IllegalArgumentException(
                    "this modpack record doesn't say which Minecraft version it was installed for");
        }
        String suffix = (loader == null || loader.equalsIgnoreCase("Vanilla")) ? "" : "-" + loader.toLowerCase();
        return launcherRoot.resolve("instances").resolve(mcVersion + suffix);
    }

    /** True when this record knows enough to locate its own instance folder (see {@link #instanceDirFor}). */
    public boolean knowsTarget() {
        return mcVersion != null && !mcVersion.isBlank();
    }

    public static Path fileFor(Path instanceDir) {
        return instanceDir.resolve("modpack.json");
    }

    /** Builds the metadata for a pack that's about to be (or has just been) installed. */
    public static ModpackMeta of(ModpackInfo info) {
        return of(info, info.mcVersion(), info.launcherLoader());
    }

    /**
     * Builds the metadata for a pack installed INTO a known version + loader.
     *
     * <p>The distinction matters and used to be a real bug: a pack may declare no Minecraft version or
     * loader at all (the launcher deliberately supports those, installing them into whatever the user
     * has selected), and {@link #of(ModpackInfo)} would then record an EMPTY version and "Vanilla".
     * Everything downstream reads this record to find the instance again -- {@link #pinnedLoaderVersion},
     * the pack menu's "play this pack", the repair button, delete -- so the pack ended up pointing at
     * {@code instances/} itself instead of its own folder, which is both useless and dangerous for the
     * delete path. Recording where the pack really went is what makes those all work.
     */
    public static ModpackMeta of(ModpackInfo info, String mcVersion, String loader) {
        ModpackMeta meta = new ModpackMeta();
        meta.name = info.name();
        meta.version = info.version();
        meta.mcVersion = mcVersion != null && !mcVersion.isBlank() ? mcVersion : info.mcVersion();
        meta.loader = loader != null && !loader.isBlank() ? loader : info.launcherLoader();
        meta.loaderVersion = info.loaderVersion();
        meta.format = info.format() == null ? null : info.format().name();
        meta.iconPath = info.iconPath() == null ? null : info.iconPath().toString();
        meta.source = info.source() == null ? null : info.source().toString();
        meta.note = info.note();
        meta.installedAt = System.currentTimeMillis();
        return meta;
    }

    public void write(Path instanceDir) {
        try {
            Files.createDirectories(instanceDir);
            Files.writeString(fileFor(instanceDir), GSON.toJson(this));
        } catch (IOException ignored) {
            // Best-effort, exactly like ServerStore.save: worst case the pack simply isn't listed
            // next run -- the installed mods themselves are untouched either way.
        }
    }

    /** The pack's file manifest, never null (an older pack record simply has none). */
    public List<ModpackFile> packFiles() {
        return files == null ? List.of() : files;
    }

    /** Pack files that couldn't be fetched automatically, never null. */
    public List<String> unresolvedFiles() {
        return unresolved == null ? List.of() : unresolved;
    }

    /** True when this record carries enough to verify/repair the pack's own files. */
    public boolean managesFiles() {
        return !packFiles().isEmpty();
    }

    public static ModpackMeta read(Path instanceDir) {
        Path meta = fileFor(instanceDir);
        if (!Files.exists(meta)) return null;
        try {
            return GSON.fromJson(Files.readString(meta), ModpackMeta.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** Every instance that has a modpack.json, newest install first. */
    public static List<ModpackMeta> listInstalled(Path launcherRoot) {
        List<ModpackMeta> out = new ArrayList<>();
        Path instances = launcherRoot.resolve("instances");
        if (!Files.isDirectory(instances)) return out;
        try (var stream = Files.list(instances)) {
            for (Path dir : (Iterable<Path>) stream.filter(Files::isDirectory)::iterator) {
                ModpackMeta meta = read(dir);
                if (meta != null && meta.name != null && !meta.name.isBlank()) out.add(meta);
            }
        } catch (IOException ignored) {
        }
        out.sort((a, b) -> Long.compare(b.installedAt, a.installedAt));
        return out;
    }

    /**
     * The loader version a pack pinned for this version+loader, or null when the instance has no
     * pack (or the pack didn't pin one) -- in which case the caller keeps its normal
     * "newest stable build" behaviour.
     */
    public static String pinnedLoaderVersion(Path launcherRoot, String mcVersion, String loader) {
        if (mcVersion == null || mcVersion.isBlank() || loader == null) return null;
        ModpackMeta meta = read(instanceDirFor(launcherRoot, mcVersion, loader));
        if (meta == null || meta.loaderVersion == null || meta.loaderVersion.isBlank()) return null;
        if (meta.loader == null || !meta.loader.equalsIgnoreCase(loader)) return null;
        return meta.loaderVersion;
    }
}