package com.deylauncher.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the semantic choices that matter for the self-updater: {@code isNewer} must compare
 * dotted versions numerically (so 0.10.0 &gt; 0.2.0), tolerate a leading "v", and treat equal
 * versions as NOT new. Only the pure logic is tested here -- the GitHub network query is not.
 */
class AppUpdaterTest {

    @Test
    void isNewerComparesNumerically() {
        assertTrue(AppUpdater.isNewer("0.2.0", "0.1.0"));
        assertTrue(AppUpdater.isNewer("1.0.0", "0.9.9"));
        assertTrue(AppUpdater.isNewer("0.10.0", "0.2.0"));   // 10 > 2, not lexicographic
        assertTrue(AppUpdater.isNewer("26.2", "1.21.1"));    // missing parts are 0
    }

    @Test
    void isNewerToleratesLeadingV() {
        assertTrue(AppUpdater.isNewer("v0.3.0", "0.2.9"));
        assertTrue(AppUpdater.isNewer("v1.2.0", "0.1.0"));
    }

    @Test
    void equalOrOlderIsNotNewer() {
        assertFalse(AppUpdater.isNewer("0.1.0", "0.1.0"));
        assertFalse(AppUpdater.isNewer("0.1.0", "0.2.0"));
        assertFalse(AppUpdater.isNewer("1.2.0", "1.2.5"));
    }

    @Test
    void currentVersionFallsBackWhenNotFromAJar() {
        // Tests run from a classes dir, so the code-source jar is absent -> fallback constant.
        assertTrue(AppUpdater.currentVersion().matches("[0-9]+(\\.[0-9]+){1,3}"));
    }
}