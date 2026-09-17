package com.deylauncher.modpack;

import com.deylauncher.server.ModrinthClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Fills in the one thing some packs can't tell us themselves: WHERE their files come from.
 *
 * <p>A Modrinth pack lists a URL + hashes per file, so it needs no help. A CurseForge pack lists only
 * {@code projectID}/{@code fileID} pairs -- which is why those packs used to install nothing but their
 * {@code overrides/} and left every mod to be downloaded from the website and dropped into the mods
 * folder by hand. This resolver looks each pair up (see {@link CurseForgeFiles}) and turns it into the
 * {@code mods/<name>.jar} line {@link ModpackInstaller} and {@link ModpackVerifier} work with, so the
 * whole pack installs, downloads and self-repairs without anyone touching a browser.
 *
 * <p>Resolution order per entry:
 * <ol>
 *   <li><b>CurseForge, by id</b> -- authoritative: the exact file the pack pinned, with its real size
 *       (checked after download) and its own client/server markers.</li>
 *   <li><b>Modrinth, by exact name</b> -- only used when the id lookup fails, and only when the pack's
 *       own {@code modlist.html} gave a name, the manifest and the modlist line up one-to-one, and
 *       Modrinth returns a project whose name matches that name EXACTLY. A fuzzy match could install
 *       a different mod into someone's pack, which is far worse than reporting the file as missing.</li>
 *   <li><b>Reported as unresolved</b> -- listed by id/name in the install log and kept in the
 *       instance's {@code modpack.json}, so nothing is ever silently missing.</li>
 * </ol>
 *
 * <p>Everything else in the pack (its {@code overrides/}) is untouched by this class.
 */
public class ModpackResolver {

    /** What one resolution pass did, plus the pack with its downloads filled in. */
    public record Outcome(ModpackInfo info, int resolved, int unresolved, List<String> unresolvedNames) {}

    private final CurseForgeFiles curseForge;
    private final ModrinthClient modrinth;

    public ModpackResolver() {
        this(new CurseForgeFiles(), new ModrinthClient());
    }

    /** Test seams: any id-resolver and any Modrinth client. */
    ModpackResolver(CurseForgeFiles curseForge, ModrinthClient modrinth) {
        this.curseForge = curseForge;
        this.modrinth = modrinth;
    }

    /**
     * Resolves every remote file of {@code info} for the given Minecraft version + loader.
     *
     * @param progress receives a 0.0 -> 1.0 fraction of the RESOLUTION phase, so the installer window
     *                 can show progress during a 300-mod lookup instead of sitting at zero.
     * @param log      optional one-line-per-notable-event sink.
     */
    public Outcome resolve(ModpackInfo info, String mcVersion, String loader,
                           com.deylauncher.launch.DownloadProgress progress, Consumer<String> log) {
        if (info == null || !info.resolvesRemotely()) {
            return new Outcome(info, 0, 0, List.of());
        }
        List<CurseForgeEntry> entries = ModpackReader.curseForgeEntries(info.source());
        if (entries.isEmpty()) {
            String note = "This CurseForge pack lists no downloadable files of its own -- only the files "
                    + "bundled inside it were installed.";
            return new Outcome(info.withDownloads(info.downloads(), 0, note), 0, 0, List.of());
        }

        List<ModpackFile> downloads = new ArrayList<>(info.downloads());
        List<String> unresolved = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int resolved = 0, done = 0;

        for (CurseForgeEntry entry : entries) {
            ModpackFile file = fromCurseForge(entry);
            if (file == null) file = fromModrinth(entry, mcVersion, loader);
            if (file == null) {
                unresolved.add(entry.describe());
            } else if (seen.add(file.path().toLowerCase(Locale.ROOT))) {
                downloads.add(file);
                resolved++;
            }
            if (log != null && resolved > 0 && resolved % 25 == 0) {
                log.accept("Resolved " + resolved + " of " + entries.size() + " pack file(s)...");
            }
            if (progress != null) {
                progress.onProgress((double) ++done / entries.size());
            }
        }

        String note = unresolved.isEmpty() ? null
                : unresolved.size() + " file(s) of this pack couldn't be fetched automatically and are "
                + "NOT installed:\n  " + String.join("\n  ", unresolved);
        ModpackInfo resolvedInfo = info.withDownloads(downloads, unresolved.size(), note);
        return new Outcome(resolvedInfo, resolved, unresolved.size(), unresolved);
    }

    // ---- per-entry resolution ----

    /** The exact file the pack pinned on CurseForge, as a download into the instance's mods/. */
    private ModpackFile fromCurseForge(CurseForgeEntry entry) {
        CurseForgeFiles.Resolved r = curseForge.resolve(entry.projectId(), entry.fileId());
        if (r == null || r.fileName() == null || r.fileName().isBlank()) return null;
        String path = folderFor(r.fileName()) + r.fileName();
        return ModpackFile.download(path, r.url(), null, null, r.sizeBytes(),
                r.clientOk(), !r.clientOk(), r.serverOk(), !r.serverOk());
    }

    /**
     * Fallback for a file CurseForge no longer serves: the same mod, by exact name, from Modrinth --
     * but only for a build published for this Minecraft version AND this loader.
     *
     * <p>Package-private so tests can drive the fallback without a network.
     */
    ModpackFile fromModrinth(CurseForgeEntry entry, String mcVersion, String loader) {
        String name = entry.name();
        if (name == null || name.isBlank() || mcVersion == null || mcVersion.isBlank()) return null;
        try {
            ModrinthClient.Hit hit = modrinth.firstHitByName(name, "mod");
            if (!trustedNameMatch(name, hit)) return null;
            ModrinthClient.ProjectVersion version = pickVersion(
                    modrinth.compatibleVersionsLenient(hit.slug(), mcVersion), loader);
            if (version == null) return null;
            ModrinthClient.FileRef jar = version.firstJar();
            if (jar == null || jar.url() == null || jar.url().isBlank()) return null;
            String fileName = jar.filename() == null || jar.filename().isBlank() ? "mod.jar" : jar.filename();
            return ModpackFile.download("mods/" + fileName, jar.url(), null, null, jar.sizeBytes(),
                    true, false, true, false);
        } catch (Exception e) {
            // Offline / rate-limited / project gone -- the file stays in the unresolved list instead.
        }
        return null;
    }

    /**
     * The safety rule of the Modrinth fallback: a search hit only counts when the project's name is
     * EXACTLY what the pack's modlist said. A fuzzy near-match here would install a different mod into
     * someone's pack -- far worse than admitting that one file couldn't be fetched.
     */
    static boolean trustedNameMatch(String packName, ModrinthClient.Hit hit) {
        if (hit == null || hit.slug() == null || hit.slug().isBlank()) return false;
        if (packName == null || packName.isBlank() || hit.name() == null) return false;
        return hit.name().equalsIgnoreCase(packName.trim());
    }

    /** The newest of {@code compatible} that was actually published for {@code loader}, or null. */
    static ModrinthClient.ProjectVersion pickVersion(List<ModrinthClient.ProjectVersion> compatible,
                                                     String loader) {
        // Modrinth returns versions newest first, so the first usable one is the newest usable one.
        for (ModrinthClient.ProjectVersion version : compatible) {
            if (version.supportsLoader(loader)) return version;
        }
        return null;
    }

    /**
     * Which instance folder a resolved CurseForge file belongs in.
     *
     * <p>CurseForge's manifest carries no path, so this is decided from what the file actually is: a
     * jar is a mod ({@code mods/}), and the two non-mod shapes packs use most -- shaders and resource
     * packs -- are told apart by name/extension. Anything unrecognised goes to {@code mods/}, exactly
     * where a CurseForge pack's files belong in the overwhelming majority of cases.
     */
    static String folderFor(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        if (CurseForgeFiles.looksLikeModJar(n)) return "mods/";
        if (n.contains("shader")) return "shaderpacks/";
        if (n.endsWith(".zip")) return "resourcepacks/";
        return "mods/";
    }
}