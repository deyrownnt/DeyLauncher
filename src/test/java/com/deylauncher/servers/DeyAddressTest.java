package com.deylauncher.servers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A DEY address is the only part of the semi self-hosted feature a player types by hand, so its parsing
 * has to be forgiving about what people paste while still refusing values that could never resolve.
 */
class DeyAddressTest {

    @Test
    void recognisesThePrefixCaseInsensitivelyAndTrims() {
        assertEquals("mySMP", DeyAddress.aliasOf("dey|mySMP"));
        assertEquals("mySMP", DeyAddress.aliasOf("  DEY|mySMP  "));
        assertEquals("mySMP", DeyAddress.aliasOf("Dey|mySMP"));
        assertTrue(DeyAddress.isDeyAddress("dey|mySMP"));
    }

    @Test
    void anEmptyAliasIsNotADeyAddress() {
        // "dey|" names no server -- accepting it would put a permanently unresolvable value in the
        // Add Server dialog.
        assertNull(DeyAddress.aliasOf("dey|"));
        assertNull(DeyAddress.aliasOf("dey|   "));
        assertNull(DeyAddress.aliasOf("dey"));
        assertFalse(DeyAddress.isDeyAddress("dey|"));
    }

    @Test
    void aPlainAddressIsNotADeyAddress() {
        assertNull(DeyAddress.aliasOf("practice-downhill.ply.gg"));
        assertNull(DeyAddress.aliasOf("mc.example.com:25565"));
        assertNull(DeyAddress.aliasOf(null));
        assertNull(DeyAddress.aliasOf(""));
    }

    @Test
    void formatsAndNormalises() {
        assertEquals("dey|mySMP", DeyAddress.of("mySMP"));
        assertEquals("dey|mySMP", DeyAddress.of("  mySMP "));
        assertNull(DeyAddress.of("   "));
        // Matching is case-insensitive, so storage uses the normalised form.
        assertEquals("mysmp", DeyAddress.normalizeAlias("MySMP"));
        assertNull(DeyAddress.normalizeAlias(null));
    }

    @Test
    void aliasRulesRejectAnythingThatWouldBreakAPathOrACommitMessage() {
        assertTrue(DeyAddress.isValidAlias("mySMP"));
        assertTrue(DeyAddress.isValidAlias("my-smp_2"));
        assertTrue(DeyAddress.isValidAlias("a"));
        assertFalse(DeyAddress.isValidAlias("my smp"));   // space
        assertFalse(DeyAddress.isValidAlias("my.smp"));   // dot
        assertFalse(DeyAddress.isValidAlias("my/smp"));   // slash -- would escape a repo path
        assertFalse(DeyAddress.isValidAlias("dey|x"));    // the prefix is not part of an alias
        assertFalse(DeyAddress.isValidAlias(""));
        assertFalse(DeyAddress.isValidAlias(null));
        assertFalse(DeyAddress.isValidAlias("x".repeat(DeyAddress.MAX_ALIAS_LENGTH + 1)));
    }

    @Test
    void roundTripsThroughFormatAndParse() {
        String address = DeyAddress.of("mySMP");
        assertEquals("mySMP", DeyAddress.aliasOf(address));
    }
}
