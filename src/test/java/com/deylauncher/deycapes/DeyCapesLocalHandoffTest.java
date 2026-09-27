package com.deylauncher.deycapes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the pure half of {@link DeyCapesLocalHandoff}: turning the shared (private) cape catalog
 * into the local {@code capes.json} the in-game mod reads. No network and no temp files here -- the
 * part that talks to GitHub is {@code writeInto}, which deliberately degrades instead of throwing.
 */
class DeyCapesLocalHandoffTest {

    private static final String DEV = "dev";

    @Test
    void dashlessOnlineUuidAndItsOfflineUuidBothMapToTheSameLocalPng() {
        // Mojang's profile API hands the launcher a DASHLESS uuid, and the entry records the derived
        // offline uuid next to it. Both must end up as dashed keys: the mod parses them with
        // UUID.fromString, and a player must resolve to the same cape whether they are signed in or not.
        String dashless = "d05249e99fc14728b99ec360b51524bc";
        String offline = UUID.nameUUIDFromBytes("OfflinePlayer:Deyronn".getBytes()).toString();

        CapesData data = new CapesData();
        CapesData.PlayerCape p = data.getOrCreate(dashless, "Deyronn");
        p.cape = DEV;
        p.offlineUuid = offline;

        Map<String, String> map = DeyCapesLocalHandoff.buildLocalMap(data, Set.of(DEV));

        assertEquals("capes/dev.png", map.get(dashless.replaceFirst(
                "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5")));
        assertEquals("capes/dev.png", map.get(offline));
        assertEquals(2, map.size(), "one online uuid + one offline uuid, both pointing at the same PNG");
        // Relative, so the mod resolves it against its own config/deycapes/ -- an absolute path would
        // break the moment the instance is moved or copied to another machine.
        assertTrue(map.values().stream().allMatch(v -> v.startsWith("capes/")));
    }

    @Test
    void playerWhoseCapeTextureWasNotWrittenIsSkipped() {
        // buildLocalMap is only ever handed the cape ids whose PNG actually landed in the instance.
        // Anything else is dropped: a mapping to a file that does not exist is worse than no mapping.
        CapesData data = new CapesData();
        data.getOrCreate("811d8a9a-d9d3-3744-81c2-cc8897c917da", "Deyronn").cape = "gold";
        data.getOrCreate("5117b9df-e39a-3057-9671-b329e347f3f5", "Skibiddy1").cape = DEV;

        Map<String, String> map = DeyCapesLocalHandoff.buildLocalMap(data, Set.of(DEV));

        assertEquals(1, map.size());
        assertEquals("capes/dev.png", map.get("5117b9df-e39a-3057-9671-b329e347f3f5"));
        assertNull(map.get("811d8a9a-d9d3-3744-81c2-cc8897c917da"));
    }

    @Test
    void entriesWithoutAnEquippedCapeAndUnparseableUuidsAreIgnored() {
        CapesData data = new CapesData();
        data.getOrCreate("811d8a9a-d9d3-3744-81c2-cc8897c917da", "NoCape").cape = null;
        data.getOrCreate("5117b9df-e39a-3057-9671-b329e347f3f5", "Blank").cape = "   ";
        CapesData.PlayerCape bad = data.getOrCreate("not-a-uuid", "Typo");
        bad.cape = DEV;

        assertEquals(0, DeyCapesLocalHandoff.buildLocalMap(data, Set.of(DEV)).size());
    }

    @Test
    void aNullCatalogIsNotFatal() {
        assertEquals(0, DeyCapesLocalHandoff.buildLocalMap(null, Set.of(DEV)).size());
        assertEquals(0, DeyCapesLocalHandoff.buildLocalMap(new CapesData(), Set.of(DEV)).size());
    }

    @Test
    void dashedUuidCanonicalizesDashlessInputAndRejectsGarbage() {
        assertEquals("d05249e9-9fc1-4728-b99e-c360b51524bc",
                DeyCapesLocalHandoff.dashedUuid("d05249e99fc14728b99ec360b51524bc"));
        assertEquals("d05249e9-9fc1-4728-b99e-c360b51524bc",
                DeyCapesLocalHandoff.dashedUuid("  d05249e9-9fc1-4728-b99e-c360b51524bc  "));
        assertNull(DeyCapesLocalHandoff.dashedUuid("hello"));
        assertNull(DeyCapesLocalHandoff.dashedUuid(""));
        assertNull(DeyCapesLocalHandoff.dashedUuid(null));
    }

    @Test
    void capeIdsAreReducedToFilesystemSafeNames() {
        assertEquals("dev", DeyCapesLocalHandoff.safeFileName("dev"));
        assertEquals("my_cape_1", DeyCapesLocalHandoff.safeFileName("my/cape:1"));
        assertEquals(".._.._etc_passwd", DeyCapesLocalHandoff.safeFileName("../../etc/passwd"));
    }

    // ---------------------------------------------------------------------------------------------
    // The remote-read cleanup. This is what makes an instance the launcher already configured switch
    // over: any pointer the launcher wrote outranks the local handoff, so it has to go -- while a
    // file the user wrote by hand, aimed at their own public repo, must survive.
    // ---------------------------------------------------------------------------------------------

    @Test
    void aLauncherWrittenPointerIsRemovedEvenWhenItNamesTheOldPublicMirror(@TempDir Path configDir)
            throws Exception {
        Path file = configDir.resolve("github.properties");
        // Exactly the shape the launcher wrote before the handoff existed: launcher comment header,
        // pointing at the PUBLIC cape mirror rather than the private backend.
        Files.writeString(file, """
                #DeyCapes public repository settings
                #Sat Sep 26 11:43:32 EEST 2026
                owner=onpishi
                repo=DeyLauncher-Capes
                capesPath=capes.json
                capesDir=capes
                """);

        DeyCapesLocalHandoff.makeRemoteReadInert(configDir, "DeyLauncher-Friends", new java.util.ArrayList<>());

        assertFalse(Files.exists(file), "a launcher-written pointer must not be left to outrank the handoff");
    }

    @Test
    void aPointerAtThePrivateBackendIsRemovedEvenWithoutTheHeader(@TempDir Path configDir) throws Exception {
        Path file = configDir.resolve("github.properties");
        Files.writeString(file, "owner=onpishi\nrepo=DeyLauncher-Friends\n");

        DeyCapesLocalHandoff.makeRemoteReadInert(configDir, "DeyLauncher-Friends", new java.util.ArrayList<>());

        assertFalse(Files.exists(file), "the private repo cannot be read anonymously, so the pointer is useless");
    }

    @Test
    void aHandWrittenPointerAtSomeoneElsesPublicRepoSurvives(@TempDir Path configDir) throws Exception {
        Path file = configDir.resolve("github.properties");
        Files.writeString(file, "owner=somebody\nrepo=their-own-cape-repo\n");

        DeyCapesLocalHandoff.makeRemoteReadInert(configDir, "DeyLauncher-Friends", new java.util.ArrayList<>());

        assertTrue(Files.exists(file), "a public repo the user chose by hand still works, so leave it alone");
    }

    @Test
    void nothingHappensWhenThereIsNoPointerAtAll(@TempDir Path configDir) throws Exception {
        assertDoesNotThrow(() ->
                DeyCapesLocalHandoff.makeRemoteReadInert(configDir, "DeyLauncher-Friends", new java.util.ArrayList<>()));
    }
}
