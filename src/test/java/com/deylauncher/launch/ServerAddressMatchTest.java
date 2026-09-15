package com.deylauncher.launch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the rules that decide whether a live address is recognised as a saved server. The bug these
 * guard against: matching on the host alone made "eu.mcpvp.club:25565" resolve to whichever same-host
 * bookmark came first in the user's list, so a friend on one server was labelled with another one's
 * name (and with the same-host default-port shortcut, two different ports looked identical).
 */
class ServerAddressMatchTest {

    @Test
    void identicalAddressesScoreExact() {
        assertEquals(ServerAddressMatch.SCORE_EXACT,
                ServerAddressMatch.score("donutsmp.net:25565", "donutsmp.net:25565"));
        assertTrue(ServerAddressMatch.matchesExact("DONUTSMP.NET:25565", "donutsmp.net:25565")); // case-insensitive
        assertTrue(ServerAddressMatch.matches("donutsmp.net:25565", "donutsmp.net:25565"));
    }

    @Test
    void omittedPortStillMatchesTheDefaultPort() {
        // A bookmark saved without a port is the same server as the live "host:25565".
        assertEquals(ServerAddressMatch.SCORE_HOST_ONLY,
                ServerAddressMatch.score("eu.mcpvp.club", "eu.mcpvp.club:25565"));
        assertTrue(ServerAddressMatch.matches("eu.mcpvp.club", "eu.mcpvp.club:25565"));
        assertFalse(ServerAddressMatch.matchesExact("eu.mcpvp.club", "eu.mcpvp.club:25565"));
    }

    @Test
    void sameHostDifferentPortIsADifferentServer() {
        assertEquals(ServerAddressMatch.SCORE_NONE,
                ServerAddressMatch.score("eu.mcpvp.club:25566", "eu.mcpvp.club:25565"));
        assertFalse(ServerAddressMatch.matches("eu.mcpvp.club:25566", "eu.mcpvp.club:25565"));
    }

    @Test
    void differentHostsNeverMatch() {
        assertEquals(ServerAddressMatch.SCORE_NONE,
                ServerAddressMatch.score("donutsmp.net:25565", "eu.mcpvp.club:25565"));
        assertFalse(ServerAddressMatch.matches("donutsmp.net", "eu.mcpvp.club"));
    }

    @Test
    void blankAndNullInputsAreHandled() {
        assertEquals(ServerAddressMatch.SCORE_NONE, ServerAddressMatch.score(null, "host:25565"));
        assertEquals(ServerAddressMatch.SCORE_NONE, ServerAddressMatch.score("host:25565", "   "));
        assertNull(ServerAddressMatch.hostOf(null));
        assertNull(ServerAddressMatch.portOf("host")); // no explicit port
    }

    @Test
    void hostAndPortAreExtracted() {
        assertEquals("eu.mcpvp.club", ServerAddressMatch.hostOf("eu.mcpvp.club:25565"));
        assertEquals("25565", ServerAddressMatch.portOf("eu.mcpvp.club:25565"));
        assertEquals("localhost", ServerAddressMatch.hostOf("localhost"));
        assertTrue(ServerAddressMatch.hasExplicitPort("localhost:25565"));
        assertFalse(ServerAddressMatch.hasExplicitPort("localhost"));
    }

    @Test
    void ownedServerStyleAddressesMatchTheirLocalhostForm() {
        // How an owned server is announced: "localhost:<port>" or "<lan-ip>:<port>".
        assertEquals(ServerAddressMatch.SCORE_EXACT,
                ServerAddressMatch.score("localhost:25565", "localhost:25565"));
        assertEquals(ServerAddressMatch.SCORE_NONE,
                ServerAddressMatch.score("localhost:25566", "localhost:25565"));
    }

    @Test
    void loopbackAddressesAreRecognised() {
        assertTrue(ServerAddressMatch.isLoopback("localhost"));
        assertTrue(ServerAddressMatch.isLoopback("localhost:25565"));
        assertTrue(ServerAddressMatch.isLoopback("127.0.0.1:25565"));
        assertTrue(ServerAddressMatch.isLoopback("::1"));
        assertFalse(ServerAddressMatch.isLoopback("eu.mcpvp.club:25565"));
        assertFalse(ServerAddressMatch.isLoopback(null));
    }

    @Test
    void ownMachineAddressesAreNotShareable() {
        // What a joined server's address must pass before it is ever published to friends: loopback and
        // our own LAN IP are not reachable by them, so those must never be advertised.
        assertFalse(ServerAddressMatch.isShareable("localhost:25565", null));
        assertFalse(ServerAddressMatch.isShareable("127.0.0.1:25565", "192.168.1.42"));
        assertFalse(ServerAddressMatch.isShareable("192.168.1.42:25565", "192.168.1.42"));
        assertFalse(ServerAddressMatch.isShareable("192.168.1.42:25565", " 192.168.1.42 "));
        assertTrue(ServerAddressMatch.isShareable("192.168.1.42:25565", "192.168.1.7"));
        assertTrue(ServerAddressMatch.isShareable("192.168.1.42:25565", null));
        assertTrue(ServerAddressMatch.isShareable("donutsmp.net:25565", "192.168.1.42"));
        assertFalse(ServerAddressMatch.isShareable("   ", "192.168.1.42"));
        assertFalse(ServerAddressMatch.isShareable(null, null));
    }
}