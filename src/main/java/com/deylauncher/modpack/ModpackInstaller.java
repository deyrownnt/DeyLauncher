package com.deylauncher.modpack;

import com.deylauncher.launch.DownloadProgress;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/**
 * Puts a {@link ModpackInfo} onto disk: resolves what the pack declares (downloading it if the pack
 * only shipped project/file ids), unpacks what the pack carries, and never writes outside the folder
 * it was given.
 *
 * <p>Two entry points, one engine:
 * <ul>
 *   <li>{@link #installForClient} -- into {@code ~/.deylauncher/instances/<mc>-<loader>}, i.e. the same
 *       instance folder Play uses, so the pack is immediately launchable. Also writes the instance's
 *       {@code modpack.json} (including the full file manifest, so a later launch can RESTORE anything
 *       that goes missing) and its {@code icon.png}.</li>
 *   <li>{@link #installForServer} -- into an owned server's folder, taking only the files a server can
 *       actually use (its {@code mods/} or {@code plugins/} folder, plus {@code config/}).</li>
 * </ul>
 *
 * <p>Robustness notes worth knowing when reading this: every path that comes out of a pack goes
 * through {@link PackFileOps#resolve} (zip-slip guard), a pre-existing correct file is reused instead
 * of re-downloaded (so re-running an install is cheap), downloads are streamed and size-checked, and a
 * per-file failure is collected rather than aborting the whole install -- one dead CDN link shouldn't
 * throw away the other 200 mods. A pack file that is merely OPTIONAL (Modrinth's {@code env} block)
 * failing is a warning, never an incomplete install.
 */
public class ModpackInstaller {

    /** Share of the caller's progress bar the id-resolution phase gets; the rest is file transfer. */
    private static final double RESOLVE_SHARE = 0.35;

    private final ModpackResolver resolver;
    private final PackFileOps.Downloader downloader;

    public ModpackInstaller() {
        this(new ModpackResolver(), PackFileOps.HTTP);
    }

    /** Test seam: a resolver + downloader that never touch the network. */
    ModpackInstaller(ModpackResolver resolver, PackFileOps.Downloader downloader) {
        this.resolver = resolver;
        this.downloader = downloader;
    }

    /** What the caller already knew about the pack, plus everything resolution added. */
    private record Preparations(ModpackInfo info, int resolved, List<String> unresolvedNames) {}

    /** Outcome of one install: what actually happened, per file. */
    public record Result(int downloaded, int extracted, int reused, int failed, int skippedForThisSide,
                         int unresolved, List<String> errors, List<String> warnings) {

        public boolean ok() {
            return failed == 0;
        }

        /** One-line, human-readable summary shown in the installer window. */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(downloaded).append(" downloaded, ").append(extracted).append(" unpacked");
            if (reused > 0) sb.append(", ").append(reused).append(" already in place");
            if (skippedForThisSide > 0) sb.append(", ").append(skippedForThisSide).append(" not needed here");
            if (!warnings.isEmpty()) sb.append(", ").append(warnings.size()).append(" optional not installed");
            if (unresolved > 0) sb.append(", ").append(unresolved).append(" couldn't be fetched");
            if (failed > 0) sb.append(", ").append(failed).append(" FAILED");
            return sb.toString();
        }
    }

    // ---- client / server entry points ----

    /**
     * Installs the CLIENT half of a pack into an instance folder, for the pack's own Minecraft version
     * and loader. Writes the per-instance modpack record + manifest + icon afterwards, so the launcher
     * can list the pack, reuse its exact loader version, and restore missing files on later launches.
     */
    public Result installForClient(ModpackInfo info, Path instanceDir, DownloadProgress progress)
            throws IOException {
        return installForClient(info, info.mcVersion(), info.launcherLoader(), instanceDir, progress, null);
    }

    /** Same, for a pack being installed into the version/loader currently selected in the launcher. */
    public Result installForClient(ModpackInfo info, String mcVersion, String loader, Path instanceDir,
                                   DownloadProgress progress, Consumer<String> log) throws IOException {
        Preparations prep = prepare(info, mcVersion, loader, progress, log);
        Result result = install(prep.info(), instanceDir, PackFileOps.Side.CLIENT, null, progress);
        PackIcons.copyIntoInstance(info.iconPath(), instanceDir);
        writeInstanceRecord(info, prep, instanceDir, result, mcVersion, loader);
        return result;
    }

    /**
     * Installs the SERVER half of a pack into an owned server's folder.
     *
     * @param addonFolder the folder that server type reads -- "mods" (Fabric/Forge) or "plugins"
     *                    (Purpur) -- so a client-only mod never lands in a server it can't run.
     */
    public Result installForServer(ModpackInfo info, Path serverDir, String addonFolder,
                                   DownloadProgress progress) throws IOException {
        return installForServer(info, info.mcVersion(), info.launcherLoader(), serverDir, addonFolder,
                progress, null);
    }

    /** Same, for a pack whose version/loader the caller knows better than the pack does. */
    public Result installForServer(ModpackInfo info, String mcVersion, String loader, Path serverDir,
                                   String addonFolder, DownloadProgress progress, Consumer<String> log)
            throws IOException {
        Preparations prep = prepare(info, mcVersion, loader, progress, log);
        return install(prep.info(), serverDir, PackFileOps.Side.SERVER, addonFolder, progress);
    }

    // ---- the shared engine ----

    /** Resolves the pack's remote files (a no-op for a Modrinth pack) and reports its own progress. */
    private Preparations prepare(ModpackInfo info, String mcVersion, String loader,
                                 DownloadProgress progress, Consumer<String> log) {
        DownloadProgress resolveProgress = progress == null ? null
                : f -> progress.onProgress(Math.max(0.0, Math.min(1.0, f)) * RESOLVE_SHARE);
        ModpackResolver.Outcome outcome = resolver.resolve(info, mcVersion, loader, resolveProgress, log);
        if (outcome.resolved() > 0 && log != null) {
            log.accept("Resolved " + outcome.resolved() + " pack file(s) to fetch automatically.");
        }
        return new Preparations(outcome.info(), outcome.resolved(), outcome.unresolvedNames());
    }

    /** Fetches what's needed, unpacks what's carried, and reports exactly what happened. */
    private Result install(ModpackInfo info, Path targetDir, PackFileOps.Side side, String addonFolder,
                           DownloadProgress progress) throws IOException {
        Files.createDirectories(targetDir);

        List<ModpackFile> toFetch = new ArrayList<>();
        List<ModpackFile> toUnpack = new ArrayList<>();
        for (ModpackFile f : info.downloads()) if (PackFileOps.wanted(f, side, addonFolder)) toFetch.add(f);
        for (ModpackFile f : info.bundled()) if (PackFileOps.wanted(f, side, addonFolder)) toUnpack.add(f);

        int total = Math.max(1, toFetch.size() + toUnpack.size());
        int done = 0;
        int downloaded = 0, extracted = 0, reused = 0, failed = 0;
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (ModpackFile f : toFetch) {
            try {
                if (fetch(f, targetDir)) downloaded++;
                else reused++;
            } catch (Exception e) {
                if (requiredFor(f, side)) { failed++; errors.add(f.path() + " -- " + e.getMessage()); }
                else warnings.add(f.path() + " -- " + e.getMessage());
            }
            report(progress, ++done, total);
        }

        ZipFile zip = null;
        try {
            if (info.format() != null && info.format().archive() && !toUnpack.isEmpty()
                    && info.source() != null && Files.isRegularFile(info.source())) {
                zip = new ZipFile(info.source().toFile());
            }
            for (ModpackFile f : toUnpack) {
                try {
                    Path dest = PackFileOps.resolve(targetDir, f.path());
                    if (dest == null) {
                        // A path this OS can't create (Windows-invalid characters, a reserved device
                        // name, too long) or one that escapes the folder: say WHICH file and WHY,
                        // instead of a bare filesystem error.
                        String reason = unresolvableReason(f.path(), targetDir);
                        if (requiredFor(f, side)) { failed++; errors.add(f.path() + " -- " + reason); }
                        else warnings.add(f.path() + " -- " + reason);
                    } else if (PackFileOps.unpackTo(f, zip, dest)) {
                        extracted++;
                    } else if (requiredFor(f, side)) {
                        failed++;
                        errors.add(f.path() + " -- couldn't be read out of the pack");
                    } else {
                        warnings.add(f.path() + " -- couldn't be read out of the pack");
                    }
                } catch (Exception e) {
                    if (requiredFor(f, side)) { failed++; errors.add(f.path() + " -- " + e.getMessage()); }
                    else warnings.add(f.path() + " -- " + e.getMessage());
                }
                report(progress, ++done, total);
            }
        } finally {
            if (zip != null) zip.close();
        }

        int considered = toFetch.size() + toUnpack.size();
        int skippedForThisSide = (info.downloads().size() + info.bundled().size()) - considered;
        return new Result(downloaded, extracted, reused, failed, Math.max(0, skippedForThisSide),
                info.unresolvedCount(), errors, warnings);
    }

    /** Whether a file failing is an incomplete install (required) or just a note (optional). */
    private static boolean requiredFor(ModpackFile f, PackFileOps.Side side) {
        return side == PackFileOps.Side.CLIENT ? f.clientRequired() : f.serverRequired();
    }

    /**
     * The plain-language reason one pack-declared path can't be written here: a Windows device name,
     * an invalid character, a trailing dot/space, an over-long path, or a zip-slip attempt. Same
     * source of truth as the guard in {@link PackFileOps#resolve} (`pathProblem`), so the per-file
     * message can never disagree with what actually blocked the write.
     */
    private static String unresolvableReason(String rel, Path targetDir) {
        String problem = PackFileOps.pathProblem(rel, targetDir);
        return problem != null ? problem : "the pack listed a path that would escape the instance folder";
    }

    /**
     * Makes sure one declared file exists and matches what the pack asked for. Returns true when it was
     * actually downloaded, false when an already-correct copy was reused. Throws with a readable reason
     * when it can't be done (offline, link removed upstream, hash/size mismatch, unsafe path) -- and
     * never leaves a wrong or half-written file behind to be "reused" as if it were fine.
     */
    private boolean fetch(ModpackFile f, Path targetDir) throws IOException {
        Path dest = PackFileOps.resolve(targetDir, f.path());
        if (dest == null) throw new IOException(unresolvableReason(f.path(), targetDir));
        if (PackFileOps.matches(dest, f)) return false;
        if (!f.downloadable()) throw new IOException("the pack gives no download link for it");
        if (!downloader.download(f.url(), dest, f.sizeBytes())) {
            try {
                Files.deleteIfExists(dest);
            } catch (IOException ignored) {
            }
            throw new IOException("the download failed (offline, gone upstream, or the size didn't match)");
        }
        if (f.hasHash() && !PackFileOps.hashMatches(dest, f)) {
            try {
                Files.deleteIfExists(dest);
            } catch (IOException ignored) {
            }
            throw new IOException("the downloaded file didn't match the pack's hash");
        }
        return true;
    }

    private static void report(DownloadProgress progress, int done, int total) {
        if (progress != null) {
            double files = (double) done / Math.max(1, total);
            progress.onProgress(RESOLVE_SHARE + files * (1.0 - RESOLVE_SHARE));
        }
    }

    // ---- the instance's own record of the pack ----

    /**
     * Writes {@code <instance>/modpack.json}: the pack's identity, the version+loader it was installed
     * INTO, its pinned loader version, the full list of files it owns (so {@link ModpackVerifier} can
     * restore any of them later without the original pack file), and whatever couldn't be fetched.
     */
    private void writeInstanceRecord(ModpackInfo info, Preparations prep, Path instanceDir, Result result,
                                     String targetMcVersion, String targetLoader) {
        ModpackMeta meta = ModpackMeta.of(info, targetMcVersion, targetLoader);
        meta.files = ownedFiles(allDeclared(prep.info()), PackFileOps.Side.CLIENT, null);
        meta.unresolved = prep.unresolvedNames();
        meta.note = prep.info().note();

        ModpackMeta previous = ModpackMeta.read(instanceDir);
        // A DIFFERENT pack here (another name), or another BUILD of the same pack: either way, whatever
        // the previous record owned and this one doesn't ask for is stale and must go. Leaving it
        // behind stacks two pack versions in one instance -- a mod set neither author tested, which is
        // exactly how a "worked yesterday" pack starts crashing after an update. Only files the old
        // record claims are ever removed, so a mod the user added by hand is untouched.
        if (previous != null && previous.name != null && info.name() != null && isReplacement(previous, info)) {
            List<String> removed = removeStaleOwnedFiles(instanceDir, previous, meta);
            if (!removed.isEmpty()) {
                String note = removed.size() + " file(s) left over from \"" + previous.name
                        + (sameText(previous.version, info.version()) ? "" : " " + nullToEmpty(previous.version))
                        + "\" were removed so the two packs don't mix: " + preview(removed);
                result.warnings().add(note);
            }
        }
        meta.write(instanceDir);
    }

    /**
     * True when this install replaces what was already in the instance: a pack with a different name,
     * or the same pack's files at a different version.
     */
    private static boolean isReplacement(ModpackMeta previous, ModpackInfo info) {
        if (!previous.name.equalsIgnoreCase(info.name())) return true;
        return !sameText(previous.version, info.version());
    }

    private static boolean sameText(String a, String b) {
        return nullToEmpty(a).equalsIgnoreCase(nullToEmpty(b));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** Every file this pack owns for one side of an install: the manifest a repair run works from. */
    static List<ModpackFile> ownedFiles(List<ModpackFile> declared, PackFileOps.Side side, String addonFolder) {
        List<ModpackFile> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ModpackFile f : declared) {
            if (PackFileOps.wanted(f, side, addonFolder) && seen.add(f.path().toLowerCase(Locale.ROOT))) {
                out.add(f);
            }
        }
        return out;
    }

    /**
     * Deletes the files the PREVIOUS pack put in this instance that the new pack doesn't ask for.
     *
     * <p>An instance folder is one Minecraft version + loader, so installing a second pack for the same
     * pair used to leave both packs' mods stacked on top of each other -- a mix neither pack author ever
     * tested, which shows up as a crash the moment the game starts. Only files the old pack's own record
     * claims are touched, so a mod the user added by hand is never removed.
     */
    private static List<String> removeStaleOwnedFiles(Path instanceDir, ModpackMeta previous, ModpackMeta fresh) {
        List<String> removed = new ArrayList<>();
        if (previous.files == null) return removed;
        Set<String> keep = new LinkedHashSet<>();
        if (fresh.files != null) {
            for (ModpackFile f : fresh.files) {
                if (f.path() != null) keep.add(f.path().toLowerCase(Locale.ROOT));
            }
        }
        for (ModpackFile f : previous.files) {
            if (f.path() == null || keep.contains(f.path().toLowerCase(Locale.ROOT))) continue;
            Path dest = PackFileOps.resolve(instanceDir, f.path());
            if (dest != null && deleteQuietly(dest)) {
                removed.add(f.path());
                continue;
            }
            // A mod the user had switched OFF lives in mods-disabled/ instead of mods/. The path is
            // canonicalised first, so a pack that spells it "Mods/" is found in the same place.
            String owned = ModpackReader.canonicalGameFolder(f.path());
            if (owned != null && owned.startsWith("mods/")) {
                Path disabled = instanceDir.resolve("mods-disabled")
                        .resolve(owned.substring("mods/".length()));
                Path safe = disabled.normalize();
                if (safe.startsWith(instanceDir.normalize()) && deleteQuietly(safe)) removed.add(f.path());
            }
        }
        return removed;
    }

    private static boolean deleteQuietly(Path p) {
        try {
            return Files.deleteIfExists(p);
        } catch (IOException e) {
            return false; // locked by a running game / permissions -- leave it, never fail the install
        }
    }

    private static String preview(List<String> names) {
        if (names.size() <= 5) return String.join(", ", names);
        return String.join(", ", names.subList(0, 5)) + ", ...";
    }

    /** Everything a pack declares for one side (its downloads plus the files it carries). */
    static List<ModpackFile> allDeclared(ModpackInfo info) {
        List<ModpackFile> declared = new ArrayList<>(info.downloads());
        declared.addAll(info.bundled());
        return declared;
    }
}