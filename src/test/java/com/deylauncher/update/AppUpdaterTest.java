package com.deylauncher.update;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
    void currentVersionReadsEmbeddedOrFallsBackToDottedConstant() {
        // The build bakes /deylauncher-version.properties from project.version, so this should be the
        // real release version. If the resource is ever absent (e.g. unusual launch), the fallback
        // must still look like a dotted version -- never an empty/blank string.
        assertTrue(AppUpdater.currentVersion().matches("[0-9]+(\\.[0-9]+){1,3}"));
    }

    @Test
    void embeddedVersionResourceIsPresentAndSanelyVersioned() {
        try (InputStream in = AppUpdater.class.getResourceAsStream("/deylauncher-version.properties")) {
            assertNotNull(in, "deylauncher-version.properties must be baked in by processResources");
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("version");
            assertNotNull(v, "resource must define 'version'");
            assertTrue(v.matches("[0-9]+(\\.[0-9]+){1,3}"), "version must be a dotted number: " + v);
            // Sanity: it should be a real release, not the old 0.1.0 starting point.
            assertTrue(AppUpdater.isNewer(v.trim(), "0.0.0"), "running version should be above 0.0.0");
        } catch (Exception ex) {
            fail("reading embedded version failed: " + ex);
        }
    }
}