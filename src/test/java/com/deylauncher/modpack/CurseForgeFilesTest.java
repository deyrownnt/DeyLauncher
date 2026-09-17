package com.deylauncher.modpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the thing that makes a CurseForge pack installable without an API key: turning a
 * {@code projectID}/{@code fileID} pair into a real file name + CDN URL, and the CDN layout that goes
 * with it (both verified against the live service by hand -- see {@link CurseForgeFiles}' doc).
 */
class CurseForgeFilesTest {

    private static final String JEI = """
            {"data":{"id":8807823,"fileName":"jei-1.20.1-forge-15.57.0.207.jar","fileLength":1820418,
             "gameVersions":["Client","1.20.1","Forge","Server"]}}""";

    /** A mod that only ships a server jar: the env split comes straight from gameVersions. */
    private static final String SERVER_ONLY = """
            {"data":{"id":8811156,"fileName":"servermod-1.0.jar","fileLength":1234,
             "gameVersions":["1.20.1","Forge","Server"]}}""";

    @Test
    void cdnUrlUsesTheBucketAndThreeDigitRemainder() {
        assertEquals("https://edge.forgecdn.net/files/8807/823/jei-1.20.1-forge-15.57.0.207.jar",
                CurseForgeFiles.cdnUrl(8807823, "jei-1.20.1-forge-15.57.0.207.jar"));
        // The remainder is zero-padded ("089", not "89") and the name is percent-encoded -- both are
        // required, and both were wrong in the first cut of this URL.
        assertEquals("https://edge.forgecdn.net/files/8882/089/fabric-api-0.160.5%2B26.3.jar",
                CurseForgeFiles.cdnUrl(8882089, "fabric-api-0.160.5+26.3.jar"));
    }

    @Test
    void resolvesTheFileNameSizeAndSidesFromTheEndpoint() {
        CurseForgeFiles cf = new CurseForgeFiles(null, url -> JEI);
        CurseForgeFiles.Resolved r = cf.resolve(238222, 8807823);

        assertNotNull(r);
        assertEquals("jei-1.20.1-forge-15.57.0.207.jar", r.fileName());
        assertEquals(1820418, r.sizeBytes());
        assertEquals("https://edge.forgecdn.net/files/8807/823/jei-1.20.1-forge-15.57.0.207.jar", r.url());
        assertTrue(r.clientOk(), "a file marked Client must be installed on the client");
        assertTrue(r.serverOk(), "a file marked Server must be installable on a server");
    }

    @Test
    void aFileOnlyMarkedServerIsNotAClientFile() {
        CurseForgeFiles cf = new CurseForgeFiles(null, url -> SERVER_ONLY);
        CurseForgeFiles.Resolved r = cf.resolve(1, 8811156);

        assertNotNull(r);
        assertFalse(r.clientOk());
        assertTrue(r.serverOk());
    }

    @Test
    void anUnknownFileIsReportedAsMissingNotGuessedAt() {
        AtomicInteger calls = new AtomicInteger();
        CurseForgeFiles cf = new CurseForgeFiles(null, url -> {
            calls.incrementAndGet();
            return "{\"data\":null}"; // CurseForge's answer for a file id that doesn't exist
        });

        assertNull(cf.resolve(238222, 777));
        assertEquals(1, calls.get());
        assertNull(cf.resolve(238222, 777), "a miss is remembered, so a pack full of dead ids asks once each");
        assertEquals(1, calls.get());
    }

    @Test
    void skipsTheRequestEntirelyForUnusableIds() {
        AtomicInteger calls = new AtomicInteger();
        CurseForgeFiles cf = new CurseForgeFiles(null, url -> {
            calls.incrementAndGet();
            return JEI;
        });

        assertNull(cf.resolve(0, 5));
        assertNull(cf.resolve(5, 0));
        assertEquals(0, calls.get());
    }

    @Test
    void aBrokenAnswerIsTreatedAsAMiss() {
        CurseForgeFiles cf = new CurseForgeFiles(null, url -> "<html>rate limited</html>");
        assertNull(cf.resolve(238222, 424242));
    }

    @Test
    void remembersResolvedFilesOnDiskSoARepairRunCostsNoRequests(@TempDir Path dir) {
        Path cache = dir.resolve("modpacks").resolve("cf-files.json");
        long fileId = 876543210L; // unique to this test: the in-memory cache is process-wide

        AtomicInteger firstCalls = new AtomicInteger();
        CurseForgeFiles first = new CurseForgeFiles(cache, url -> {
            firstCalls.incrementAndGet();
            return "{\"data\":{\"fileName\":\"cached-1.0.jar\",\"fileLength\":42,"
                    + "\"gameVersions\":[\"1.20.1\",\"Client\",\"Server\"]}}";
        });
        assertEquals("cached-1.0.jar", first.resolve(1, fileId).fileName());
        assertEquals(1, firstCalls.get());
        assertTrue(Files.isRegularFile(cache), "the answer must be written next to modpack.json's cache");

        // A second run whose endpoint is unreachable -- e.g. CurseForge is down -- still resolves it.
        CurseForgeFiles second = new CurseForgeFiles(cache, url -> null);
        CurseForgeFiles.Resolved again = second.resolve(1, fileId);
        assertNotNull(again, "a cached file must survive CurseForge being unavailable");
        assertEquals(42, again.sizeBytes());
        assertEquals(CurseForgeFiles.cdnUrl(fileId, "cached-1.0.jar"), again.url());
    }
}
