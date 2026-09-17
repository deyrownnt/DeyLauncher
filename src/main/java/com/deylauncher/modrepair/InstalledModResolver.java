package com.deylauncher.modrepair;

import com.deylauncher.modpack.CurseForgeFallback;
import com.deylauncher.modpack.ModDistributionPolicy;
import com.deylauncher.server.ModrinthClient;

import java.nio.file.Path;
import java.util.List;

/**
 * Works out where a build of one INSTALLED mod can legitimately come from, so the Mods window's
 * checker/fixer has a single answer to work with instead of three slightly different guesses.
 *
 * <p>Sources (content) and loaders (runtime) are separate concepts and stay separate here: a source is
 * {@code Modrinth} or {@code CurseForge}; a loader is {@code Fabric}/{@code Forge}/{@code NeoForge} and
 * is only ever used to FILTER which published build of a project is compatible.
 *
 * <p>Resolution order, exactly as intended:
 * <ol>
 *   <li><b>Modrinth</b> -- by the mod's own declared id, which must resolve to a real project
 *       (never a fuzzy name match: acting on a near-match is how a pack loses working mods). If
 *       Modrinth has a compatible build, that is the answer and the fallback is never consulted.</li>
 *   <li><b>CurseForge, keyless</b> -- only when Modrinth has no compatible build (or doesn't know the
 *       mod at all) AND the mod's own declared license establishes that a build of it may be fetched
 *       on the user's behalf (see {@link ModDistributionPolicy}).</li>
 *   <li><b>Nothing</b> -- reported with a plain-language reason, so "automatic fixing is unavailable
 *       for this mod" is visible instead of a silent no-op.</li>
 * </ol>
 *
 * <p>Nothing here downloads anything: {@link Resolution} just says what (if anything) should be
 * fetched, and by which source.
 */
public class InstalledModResolver {

    /** A content source a mod's build can come from. Loaders are NOT sources and never appear here. */
    public enum Source { MODRINTH, CURSEFORGE, NONE }

    /**
     * What the resolver concluded for one installed mod.
     *
     * @param source                        where a usable build was found, or {@link Source#NONE}.
     * @param modrinthKnown                 the mod's declared id resolves to a real Modrinth project.
     * @param upToDate                      the installed jar already is a build we would install.
     * @param needsReplacement              a compatible build exists that is NOT the installed jar.
     * @param noModrinthBuildForThisVersion Modrinth knows the mod but has no build for this MC version.
     * @param note                          plain-language reason when nothing can be done automatically.
     */
    public record Resolution(Source source, boolean modrinthKnown, boolean upToDate,
                             boolean needsReplacement, boolean noModrinthBuildForThisVersion,
                             boolean transientFailure, CurseForgeFallback.Candidate curseForgeCandidate,
                             String targetFileName, String downloadUrl, String targetVersionLabel,
                             String note) {

        /** True when the fixer can actually fetch a replacement. */
        public boolean canFix() {
            return needsReplacement && downloadUrl != null && !downloadUrl.isBlank()
                    && targetFileName != null && !targetFileName.isBlank();
        }

        static Resolution upToDate(Source source, boolean modrinthKnown, boolean noModrinthBuild) {
            return new Resolution(source, modrinthKnown, true, false, noModrinthBuild, false, null,
                    null, null, null, null);
        }

        static Resolution replace(Source source, boolean modrinthKnown, boolean noModrinthBuild,
                                  CurseForgeFallback.Candidate cf,
                                  String fileName, String url, String versionLabel) {
            return new Resolution(source, modrinthKnown, false, true, noModrinthBuild, false,
                    source == Source.CURSEFORGE ? cf : null, fileName, url, versionLabel, null);
        }

        static Resolution unavailable(boolean modrinthKnown, boolean noModrinthBuild,
                                      boolean transientFailure, String note) {
            return new Resolution(Source.NONE, modrinthKnown, false, false, noModrinthBuild,
                    transientFailure, null, null, null, null, note);
        }
    }

    private final ModrinthClient modrinth;
    private final CurseForgeFallback curseForge;

    public InstalledModResolver() {
        this(new ModrinthClient(), new CurseForgeFallback());
    }

    /** Resolver whose CurseForge fallback caches its answers under the launcher's root folder. */
    public InstalledModResolver(Path launcherRoot) {
        this(new ModrinthClient(), new CurseForgeFallback(launcherRoot));
    }

    /** Test seam: any Modrinth client and any CurseForge fallback (both never touch the network). */
    public InstalledModResolver(ModrinthClient modrinth, CurseForgeFallback curseForge) {
        this.modrinth = modrinth;
        this.curseForge = curseForge;
    }

    /**
     * Resolves one installed mod.
     *
     * @param declaredModId     the mod's own id from its metadata ({@code fabric.mod.json}/{@code mods.toml}).
     * @param installedFileName the jar on disk, used to tell "already current" from "replace it".
     * @param mcVersion         the instance's Minecraft version.
     * @param loader            the instance's loader ("Fabric"/"Forge"/"NeoForge"), used to filter builds.
     * @param licenseText       the license the jar itself declares, or null when it declares none.
     */
    public Resolution resolve(String declaredModId, String installedFileName, String mcVersion,
                              String loader, String licenseText) {
        if (mcVersion == null || mcVersion.isBlank()) {
            return Resolution.unavailable(false, false, false, "no Minecraft version is selected");
        }
        if (declaredModId == null || declaredModId.isBlank()) {
            return Resolution.unavailable(false, false, false,
                    "the jar doesn't declare a mod id, so it can't be identified safely");
        }

        boolean modrinthKnown = false;
        boolean modrinthNoCompatibleBuild = false; // Modrinth knows the mod, but publishes nothing for this MC version
        String modrinthProblem = "";       // how to describe the Modrinth side if we can't act
        boolean modrinthTransient = false; // Modrinth was unreachable: worth retrying later
        try {
            ModrinthClient.Hit hit = modrinth.projectByExactSlug(declaredModId, "mod");
            modrinthKnown = hit != null && hit.slug() != null && !hit.slug().isBlank();
            if (modrinthKnown) {
                List<ModrinthClient.ProjectVersion> compatible =
                        modrinth.compatibleVersionsLenient(hit.slug(), mcVersion);
                if (!compatible.isEmpty()) {
                    // Modrinth answers newest first, so the first usable build is the newest one.
                    ModrinthClient.ProjectVersion newest = compatible.get(0);
                    boolean upToDate = compatible.stream().anyMatch(
                            v -> ModrinthClient.versionFileMatches(v, installedFileName));
                    if (upToDate) return Resolution.upToDate(Source.MODRINTH, true, false);
                    ModrinthClient.FileRef jar = newest.firstJar();
                    if (jar == null || jar.url() == null || jar.url().isBlank()) {
                        modrinthProblem = "its newest Modrinth build for Minecraft " + mcVersion
                                + " ships no jar file";
                    } else {
                        return Resolution.replace(Source.MODRINTH, true, false, null, jar.filename(),
                                jar.url(), newest.versionNumber());
                    }
                } else {
                    // Known on Modrinth, but nothing published for this Minecraft version: the
                    // CurseForge fallback may still carry a build for it.
                    modrinthNoCompatibleBuild = true;
                    modrinthProblem = "Modrinth has no build for Minecraft " + mcVersion;
                }
            } else {
                modrinthProblem = "\"" + declaredModId + "\" isn't published on Modrinth";
            }
        } catch (Exception e) {
            // Offline / rate-limited: never claim "Modrinth has nothing" -- say what couldn't be
            // checked, and still let the CurseForge fallback try (it may be the difference between
            // fixing the mod now and not fixing it at all).
            modrinthProblem = "Modrinth couldn't be reached (" + shortReason(e) + ")";
            modrinthTransient = true;
        }

        // ---- CurseForge, keyless, and only with permission established from the mod's own license ----
        ModDistributionPolicy.Verdict verdict = ModDistributionPolicy.classify(licenseText);
        if (verdict != ModDistributionPolicy.Verdict.ALLOWED) {
            return Resolution.unavailable(modrinthKnown, modrinthNoCompatibleBuild,
                    modrinthTransient,
                    modrinthProblem + "; the CurseForge fallback isn't used because "
                            + ModDistributionPolicy.explain(verdict, licenseText));
        }

        CurseForgeFallback.Candidate candidate = curseForge.byModSlug(declaredModId, mcVersion, loader);
        if (candidate == null) {
            return Resolution.unavailable(modrinthKnown, modrinthNoCompatibleBuild,
                    modrinthTransient,
                    modrinthProblem + "; the keyless CurseForge lookup couldn't resolve \""
                            + declaredModId + "\" for Minecraft " + mcVersion + " (" + loader + ")");
        }
        if (candidate.fileName().equalsIgnoreCase(installedFileName)) {
            return Resolution.upToDate(Source.CURSEFORGE, modrinthKnown, modrinthNoCompatibleBuild);
        }
        return Resolution.replace(Source.CURSEFORGE, modrinthKnown, modrinthNoCompatibleBuild, candidate,
                candidate.fileName(), candidate.url(), candidate.versionLabel());
    }

    /** One short clause out of an exception, for the log line. */
    private static String shortReason(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) return e.getClass().getSimpleName();
        return msg.length() > 80 ? msg.substring(0, 77) + "..." : msg;
    }
}