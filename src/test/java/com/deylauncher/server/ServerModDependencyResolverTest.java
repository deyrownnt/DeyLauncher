package com.deylauncher.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server half of the dependency problem: a modpack installed onto a server carries only the files
 * the pack ships, while its mods declare required library mods the server must already have. These
 * tests pin both the metadata reading and the whole offline install, including a transitive
 * dependency (a dependency that itself needs another mod).
 */
class ServerModDependencyResolverTest {

    private static final String ANTIQUE_ATLAS = """
            {"schemaVersion":1,"id":"antique_atlas","name":"Antique Atlas","version":"1.0.0",
             "depends":{"minecraft":">=1.20","fabricloader":">=0.14","fabric":"*",
                        "fabric-api-base":"*","moonlight":">=2.0","java":">=17"}}
            """;

    private static final String MOONLIGHT = """
            {"schemaVersion":1,"id":"moonlight","name":"Moonlight","version":"2.16.34",
             "depends":{"minecraft":"1.20.1","fabric":"*"}}
            """;

    private static final String FABRIC_API = """
            {"schemaVersion":1,"id":"fabric","name":"Fabric API","version":"0.92.2",
             "depends":{"minecraft":"1.20.1"}}
            """;

    /**
     * Only real mods are read out of {@code depends}: the game, the loader and Fabric API's own
     * sub-modules are not separately installable projects, while {@code fabric} (Fabric API's mod id)
     * and the actual library mods are kept.
     */
    @Test
    void onlyRealModDependenciesAreCollected() {
        ServerModDependencyResolver.ModInfo info = ServerModDependencyResolver.readModInfo(ANTIQUE_ATLAS);

        assertNotNull(info);
        assertTrue(info.ids().contains("antique_atlas"), "the jar's own id identifies it");

        List<String> depIds = info.deps().stream()
                .map(ServerModDependencyResolver.Dependency::id).toList();
        assertEquals(List.of("fabric", "moonlight"), depIds,
                "minecraft/fabricloader/java/fabric-api-* are not mods; fabric and moonlight are");
    }

    /** Fabric's metadata allows an array of alternative ranges, and {@code provides} aliases an id. */
    @Test
    void arrayRangesAndProvidesAreHandled() {
        ServerModDependencyResolver.ModInfo info = ServerModDependencyResolver.readModInfo("""
                {"id":"tab_api","provides":["tabapi"],
                 "depends":{"moonlight":["*",">=1.0"]}}
                """);

        assertNotNull(info);
        assertTrue(info.ids().contains("tab_api"));
        assertTrue(info.ids().contains("tabapi"), "a provides entry counts as an installed id");
        assertEquals(1, info.deps().size());
        assertEquals("*", info.deps().get(0).versionRange(), "the first usable range of the array wins");
    }

    /** Unreadable metadata is ignored rather than crashing an install. */
    @Test
    void unreadableMetadataIsIgnored() {
        assertNull(ServerModDependencyResolver.readModInfo(null));
        assertNull(ServerModDependencyResolver.readModInfo("   "));
        assertNull(ServerModDependencyResolver.readModInfo("not json at all"));
        assertNull(ServerModDependencyResolver.readModInfo("[]"), "an array is not a mod object");
    }

    /** No display of a missing Fabric dependency is possible for a loader with no mods. */
    @Test
    void aNonFabricServerIsANoOp(@TempDir Path serverDir) throws Exception {
        writeModJar(serverDir.resolve("mods"), "antique_atlas.jar", ANTIQUE_ATLAS);

        ServerModDependencyResolver.Result result = new ServerModDependencyResolver(new FakeModrinth(Map.of()))
                .resolveAndInstall(serverDir, "1.20.1", "Forge", null, null);

        assertEquals(0, result.scannedMods(), "Forge describes deps in mods.toml, which we don't guess at");
        assertEquals(0, result.missingDepsFound());
    }

    /** Everything already present means no work and no network requests. */
    @Test
    void anAlreadySatisfiedDependencyIsLeftAlone(@TempDir Path serverDir) throws Exception {
        Path mods = serverDir.resolve("mods");
        writeModJar(mods, "antique_atlas.jar", ANTIQUE_ATLAS);
        writeModJar(mods, "moonlight.jar", MOONLIGHT);
        writeModJar(mods, "fabric-api.jar", FABRIC_API);

        ServerModDependencyResolver.Result result = new ServerModDependencyResolver(new FakeModrinth(Map.of()))
                .resolveAndInstall(serverDir, "1.20.1", "Fabric", null, null);

        assertEquals(3, result.scannedMods());
        assertEquals(0, result.missingDepsFound(), "'fabric' is provided by fabric-api's own id");
        assertEquals(0, result.installed());
    }

    /** A jar that needs only {@code moonlight}, so moonlight's own {@code fabric} dep is transitive. */
    private static final String ATLAS_NEEDS_ONLY_MOONLIGHT = """
            {"id":"antique_atlas","depends":{"minecraft":">=1.20","moonlight":">=2.0"}}
            """;

    /**
     * The whole point: a server holding only {@code antique_atlas} gets {@code moonlight} installed,
     * and moonlight's OWN dependency ({@code fabric} -> the Fabric API project) is followed in the
     * next wave. Progress must also be reported, and must never run backwards.
     */
    @Test
    void missingAndTransitiveDependenciesAreInstalledAndReported(@TempDir Path serverDir) throws Exception {
        Path mods = serverDir.resolve("mods");
        writeModJar(mods, "antique_atlas.jar", ATLAS_NEEDS_ONLY_MOONLIGHT);

        FakeModrinth modrinth = new FakeModrinth(Map.of(
                "moonlight", MOONLIGHT,
                "fabric-api", FABRIC_API));
        List<Double> reported = new java.util.ArrayList<>();

        ServerModDependencyResolver.Result result = new ServerModDependencyResolver(modrinth)
                .resolveAndInstall(serverDir, "1.20.1", "Fabric", reported::add, null);

        assertEquals(1, result.scannedMods());
        assertEquals(1, result.missingDepsFound(), "only moonlight is named directly");
        assertEquals(2, result.resolved());
        assertEquals(2, result.installed());
        assertTrue(result.errors().isEmpty(), String.join(", ", result.errors()));
        assertTrue(result.warnings().isEmpty(), String.join(", ", result.warnings()));

        assertTrue(Files.isRegularFile(mods.resolve("moonlight.jar")), "the library mod must land in mods/");
        assertTrue(Files.isRegularFile(mods.resolve("fabric-api.jar")),
                "moonlight's own dependency must be fetched in the next wave");
        assertEquals(List.of("moonlight", "fabric-api"), modrinth.downloaded,
                "the direct dep first, then the one its metadata adds");

        assertTrue(reported.size() >= 2, "progress must be reported per dependency");
        for (int i = 1; i < reported.size(); i++) {
            assertTrue(reported.get(i) >= reported.get(i - 1),
                    "progress must never go backwards: " + reported);
        }
        assertTrue(reported.get(reported.size() - 1) <= 1.0, "progress stays within 0..1");
    }

    /**
     * A dependency that isn't on Modrinth is a WARNING, never an error and never a wrong download:
     * this is what keeps the resolver from silently installing a similarly named mod.
     */
    @Test
    void anUnresolvableDependencyIsAWarningNotAnError(@TempDir Path serverDir) throws Exception {
        Path mods = serverDir.resolve("mods");
        writeModJar(mods, "mystery.jar", """
                {"id":"mystery_mod","depends":{"definitely_not_on_modrinth":"*"}}
                """);

        ServerModDependencyResolver.Result result = new ServerModDependencyResolver(
                new FakeModrinth(Map.of())).resolveAndInstall(serverDir, "1.20.1", "Fabric", null, null);

        assertEquals(1, result.missingDepsFound());
        assertEquals(0, result.installed());
        assertTrue(result.errors().isEmpty(), "an unknown project is not an error");
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("definitely_not_on_modrinth"));
    }

    /**
     * The id a mod declares is not always the Modrinth slug of the project it means. Simply Swords
     * declares {@code simplytooltips} while the project's slug is {@code simply-tooltips}, and a raw id
     * lookup finds nothing -- which used to leave a whole server unable to start. The curated alias is
     * what bridges that, and it must keep working.
     */
    @Test
    void aKnownAliasBridgesTheDeclaredIdAndTheModrinthSlug(@TempDir Path serverDir) throws Exception {
        Path mods = serverDir.resolve("mods");
        writeModJar(mods, "simplyswords.jar", """
                {"id":"simplyswords","depends":{"simplytooltips":"*"}}
                """);

        FakeModrinth modrinth = new FakeModrinth(Map.of(
                "simply-tooltips", "{\"id\":\"simplytooltips\"}"));

        ServerModDependencyResolver.Result result = new ServerModDependencyResolver(modrinth)
                .resolveAndInstall(serverDir, "1.20.1", "Fabric", null, null);

        assertEquals(1, result.missingDepsFound());
        assertEquals(1, result.installed(), "the alias must resolve what the raw id could not");
        assertTrue(result.warnings().isEmpty(), String.join(", ", result.warnings()));
        assertTrue(result.setAsideMods().isEmpty(), "a resolved dependency must not cost a mod");
        assertTrue(Files.isRegularFile(mods.resolve("simply-tooltips.jar")));
        assertEquals(List.of("simply-tooltips"), modrinth.downloaded);
    }

    /**
     * A dependency with no build for this Minecraft version cannot be supplied from anywhere, and Fabric
     * refuses to boot the whole server over one missing hard dependency. The mod that needs it is moved
     * into {@code mods-disabled/} (never deleted), with a README, and the reason is reported -- leaving
     * it in place would mean nothing starts at all.
     */
    @Test
    void aModWhoseDependencyCannotBeSuppliedIsSetAsideNotLeftToCrash(@TempDir Path serverDir)
            throws Exception {
        Path mods = serverDir.resolve("mods");
        writeModJar(mods, "antiqueatlastweaks-1.0.0.jar", """
                {"id":"antique_atlas_item","depends":{"antique_atlas":">=2.11.2+1.20"}}
                """);
        writeModJar(mods, "keepme.jar", "{\"id\":\"keepme\"}");

        ServerModDependencyResolver.Result result = new ServerModDependencyResolver(new FakeModrinth(Map.of()))
                .resolveAndInstall(serverDir, "1.20.1", "Fabric", null, null);

        assertEquals(1, result.missingDepsFound());
        assertEquals(0, result.installed());
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("antique_atlas"));
        assertTrue(result.warnings().get(0).contains("no Modrinth project matches this id"),
                "the warning must say WHY, not just 'could not resolve': " + result.warnings().get(0));

        assertEquals(1, result.setAsideMods().size());
        assertTrue(result.setAsideMods().get(0).contains("antiqueatlastweaks-1.0.0.jar"));
        assertFalse(Files.exists(mods.resolve("antiqueatlastweaks-1.0.0.jar")), "moved, not deleted");
        assertTrue(Files.isRegularFile(serverDir.resolve("mods-disabled/antiqueatlastweaks-1.0.0.jar")));
        assertTrue(Files.isRegularFile(serverDir.resolve("mods-disabled/README.txt")),
                "the set-aside folder must explain itself");
        assertTrue(Files.isRegularFile(mods.resolve("keepme.jar")), "an unrelated mod is untouched");
    }

    /** "On Modrinth, but nothing for this Minecraft version" is reported as exactly that. */
    @Test
    void aProjectWithNoBuildForThisMinecraftVersionSaysSo(@TempDir Path serverDir) throws Exception {
        Path mods = serverDir.resolve("mods");
        writeModJar(mods, "aa_tweaks.jar", """
                {"id":"aa_tweaks","depends":{"moonlight":"*"}}
                """);

        ServerModDependencyResolver.Result result = new ServerModDependencyResolver(
                new NoBuildModrinth(Map.of("moonlight", MOONLIGHT)))
                .resolveAndInstall(serverDir, "1.20.1", "Fabric", null, null);

        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("no build for Minecraft 1.20.1"),
                result.warnings().get(0));
    }

    /**
     * Jar-in-jar: Fabric loads every jar under {@code META-INF/jars/} as a mod of its own, and mods ship
     * their libraries that way (Cardinal Components' modules, WunderLib inside BCLib). A resolver that
     * only read the outside of a jar reported those libraries as missing, which is what caused good
     * mods to be needlessly installed over and even moved aside.
     */
    @Test
    void aLibraryNestedInsideAnotherJarSatisfiesTheDependency(@TempDir Path serverDir) throws Exception {
        Path mods = serverDir.resolve("mods");
        writeJarWithNested(mods, "outer.jar", "{\"id\":\"outer\"}",
                "wunderlib-1.1.5.jar", "{\"id\":\"wunderlib\"}");
        writeModJar(mods, "needsit.jar", "{\"id\":\"needsit\",\"depends\":{\"wunderlib\":\"*\"}}");

        ServerModDependencyResolver.Result result = new ServerModDependencyResolver(new FakeModrinth(Map.of()))
                .resolveAndInstall(serverDir, "1.20.1", "Fabric", null, null);

        assertEquals(0, result.missingDepsFound(), "the nested library already provides it");
        assertEquals(0, result.installed());
        assertTrue(result.setAsideMods().isEmpty(), "nothing may be set aside for a present dependency");
        assertFalse(Files.exists(serverDir.resolve("mods-disabled")));
    }

    /** A nested library's own dependencies are followed too, since Fabric loads it as a mod. */
    @Test
    void aNestedLibrarysOwnDependencyIsResolved(@TempDir Path serverDir) throws Exception {
        Path mods = serverDir.resolve("mods");
        writeJarWithNested(mods, "outer.jar", "{\"id\":\"outer\"}",
                "cc-base.jar", "{\"id\":\"cardinal-components-base\",\"depends\":{\"moonlight\":\"*\"}}");

                // moonlight itself declares a "fabric" dependency (Fabric API); the resolver correctly demands
        // fabric-api for it, so the fake must serve it too (as a leaf mod whose id is fabric).
        ServerModDependencyResolver.Result result = new ServerModDependencyResolver(
                new FakeModrinth(Map.of("moonlight", MOONLIGHT,
                        "fabric-api", "{\"id\":\"fabric\"}")))
                .resolveAndInstall(serverDir, "1.20.1", "Fabric", null, null);

        assertEquals(1, result.missingDepsFound());
        assertEquals(2, result.installed(),
                "moonlight plus the fabric-api its own dependency demands");
        assertTrue(Files.isRegularFile(mods.resolve("moonlight.jar")));
        assertTrue(Files.isRegularFile(mods.resolve("fabric-api.jar")));
        assertTrue(result.warnings().isEmpty(), String.join(", ", result.warnings()));
    }

    /** A client-only mod never loads on a dedicated server, so Fabric never asks for its dependencies. */
    @Test
    void aClientOnlyModsDependenciesAreNotDemanded(@TempDir Path serverDir) throws Exception {
        Path mods = serverDir.resolve("mods");
        writeModJar(mods, "enchdesc.jar",
                "{\"id\":\"enchdesc\",\"environment\":\"client\",\"depends\":{\"clientsidelib\":\"*\"}}");

        ServerModDependencyResolver.Result result = new ServerModDependencyResolver(new FakeModrinth(Map.of()))
                .resolveAndInstall(serverDir, "1.20.1", "Fabric", null, null);

        assertEquals(0, result.missingDepsFound(), "a disabled client mod's deps are not server deps");
        assertTrue(result.setAsideMods().isEmpty());
    }

    /**
     * The guard that keeps a resolver mistake from wrecking a working modpack: a mod that fails on one
     * dependency but is the only provider of ids other mods require must be left where it is. Moving it
     * would turn one unusable mod into a cascade of missing dependencies.
     */
    @Test
    void aModThatProvidesIdsOthersNeedIsNeverSetAside(@TempDir Path serverDir) throws Exception {
        Path mods = serverDir.resolve("mods");
        // Provides the library others use, but itself needs something this version has no build for.
        writeModJar(mods, "sharedlib.jar",
                "{\"id\":\"sharedlib\",\"depends\":{\"missing_build_lib\":\"*\"}}");
        writeModJar(mods, "user.jar", "{\"id\":\"user\",\"depends\":{\"sharedlib\":\"*\"}}");

        ServerModDependencyResolver.Result result = new ServerModDependencyResolver(new FakeModrinth(Map.of()))
                .resolveAndInstall(serverDir, "1.20.1", "Fabric", null, null);

        assertTrue(result.setAsideMods().isEmpty(), "a library other mods depend on must not be moved");
        assertTrue(Files.isRegularFile(mods.resolve("sharedlib.jar")), "left in place");
        assertFalse(Files.exists(serverDir.resolve("mods-disabled")), "no empty folder is created");
    }

    /**
     * A Modrinth client that never touches the network: it knows exactly two projects, answers every
     * version query for the requested Minecraft version, and writes real (tiny) jars carrying the
     * metadata the map supplies -- so transitive resolution is exercised for real.
     */
    private static class FakeModrinth extends ModrinthClient {

        private final Map<String, String> fabricJsonBySlug;
        private final List<String> downloaded = new java.util.ArrayList<>();

        FakeModrinth(Map<String, String> fabricJsonBySlug) {
            this.fabricJsonBySlug = fabricJsonBySlug;
        }

        @Override
        public Hit projectByExactSlug(String slug, String projectType) {
            return fabricJsonBySlug.containsKey(slug)
                    ? new Hit(slug, slug, "", 0, "", null)
                    : null;
        }

        @Override
        public Hit firstHitByName(String name, String projectType) {
            return null; // never act on a fuzzy match
        }

        @Override
        public List<ProjectVersion> compatibleVersionsLenient(String slug, String mcVersion) {
            return List.of(new ProjectVersion(slug + "-version", slug, "1.0.0",
                    List.of(mcVersion), List.of("fabric"),
                    List.of(new FileRef("https://example.invalid/" + slug + ".jar", slug + ".jar", 3))));
        }

        @Override
        public Path download(String url, String filename, Path targetDir) throws Exception {
            downloaded.add(filename.endsWith(".jar")
                    ? filename.substring(0, filename.length() - 4) : filename);
            String json = fabricJsonBySlug.get(downloaded.get(downloaded.size() - 1));
            writeModJar(targetDir, filename, json == null ? "{\"id\":\"stand_in\"}" : json);
            return targetDir.resolve(filename);
        }
    }

    /** A client whose project lookups work but which has published nothing for this Minecraft version. */
    private static final class NoBuildModrinth extends FakeModrinth {

        NoBuildModrinth(Map<String, String> fabricJsonBySlug) {
            super(fabricJsonBySlug);
        }

        @Override
        public List<ProjectVersion> compatibleVersionsLenient(String slug, String mcVersion) {
            return List.of();
        }
    }

    /** Writes a real jar (zip) whose only entry is the given {@code fabric.mod.json}. */
    private static void writeModJar(Path dir, String fileName, String fabricJson) throws Exception {
        Files.createDirectories(dir);
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(dir.resolve(fileName)))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("fabric.mod.json"));
            zip.write(fabricJson.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    /**
     * Writes a jar that declares {@code topJson} outside and nests a second mod (with
     * {@code nestedJson}) under {@code META-INF/jars/} -- how mods ship their libraries.
     */
    private static void writeJarWithNested(Path dir, String fileName, String topJson,
                                          String nestedFileName, String nestedJson) throws Exception {
        Files.createDirectories(dir);
        byte[] nested;
        try (var bos = new java.io.ByteArrayOutputStream();
             var zip = new java.util.zip.ZipOutputStream(bos)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("fabric.mod.json"));
            zip.write(nestedJson.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            nested = bos.toByteArray();
        }
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(dir.resolve(fileName)))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("fabric.mod.json"));
            zip.write(topJson.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("META-INF/jars/" + nestedFileName));
            zip.write(nested);
            zip.closeEntry();
        }
    }
}