package com.deylauncher.modrepair;

import com.deylauncher.modpack.CurseForgeFallback;
import com.deylauncher.server.ModrinthClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The resolution order the Mods window's autofix/checker depends on:
 * <b>Modrinth first, then the key-less CurseForge fallback, and only when the mod's own license
 * establishes permission</b>. Where neither source can serve the mod, the answer must say so in plain
 * language instead of quietly doing nothing.
 */
class InstalledModResolverTest {

    /** A Modrinth client that answers from memory, or fails the way an offline launcher would. */
    private static class StubModrinth extends ModrinthClient {
        private final ModrinthClient.Hit hit;
        private final List<ModrinthClient.ProjectVersion> versions;
        private final boolean offline;

        StubModrinth(ModrinthClient.Hit hit, List<ModrinthClient.ProjectVersion> versions, boolean offline) {
            this.hit = hit;
            this.versions = versions;
            this.offline = offline;
        }

        @Override
        public ModrinthClient.Hit projectByExactSlug(String slug, String projectType) {
            if (offline) throw new RuntimeException("offline");
            return hit;
        }

        @Override
        public List<ModrinthClient.ProjectVersion> compatibleVersionsLenient(String slug, String mcVersion) {
            if (offline) throw new RuntimeException("offline");
            return versions;
        }
    }

    private static ModrinthClient.ProjectVersion version(String number, String fileName) {
        return new ModrinthClient.ProjectVersion("v" + number, number, number, List.of("1.20.1"), List.of("Forge"),
                List.of(new ModrinthClient.FileRef("https://cdn.modrinth.com/data/x/versions/" + number
                        + "/" + fileName, fileName, 10)));
    }

    private static ModrinthClient.Hit knownProject() {
        return new ModrinthClient.Hit("examplemod", "Example Mod", "author", 1, "", "");
    }

    /** A CurseForge fallback whose service returns the given answer (or nothing at all). */
    private static CurseForgeFallback fallback(String answer, AtomicInteger calls) {
        return new CurseForgeFallback(null, url -> {
            if (calls != null) calls.incrementAndGet();
            return answer;
        });
    }

    private static String cfProject(String fileName) {
        return "{\"id\":42,\"title\":\"Example Mod\",\"files\":[{\"id\":777,\"name\":\"" + fileName
                + "\",\"filesize\":5,\"type\":\"release\",\"uploaded_at\":\"2025-01-01T00:00:00Z\","
                + "\"versions\":[\"1.20.1\",\"Client\",\"Forge\",\"Server\"]}]}";
    }

    @Test
    void modrinthIsPrimaryAndAlreadyCurrentModsAreLeftAlone() {
        var resolver = new InstalledModResolver(
                new StubModrinth(knownProject(), List.of(version("1.2.0", "examplemod-1.2.0.jar")), false),
                fallback(cfProject("should-never-be-used.jar"), null));

        var r = resolver.resolve("examplemod", "examplemod-1.2.0.jar", "1.20.1", "Forge", "MIT");

        assertTrue(r.upToDate());
        assertFalse(r.needsReplacement());
        assertEquals(InstalledModResolver.Source.MODRINTH, r.source());
        assertNull(r.note());
    }

    @Test
    void modrinthAnswersWithANewerBuildWhenTheInstalledOneIsNotIt() {
        var resolver = new InstalledModResolver(
                new StubModrinth(knownProject(), List.of(version("1.3.0", "examplemod-1.3.0.jar")), false),
                fallback(null, null));

        var r = resolver.resolve("examplemod", "examplemod-1.0.0.jar", "1.20.1", "Forge", "MIT");

        assertEquals(InstalledModResolver.Source.MODRINTH, r.source());
        assertTrue(r.needsReplacement());
        assertTrue(r.canFix());
        assertEquals("examplemod-1.3.0.jar", r.targetFileName());
        assertTrue(r.downloadUrl().contains("modrinth"));
    }

    @Test
    void curseForgeIsTheFallbackWhenModrinthDoesNotKnowTheModAndTheLicensePermitsIt() {
        var resolver = new InstalledModResolver(
                new StubModrinth(null, List.of(), false),
                fallback(cfProject("examplemod-1.3.0.jar"), null));

        var r = resolver.resolve("examplemod", "examplemod-1.0.0.jar", "1.20.1", "Forge", "MIT");

        assertEquals(InstalledModResolver.Source.CURSEFORGE, r.source());
        assertTrue(r.needsReplacement());
        assertTrue(r.canFix());
        assertNotNull(r.curseForgeCandidate(),
                "the candidate must travel with the plan, so it can be fetched and size-checked");
        assertTrue(r.downloadUrl().startsWith("https://edge.forgecdn.net/"));
    }

    @Test
    void anAllRightsReservedModIsNeverFetchedFromCurseForge() {
        AtomicInteger cfCalls = new AtomicInteger();
        var resolver = new InstalledModResolver(
                new StubModrinth(null, List.of(), false),
                fallback(cfProject("examplemod-1.3.0.jar"), cfCalls));

        var r = resolver.resolve("examplemod", "examplemod-1.0.0.jar", "1.20.1", "Forge",
                "All Rights Reserved");

        assertEquals(InstalledModResolver.Source.NONE, r.source());
        assertFalse(r.canFix());
        assertEquals(0, cfCalls.get(),
                "a mod that reserves its rights must not even be looked up on the fallback source");
        assertTrue(r.note().contains("does not allow redistribution"), r.note());
    }

    @Test
    void aModWithNoStatedLicenseFailsClosed() {
        AtomicInteger cfCalls = new AtomicInteger();
        var resolver = new InstalledModResolver(
                new StubModrinth(null, List.of(), false),
                fallback(cfProject("examplemod-1.3.0.jar"), cfCalls));

        var r = resolver.resolve("examplemod", "examplemod-1.0.0.jar", "1.20.1", "Forge", null);

        assertEquals(InstalledModResolver.Source.NONE, r.source());
        assertEquals(0, cfCalls.get(), "no license means no permission, so the fallback is never consulted");
        assertTrue(r.note().contains("no license"), r.note());
    }

    @Test
    void whenCurseForgeCannotResolveTheModTheReasonIsReported() {
        var resolver = new InstalledModResolver(
                new StubModrinth(null, List.of(), false),
                fallback(null, null));

        var r = resolver.resolve("examplemod", "examplemod-1.0.0.jar", "1.20.1", "Forge", "MIT");

        assertEquals(InstalledModResolver.Source.NONE, r.source());
        assertFalse(r.modrinthKnown());
        assertTrue(r.note().contains("isn't published on Modrinth"), r.note());
        assertTrue(r.note().contains("couldn't resolve"), r.note());
    }

    @Test
    void aModrinthProjectWithNoBuildForThisVersionStillFallsBackToCurseForge() {
        var resolver = new InstalledModResolver(
                new StubModrinth(knownProject(), new ArrayList<>(), false),
                fallback(cfProject("examplemod-1.3.0.jar"), null));

        var r = resolver.resolve("examplemod", "examplemod-1.0.0.jar", "1.20.1", "Forge", "MIT");

        assertEquals(InstalledModResolver.Source.CURSEFORGE, r.source());
        assertTrue(r.needsReplacement());
        assertTrue(r.noModrinthBuildForThisVersion(), "Modrinth genuinely has no build for this MC version");
    }

    @Test
    void aModrinthProjectWithNoBuildAndNoFallbackKeepsTodaysBehaviour() {
        var resolver = new InstalledModResolver(
                new StubModrinth(knownProject(), new ArrayList<>(), false),
                fallback(null, null));

        var r = resolver.resolve("examplemod", "examplemod-1.0.0.jar", "1.20.1", "Forge", "MIT");

        assertEquals(InstalledModResolver.Source.NONE, r.source());
        assertTrue(r.noModrinthBuildForThisVersion(),
                "the caller still needs this flag to keep reporting/removing it exactly as before");
        assertFalse(r.transientFailure());
    }

    @Test
    void anOfflineModrinthStillLetsCurseForgeFixTheMod() {
        var resolver = new InstalledModResolver(
                new StubModrinth(null, List.of(), true),
                fallback(cfProject("examplemod-1.3.0.jar"), null));

        var r = resolver.resolve("examplemod", "examplemod-1.0.0.jar", "1.20.1", "Forge", "MIT");

        assertEquals(InstalledModResolver.Source.CURSEFORGE, r.source());
        assertTrue(r.canFix());
    }

    @Test
    void anOfflineEverythingIsReportedAsWorthRetrying() {
        var resolver = new InstalledModResolver(new StubModrinth(null, List.of(), true), fallback(null, null));

        var r = resolver.resolve("examplemod", "examplemod-1.0.0.jar", "1.20.1", "Forge", "MIT");

        assertEquals(InstalledModResolver.Source.NONE, r.source());
        assertTrue(r.transientFailure(), "so the window can say \"run Fix again in a minute\"");
        assertTrue(r.note().contains("couldn't be reached"), r.note());
    }

    @Test
    void aJarWithoutADeclaredModIdIsNeverGuessedAt() {
        var resolver = new InstalledModResolver(
                new StubModrinth(knownProject(), List.of(version("1.3.0", "examplemod-1.3.0.jar")), false),
                fallback(cfProject("examplemod-1.3.0.jar"), null));

        var r = resolver.resolve(null, "mystery.jar", "1.20.1", "Forge", "MIT");

        assertEquals(InstalledModResolver.Source.NONE, r.source());
        assertFalse(r.canFix());
        assertTrue(r.note().contains("doesn't declare a mod id"), r.note());
    }
}
