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

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** The instance folder layout the launcher uses for a version+loader, e.g. 1.21.1-fabric. */
    public static Path instanceDirFor(Path launcherRoot, String mcVersion, String loader) {
        String suffix = (loader == null || loader.equalsIgnoreCase("Vanilla")) ? "" : "-" + loader.toLowerCase();
        return launcherRoot.resolve("instances").resolve(mcVersion + suffix);
    }

    public static Path fileFor(Path instanceDir) {
        return instanceDir.resolve("modpack.json");
    }

    /** Builds the metadata for a pack that's about to be (or has just been) installed. */
    public static ModpackMeta of(ModpackInfo info) {
        ModpackMeta meta = new ModpackMeta();
        meta.name = info.name();
        meta.version = info.version();
        meta.mcVersion = info.mcVersion();
        meta.loader = info.launcherLoader();
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
        if (mcVersion == null || loader == null) return null;
        ModpackMeta meta = read(instanceDirFor(launcherRoot, mcVersion, loader));
        if (meta == null || meta.loaderVersion == null || meta.loaderVersion.isBlank()) return null;
        if (meta.loader == null || !meta.loader.equalsIgnoreCase(loader)) return null;
        return meta.loaderVersion;
    }
}