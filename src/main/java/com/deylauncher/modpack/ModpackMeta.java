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
 * {@code ~/.deylauncher/instances/<folder>/modpack.json} after a successful install.
 *
 * It exists for two reasons:
 * <ol>
 *   <li>the modpack menu lists installed packs (name + version + icon), and</li>
 *   <li>{@link #pinnedLoaderVersion} lets the launch path reuse the EXACT loader build the pack was
 *       made for (an {@code .mrpack} pins e.g. {@code fabric-loader 0.15.11}) instead of blindly
 *       grabbing the newest one -- which is what "fully compatible with the modpack" actually needs.</li>
 * </ol>
 *
 * <p><b>Storage vs. selection.</b> Each pack lives in its OWN folder under {@code instances/} so two
 * packs never share a {@code mods/} directory ({@link #installDirFor}); that folder's name is the
 * pack's identity on disk ({@link #instanceId}). That is purely a question of WHERE FILES ARE. It says
 * nothing about which Minecraft version or loader the user has selected in the launcher -- the
 * {@code mcVersion}/{@code loader} recorded here are the pack's compatibility information (what it
 * was installed for), never a lock on the launcher's selectors. See {@link ModpackSelection}.</p>
 *
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
     * The name of this pack's folder under {@code instances/} -- its identity on disk. Deliberately
     * NOT stored in modpack.json (it is {@code transient}): it is simply the folder the record was read
     * from or written to, so it can never disagree with where the files really are, and a record written
     * by an older build (including one that still carries a {@code profileId} key, which is ignored)
     * needs no migration. Null only for a record that hasn't been placed in a folder yet.
     */
    public transient String instanceId;

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

    /**
     * The folder THIS pack's files live in: {@code instances/<instanceId>}. A record that was never
     * placed in a folder (built in code, not yet written) falls back to the plain
     * {@code instances/<mc>-<loader>} path, which is where packs installed by older builds used to live --
     * for those the folder name IS that path, so both cases resolve identically. (The launcher moves
     * such packs into folders of their own at startup: {@link #separateLegacyPacks}.)
     */
    public Path instanceDir(Path launcherRoot) {
        if (instanceId != null && !instanceId.isBlank()) {
            return launcherRoot.resolve("instances").resolve(instanceId);
        }
        return instanceDirFor(launcherRoot, mcVersion, loader);
    }

    /**
     * True when this pack was installed for exactly this Minecraft version + loader (case-insensitive;
     * a missing loader means "Vanilla", like everywhere else). This is compatibility information: it
     * answers "would this pack's mods run under that selection?", nothing more.
     */
    public boolean targets(String mcVersion, String loader) {
        return mcVersion != null && this.mcVersion != null && this.mcVersion.equalsIgnoreCase(mcVersion)
                && loaderOrVanilla(this.loader).equalsIgnoreCase(loaderOrVanilla(loader));
    }

    private static String loaderOrVanilla(String loader) {
        return loader == null || loader.isBlank() ? "Vanilla" : loader;
    }

    /**
     * Where a pack that is about to be installed should live.
     *
     * <ul>
     *   <li>The SAME pack for the same version + loader that is already installed keeps its folder, so
     *       reinstalling or updating it replaces its own stale files in place (see
     *       {@code ModpackInstaller}) and keeps the player's config and options, instead of piling up a
     *       duplicate every time.</li>
     *   <li>Any other pack gets a new folder named after it that nothing else uses, so two packs -- even
     *       for the same Minecraft version and loader -- never share a {@code mods/} directory.</li>
     * </ul>
     * Pure lookup: nothing is created here.
     */
    public static Path installDirFor(Path launcherRoot, String packName, String mcVersion, String loader) {
        String wanted = packName == null ? "" : packName.trim();
        for (ModpackMeta installed : listInstalled(launcherRoot)) {
            if (installed.name.trim().equalsIgnoreCase(wanted) && installed.targets(mcVersion, loader)) {
                return installed.instanceDir(launcherRoot);
            }
        }
        return freshFolder(launcherRoot.resolve("instances"), packName);
    }

    /** A folder under {@code instances} named after the pack that nothing else uses yet (pure lookup). */
    private static Path freshFolder(Path instances, String packName) {
        String base = folderSlug(packName);
        String candidate = base;
        for (int n = 2; Files.exists(instances.resolve(candidate)); n++) {
            candidate = base + "-" + n;
        }
        return instances.resolve(candidate);
    }

    /**
     * Gives every pack that an older build installed INTO the plain {@code instances/<mc>-<loader>} folder
     * a folder of its own, so that folder goes back to being the ordinary Vanilla / DEY instance for that
     * Minecraft version and loader.
     *
     * <p>Why: before packs got their own folders, installing a pack for 1.20.1 + Fabric put its mods
     * into {@code instances/1.20.1-fabric} -- the very folder the plain 1.20.1 Fabric profile (and the
     * DEY 1.20.1 profile, which adds its own mods on top) launches. Selecting plain 1.20.1 therefore
     * loaded the pack's mods. Moving the whole folder (mods, config, options, the pack record) under
     * the pack's own name ends that: the pack keeps a modlist that is only ever its own, and
     * plain 1.20.1 starts from an empty folder. Nothing is deleted or rewritten -- it is one rename per
     * pack, and the pack record needs no edit because a pack's folder is its identity
     * ({@link #instanceId}). Worlds are unaffected: {@code saves/} is a link into the shared pool.</p>
     *
     * <p>Idempotent and best-effort: a pack that already has its own folder is skipped, and a folder that
     * can't be renamed right now (something has it open) is left exactly where it is and tried again on
     * the next start.</p>
     *
     * @return one human-readable line per pack moved or that could not be moved; empty when there was
     *         nothing to do
     */
    public static List<String> separateLegacyPacks(Path launcherRoot) {
        List<String> notes = new ArrayList<>();
        Path instances = launcherRoot.resolve("instances");
        if (!Files.isDirectory(instances)) return notes;
        List<Path> dirs = new ArrayList<>();
        try (var stream = Files.list(instances)) {
            stream.filter(Files::isDirectory).forEach(dirs::add);
        } catch (IOException e) {
            return notes;
        }
        dirs.sort(null); // stable order, so two same-named packs always get the same folder names
        for (Path dir : dirs) {
            ModpackMeta meta = read(dir);
            if (meta == null || meta.name == null || meta.name.isBlank() || !meta.knowsTarget()) continue;
            Path plain = instanceDirFor(launcherRoot, meta.mcVersion, meta.loader);
            if (!dir.getFileName().toString().equalsIgnoreCase(plain.getFileName().toString())) continue;
            Path own = freshFolder(instances, meta.name);
            try {
                Files.move(dir, own);
                notes.add("Modpack \"" + meta.name + "\" now has its own folder (" + own.getFileName()
                        + "); " + dir.getFileName() + " is a plain " + meta.mcVersion + " instance again.");
            } catch (IOException | RuntimeException e) {
                notes.add("Couldn't give modpack \"" + meta.name + "\" its own folder yet (" + e.getMessage()
                        + ") -- it still shares " + dir.getFileName() + " and will be moved on the next start.");
            }
        }
        return notes;
    }

    /** A filesystem-safe folder name for a pack name (never empty, never a path, never a reserved device name). */
    static String folderSlug(String name) {
        String slug = (name == null || name.isBlank()) ? "modpack" : name;
        slug = slug.replaceAll("[^A-Za-z0-9._-]", "-").replaceAll("-{2,}", "-");
        slug = slug.replaceAll("^[-.]+|[-.]+$", "");
        if (slug.length() > 60) slug = slug.substring(0, 60).replaceAll("[-.]+$", "");
        if (slug.isEmpty()) return "modpack";
        if (slug.matches("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?")) return "pack-" + slug;
        return slug;
    }

    /**
     * The pack's icon on disk, or null when it has none: the cached copy {@link #iconPath} points at, or
     * -- when that cache was cleared or moved -- the {@code icon.png} the installer copied into the
     * pack's own folder ({@link PackIcons#copyIntoInstance}). Never throws on an odd stored path; a
     * missing icon is cosmetic.
     */
    public Path iconFile(Path launcherRoot) {
        try {
            if (iconPath != null && !iconPath.isBlank()) {
                Path cached = Path.of(iconPath);
                if (Files.isRegularFile(cached)) return cached;
            }
        } catch (RuntimeException invalidPath) {
            // fall through to the copy inside the instance
        }
        try {
            Path inInstance = instanceDir(launcherRoot).resolve("icon.png");
            return Files.isRegularFile(inInstance) ? inInstance : null;
        } catch (RuntimeException noFolder) {
            return null;
        }
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
        this.instanceId = folderName(instanceDir);
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
            ModpackMeta read = GSON.fromJson(Files.readString(meta), ModpackMeta.class);
            if (read != null) read.instanceId = folderName(instanceDir);
            return read;
        } catch (Exception e) {
            return null;
        }
    }

    private static String folderName(Path instanceDir) {
        Path name = instanceDir.getFileName();
        return name == null ? null : name.toString();
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
     * The loader build this pack pinned, or null when it pinned none or the pin doesn't apply to this
     * launch (a different loader or Minecraft version) -- in which case the caller keeps its normal
     * "newest stable build" behaviour.
     *
     * <p>An instance method on purpose: the record to ask is the one living in the folder that is about
     * to launch (see {@link ModpackSelection#resolve}), never one found by searching every folder for a
     * matching version + loader.</p>
     */
    public String pinnedLoaderVersion(String mcVersion, String loader) {
        if (loaderVersion == null || loaderVersion.isBlank()) return null;
        if (loader == null || !targets(mcVersion, loader)) return null;
        return loaderVersion;
    }
}
