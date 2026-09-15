package com.deylauncher.friends;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The published play state has to survive friends running different launcher builds, so parsing is
 * deliberately forgiving: anything missing or unrecognized is UNKNOWN and must be rendered the legacy
 * way (an address still means "on a server") rather than breaking the Friends page.
 */
class PlayStateTest {

    @Test
    void wireValuesRoundTrip() {
        for (PlayState state : PlayState.values()) {
            assertEquals(state, PlayState.fromWire(state.wire));
        }
    }

    @Test
    void wireValuesAreStableStrings() {
        // These strings are the published contract -- renaming one silently breaks older readers.
        assertEquals("IN_LAUNCHER", PlayState.IN_LAUNCHER.wire);
        assertEquals("SINGLE_PLAYER", PlayState.SINGLE_PLAYER.wire);
        assertEquals("SERVER", PlayState.SERVER.wire);
        assertNull(PlayState.UNKNOWN.wire); // never written to friends.json
    }

    @Test
    void missingOrUnknownValuesParseToUnknown() {
        assertEquals(PlayState.UNKNOWN, PlayState.fromWire(null));
        assertEquals(PlayState.UNKNOWN, PlayState.fromWire(""));
        assertEquals(PlayState.UNKNOWN, PlayState.fromWire("   "));
        assertEquals(PlayState.UNKNOWN, PlayState.fromWire("SOMETHING_A_FUTURE_BUILD_ADDED"));
    }

    @Test
    void parsingIsCaseInsensitiveAndTrims() {
        assertEquals(PlayState.SERVER, PlayState.fromWire("server"));
        assertEquals(PlayState.SINGLE_PLAYER, PlayState.fromWire(" single_player "));
        assertEquals(PlayState.IN_LAUNCHER, PlayState.fromWire("in_launcher"));
    }
}