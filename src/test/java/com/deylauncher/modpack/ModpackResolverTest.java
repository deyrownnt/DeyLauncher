package com.deylauncher.modpack;

import com.deylauncher.server.ModrinthClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The resolver is what turns a CurseForge pack's id-only file list into real downloads -- i.e. what
 * makes "the launcher fetches the pack's mods for me" true instead of "go and download 200 jars".
 */
class ModpackResolverTest {

    /** A resolver whose Modrinth fallback is disabled, so nothing here can reach the network. */
    private static class NoFallback extends ModpackResolver {
        NoFallback(CurseForgeFiles cf) {
            super(cf, new ModrinthClient());
        }

        @Override
        ModpackFile fromModrinth(CurseForgeEntry entry, String mcVersion, String loader) {
            return null;
        }
    }

    /** A resolver whose Modrinth fallback answers with a canned file. */
    private static class StubFallback extends ModpackResolver {
        private final ModpackFile answer;

        StubFallback(CurseForgeFiles cf, ModpackFile answer) {
            super(cf, new ModrinthClient());
            this.answer = answer;
        }

        @Override
        ModpackFile fromModrinth(CurseForgeEntry entry, String mcVersion, String loader) {
            return answer;
        }
    }

    @Test
    void resolvesEveryDeclaredFileIntoTheModsFolder(@TempDir Path pack) throws Exception {
        Files.writeString(pack.resolve("manifest.json"), """
                {"minecraft":{"version":"1.20.1","modLoaders":[{"id":"forge-47.4.20","primary":true}]},
                 "name":"CF Pack","version":"1.0",
                 "files":[{"projectID":238222,"fileID":8807823,"required":true},
                          {"projectID":306612,"fileID":8882089,"required":true}]}""");
        Files.createDirectories(pack.resolve("overrides/config"));
        Files.writeString(pack.resolve("overrides/config/pack.cfg"), "cfg");

        CurseForgeFiles cf = new CurseForgeFiles(null, url -> url.contains("306612")
                ? "{\"data\":{\"fileName\":\"fabric-api-0.92.0+1.20.1.jar\",\"fileLength\":2000,"
                  + "\"gameVersions\":[\"Client\",\"Fabric\",\"Server\",\"1.20.1\"]}}"
                : "{\"data\":{\"fileName\":\"jei-1.20.1-forge-15.57.0.207.jar\",\"fileLength\":1820418,"
                  + "\"gameVersions\":[\"Client\",\"1.20.1\",\"Forge\",\"Server\"]}}");

        ModpackInfo info = ModpackReader.read(pack);
        ModpackResolver.Outcome outcome = new NoFallback(cf).resolve(info, "1.20.1", "Forge", null, null);

        assertEquals(2, outcome.resolved());
        assertEquals(0, outcome.unresolved());
        List<String> paths = outcome.info().downloads().stream().map(ModpackFile::path).toList();
        assertTrue(paths.contains("mods/jei-1.20.1-forge-15.57.0.207.jar"), "got " + paths);
        assertTrue(paths.contains("mods/fabric-api-0.92.0+1.20.1.jar"), "got " + paths);

        ModpackFile jei = outcome.info().downloads().stream()
                .filter(f -> f.path().endsWith("jei-1.20.1-forge-15.57.0.207.jar")).findFirst().orElseThrow();
        assertEquals("https://edge.forgecdn.net/files/8807/823/jei-1.20.1-forge-15.57.0.207.jar", jei.url());
        assertEquals(1820418, jei.sizeBytes());
        assertTrue(jei.clientRequired());
        assertTrue(jei.downloadable());
        assertEquals(1, outcome.info().bundled().size(), "the pack's overrides are untouched");
    }

    @Test
    void aFileNobodyServesIsReportedByNameInsteadOfBeingSkippedSilently(@TempDir Path pack) throws Exception {
        Files.writeString(pack.resolve("manifest.json"), """
                {"minecraft":{"version":"1.20.1"},
                 "files":[{"projectID":999,"fileID":111,"required":true}]}""");
        Files.writeString(pack.resolve("modlist.html"),
                "<ul><li><a href=\"https://www.curseforge.com/minecraft/mc-mods/gone\">A Deleted Mod</a></li></ul>");

        ModpackInfo info = ModpackReader.read(pack);
        ModpackResolver.Outcome outcome = new NoFallback(new CurseForgeFiles(null, url -> "{\"data\":null}"))
                .resolve(info, "1.20.1", "Forge", null, null);

        assertEquals(0, outcome.resolved());
        assertEquals(1, outcome.unresolved());
        assertEquals(1, outcome.unresolvedNames().size());
        assertTrue(outcome.unresolvedNames().get(0).contains("A Deleted Mod"));
        assertTrue(outcome.info().note().contains("A Deleted Mod"),
                "the pack's note must name what is missing, so the UI can show it");
    }

    @Test
    void theModrinthFallbackIsUsedWhenTheIdLookupFails(@TempDir Path pack) throws Exception {
        Files.writeString(pack.resolve("manifest.json"), """
                {"minecraft":{"version":"1.20.1"},
                 "files":[{"projectID":999,"fileID":111,"required":true}]}""");
        Files.writeString(pack.resolve("modlist.html"),
                "<ul><li><a href=\"https://www.curseforge.com/minecraft/mc-mods/moved\">Moved Mod</a></li></ul>");

        ModpackFile fallback = ModpackFile.download("mods/moved-mod-1.4.jar",
                "https://cdn.modrinth.com/data/abc/versions/1/moved-mod-1.4.jar", null, null, 900,
                true, false, true, false);
        ModpackResolver.Outcome outcome = new StubFallback(new CurseForgeFiles(null, url -> "{\"data\":null}"),
                fallback).resolve(ModpackReader.read(pack), "1.20.1", "Forge", null, null);

        assertEquals(1, outcome.resolved());
        assertEquals("mods/moved-mod-1.4.jar", outcome.info().downloads().get(0).path());
    }

    @Test
    void aModrinthPackIsLeftExactlyAsThePackDeclaredIt() {
        ModpackInfo info = new ModpackInfo(ModpackFormat.MODRINTH_MRPACK, "P", "1", "1.20.1",
                "Fabric", "0.15.11",
                List.of(ModpackFile.download("mods/a.jar", "https://example.invalid/a.jar", null, null, 1,
                        true, false, true, false)),
                List.of(), 0, null, Path.of("/tmp/p.mrpack"), null);

        ModpackResolver.Outcome outcome = new NoFallback(new CurseForgeFiles(null, url -> null))
                .resolve(info, "1.20.1", "Fabric", null, null);

        assertEquals(0, outcome.resolved());
        assertEquals(1, outcome.info().downloads().size());
        assertEquals("mods/a.jar", outcome.info().downloads().get(0).path());
    }

    @Test
    void foldersAreChosenFromWhatTheFileActuallyIs() {
        assertEquals("mods/", ModpackResolver.folderFor("jei-1.20.1-forge-15.57.0.207.jar"));
        assertEquals("shaderpacks/", ModpackResolver.folderFor("ComplementaryShaders_v4.7.zip"));
        assertEquals("resourcepacks/", ModpackResolver.folderFor("Faithful 32x.zip"));
    }

    @Test
    void theModrinthFallbackOnlyTrustsAnExactNameMatch() {
        var exact = new ModrinthClient.Hit("jei", "Just Enough Items (JEI)", "mezz", 1, "", "");
        var near = new ModrinthClient.Hit("jei-something", "Just Enough Items Extra", "someone", 1, "", "");

        assertTrue(ModpackResolver.trustedNameMatch("Just Enough Items (JEI)", exact));
        assertTrue(ModpackResolver.trustedNameMatch("  just enough items (jei) ", exact));
        assertFalse(ModpackResolver.trustedNameMatch("Just Enough Items (JEI)", near),
                "a near match must never be installed into someone's pack");
        assertNull(ModpackResolver.pickVersion(List.of(), "Forge"));
    }

    @Test
    void versionsArePickedForBothTheGameVersionAndTheLoader() {
        var forge = new ModrinthClient.ProjectVersion("1", "mod", "1.0", List.of("1.20.1"), List.of("Forge"),
                List.of(new ModrinthClient.FileRef("https://example.invalid/forge.jar", "forge.jar", 1)));
        var fabric = new ModrinthClient.ProjectVersion("2", "mod", "2.0", List.of("1.20.1"), List.of("Fabric"),
                List.of(new ModrinthClient.FileRef("https://example.invalid/fabric.jar", "fabric.jar", 1)));

        assertNotNull(ModpackResolver.pickVersion(List.of(fabric, forge), "Forge"));
        assertEquals("1.0", ModpackResolver.pickVersion(List.of(fabric, forge), "Forge").versionNumber(),
                "newest usable build wins");
        assertNull(ModpackResolver.pickVersion(List.of(fabric), "Forge"),
                "a Fabric build must never be installed on a Forge instance");
    }
}