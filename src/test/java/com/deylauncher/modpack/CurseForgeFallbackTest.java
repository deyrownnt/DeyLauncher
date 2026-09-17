package com.deylauncher.modpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The key-less CurseForge fallback: what it does (an exact slug + Minecraft version + loader in, the
 * matching published file out) and -- just as important -- everything it refuses to do (fuzzy matches,
 * wrong loaders, nothing-but-alphas, unreadable answers). Every case here runs offline.
 */
class CurseForgeFallbackTest {

    /** A project answer shaped exactly like api.cfwidget.com's, with two usable builds plus a trap. */
    private static String project(String... files) {
        return "{\"id\":394468,\"title\":\"Sodium\",\"license\":\"\",\"files\":[" + String.join(",", files) + "]}";
    }

    private static String file(long id, String name, String type, String uploadedAt, String... tags) {
        StringBuilder t = new StringBuilder();
        for (String tag : tags) {
            if (t.length() > 0) t.append(",");
            t.append('"').append(tag).append('"');
        }
        return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"filesize\":1234,\"type\":\"" + type
                + "\",\"uploaded_at\":\"" + uploadedAt + "\",\"versions\":[" + t + "]}";
    }

    @Test
    void picksTheNewestBuildForTheWantedMinecraftVersionAndLoader() {
        String body = project(
                file(1, "sodium-0.5.0-fabric-1.20.1.jar", "release", "2024-01-01T00:00:00Z",
                        "1.20.1", "Client", "Fabric", "Server"),
                file(2, "sodium-0.6.0-fabric-1.20.1.jar", "release", "2025-01-01T00:00:00Z",
                        "1.20.1", "Client", "Fabric", "Server"),
                file(3, "sodium-0.6.0-forge-1.20.1.jar", "release", "2025-06-01T00:00:00Z",
                        "1.20.1", "Client", "Forge", "Server"),
                file(4, "sodium-0.6.0-fabric-1.21.1.jar", "release", "2025-07-01T00:00:00Z",
                        "1.21.1", "Client", "Fabric", "Server"));

        CurseForgeFallback.Candidate c = CurseForgeFallback.parse(body, "1.20.1", "Fabric");

        assertNotNull(c);
        assertEquals(2, c.fileId(), "the newest Fabric build for 1.20.1 wins -- not the Forge one, not 1.21.1");
        assertEquals("sodium-0.6.0-fabric-1.20.1.jar", c.fileName());
        assertEquals("https://edge.forgecdn.net/files/0/002/sodium-0.6.0-fabric-1.20.1.jar", c.url(),
                "the download URL comes from the same keyless CDN builder the CurseForge pack path uses");
        assertTrue(c.clientOk());
        assertTrue(c.serverOk());
    }

    @Test
    void aReleaseAlwaysBeatsANewerBeta() {
        String body = project(
                file(10, "mod-1.2.0.jar", "beta", "2025-09-01T00:00:00Z", "1.20.1", "Forge", "Client"),
                file(11, "mod-1.1.0.jar", "release", "2025-01-01T00:00:00Z", "1.20.1", "Forge", "Client"));

        CurseForgeFallback.Candidate c = CurseForgeFallback.parse(body, "1.20.1", "Forge");

        assertNotNull(c);
        assertEquals(11, c.fileId());
    }

    @Test
    void aBuildForAnotherLoaderIsNeverUsed() {
        String body = project(
                file(20, "mod-neoforge.jar", "release", "2025-01-01T00:00:00Z", "1.20.1", "NeoForge", "Client"));

        assertNull(CurseForgeFallback.parse(body, "1.20.1", "Forge"),
                "a NeoForge-only build must never be installed onto a Forge instance");
        assertNotNull(CurseForgeFallback.parse(body, "1.20.1", "NeoForge"));
    }

    @Test
    void anAlphaIsOnlyUsedWhenNothingElseExists() {
        String alphaOnly = project(
                file(30, "mod-alpha.jar", "alpha", "2025-01-01T00:00:00Z", "1.20.1", "Fabric", "Client"));
        assertNotNull(CurseForgeFallback.parse(alphaOnly, "1.20.1", "Fabric"));

        String alphaAndBeta = project(
                file(31, "mod-alpha.jar", "alpha", "2025-09-01T00:00:00Z", "1.20.1", "Fabric", "Client"),
                file(32, "mod-beta.jar", "beta", "2025-01-01T00:00:00Z", "1.20.1", "Fabric", "Client"));
        assertEquals(32, CurseForgeFallback.parse(alphaAndBeta, "1.20.1", "Fabric").fileId());
    }

    @Test
    void aNonJarOrUnusableAnswerIsTreatedAsUnresolved() {
        assertNull(CurseForgeFallback.parse("", "1.20.1", "Forge"));
        assertNull(CurseForgeFallback.parse("<html>blocked</html>", "1.20.1", "Forge"));
        assertNull(CurseForgeFallback.parse("{\"data\":null}", "1.20.1", "Forge"));
        assertNull(CurseForgeFallback.parse(project(
                        file(40, "mod-resourcepack.zip", "release", "2025-01-01T00:00:00Z", "1.20.1", "Forge")),
                "1.20.1", "Forge"),
                "only a .jar is ever treated as a mod");
    }

    @Test
    void theFamilyMatchAcceptsAPatchLevelDifference() {
        String body = project(
                file(50, "mod-1.21.jar", "release", "2025-01-01T00:00:00Z", "1.21", "Fabric", "Client"));

        assertNotNull(CurseForgeFallback.parse(body, "1.21.1", "Fabric"),
                "a project tagged only 1.21 still covers a 1.21.1 instance");
    }

    @Test
    void theLookupIsExactSlugOnlyAndCached(@TempDir Path dir) {
        AtomicInteger calls = new AtomicInteger();
        Map<String, String> answers = new LinkedHashMap<>();
        answers.put("https://api.cfwidget.com/minecraft/mc-mods/jei", project(
                file(60, "jei-1.20.1-forge.jar", "release", "2025-01-01T00:00:00Z",
                        "1.20.1", "Forge", "Client", "Server")));
        Function<String, String> fetcher = url -> {
            calls.incrementAndGet();
            return answers.get(url);
        };
        Path cache = dir.resolve("modpacks").resolve("cf-widget.json");

        CurseForgeFallback cf = new CurseForgeFallback(cache, fetcher);
        assertNotNull(cf.byModSlug("jei", "1.20.1", "Forge"));
        assertEquals(1, calls.get());

        assertNotNull(cf.byModSlug("jei", "1.20.1", "Forge"),
                "a repeat lookup inside one run must not hit the service again");
        assertEquals(1, calls.get());

        // A failing service must still resolve what this launcher already learned, from its cache.
        CurseForgeFallback afterRestart = new CurseForgeFallback(cache, url -> null);
        assertNotNull(afterRestart.byModSlug("jei", "1.20.1", "Forge"));
        assertTrue(Files.isRegularFile(cache), "the answer is cached next to the other launcher data");
    }

    @Test
    void anUnknownProjectIsReportedAsUnresolvedRatherThanGuessed() {
        CurseForgeFallback cf = new CurseForgeFallback(null, url -> null);
        assertNull(cf.byModSlug("does-not-exist", "1.20.1", "Forge"));
        assertNull(cf.byModSlug("", "1.20.1", "Forge"), "an empty slug is never requested");
        assertNull(cf.byModSlug("jei", "", "Forge"), "no Minecraft version means nothing to match");
    }
}
