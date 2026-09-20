package com.deylauncher.server;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the log signatures that turn a dead server into an explanation. These are the console lines a
 * version change really produces when the world or the mods are from another Minecraft version -- the
 * case that used to end in nothing but "Server exited with code 1".
 */
class ServerStartDiagnosticsTest {

    @Test
    void worldFromANewerVersionIsRecognised() {
        var hint = ServerStartDiagnostics.hintFor(
                "[Server thread/ERROR]: Failed to load level: unsupported data version 4189 (this world "
                        + "was created in a newer version of Minecraft)");
        assertNotNull(hint);
        assertEquals("world-too-new", hint.key());
        assertTrue(hint.message().toLowerCase().contains("change version"),
                "the hint must point at the fix the launcher provides");
    }

    @Test
    void leftoverModsAreRecognised() {
        assertEquals("mods-version", ServerStartDiagnostics.hintFor(
                "[main/ERROR]: Incompatible mod set! net.fabricmc.loader.impl.FormattedException: "
                        + "Mod 'sodium' (0.6.0) requires minecraft 1.21.11").key());
        assertEquals("mods-version", ServerStartDiagnostics.hintFor(
                "[main/ERROR]: Mixin apply failed mixins.sodium.json").key());
    }

    @Test
    void aMissingModLibraryIsRecognisedAsTheAutoFixableCase() {
        var immediate = ServerStartDiagnostics.hintFor(
                "[19:55:04] [main/INFO]: Immediate reason: [HARD_DEP_NO_CANDIDATE antique_atlas_item 1.0.0 "
                        + "{depends antique_atlas @ [>=2.11.2+1.20]}, ROOT_FORCELOAD_SINGLE antique_atlas_item 1.0.0]");
        assertNotNull(immediate);
        assertEquals("missing-mod-dependency", immediate.key());
        assertTrue(immediate.message().contains("mods-disabled"),
                "the hint must point at where the launcher sets the offending mod aside");

        assertEquals("missing-mod-dependency", ServerStartDiagnostics.hintFor(
                "\t - Mod 'AntiqueAtlasItem' (antique_atlas_item) 1.0.0 requires version 2.11.2+1.20 "
                        + "or later of antique_atlas, which is missing!").key());
    }

    @Test
    void otherCommonStartupFailuresAreRecognised() {
        assertEquals("java-too-old", ServerStartDiagnostics.hintFor(
                "java.lang.UnsupportedClassVersionError: net/minecraft/server/Main has been compiled by a "
                        + "more recent version of the Java Runtime").key());
        assertEquals("missing-software", ServerStartDiagnostics.hintFor(
                "Error: Unable to access jarfile server.jar").key());
        assertEquals("datapacks-version", ServerStartDiagnostics.hintFor(
                "[Server thread/ERROR]: Failed to load datapacks, can't proceed with server load").key());
        assertEquals("eula", ServerStartDiagnostics.hintFor(
                "You need to agree to the EULA in order to run the server").key());
    }

    @Test
    void ordinaryLinesProduceNoHint() {
        assertNull(ServerStartDiagnostics.hintFor("[13:05:35] [Server thread/INFO]: Starting minecraft server version 26.2"));
        assertNull(ServerStartDiagnostics.hintFor("[13:05:35] [Server thread/INFO]: Done (2.418s)! For help, type \"help\""));
        assertNull(ServerStartDiagnostics.hintFor(""));
        assertNull(ServerStartDiagnostics.hintFor(null));
    }

    @Test
    void eachHintIsOnlyShownOnce() {
        List<ServerStartDiagnostics.Hint> shown = new ArrayList<>();
        var first = ServerStartDiagnostics.hintFor("unsupported data version 4189");
        assertTrue(ServerStartDiagnostics.isNew(shown, first));
        shown.add(first);

        var repeated = ServerStartDiagnostics.hintFor("another line about the data version 4189");
        assertEquals(first.key(), repeated.key());
        assertTrue(!ServerStartDiagnostics.isNew(shown, repeated), "the same advice must not repeat");
        assertTrue(!ServerStartDiagnostics.isNew(shown, null), "no hint is never 'new'");
    }
}