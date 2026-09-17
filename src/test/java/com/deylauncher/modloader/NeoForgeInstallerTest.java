package com.deylauncher.modloader;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins NeoForge's two easy-to-get-wrong naming rules, both verified against the real Maven
 * repository rather than guessed:
 *
 * <ol>
 *   <li>NeoForge's own version numbers drop Minecraft's leading {@code 1.} ({@code 1.21.1} is
 *       NeoForge {@code 21.1.x}, {@code 26.2} is {@code 26.2.x}), and MC 1.20.1 is the one version
 *       published under the legacy {@code net/neoforged/forge} artifact as {@code 1.20.1-47.1.x}.</li>
 *   <li>The client profile NeoForge's installer writes is named {@code neoforge-<version>} (modern)
 *       or just {@code <version>} (legacy). Getting it wrong means the "already installed" check
 *       never matches, so the (slow) installer is re-run on every single launch.</li>
 * </ol>
 */
class NeoForgeInstallerTest {

    @Test
    void modernVersionsDropTheLeadingOneOfTheMinecraftVersion() {
        assertEquals("21.1.", NeoForgeInstaller.targetFor("1.21.1").versionPrefix());
        assertEquals("21.11.", NeoForgeInstaller.targetFor("1.21.11").versionPrefix());
        assertEquals("20.4.", NeoForgeInstaller.targetFor("1.20.4").versionPrefix());
        assertEquals("26.2.", NeoForgeInstaller.targetFor("26.2").versionPrefix());
        assertEquals("26.3.", NeoForgeInstaller.targetFor("26.3").versionPrefix());

        assertEquals("neoforge", NeoForgeInstaller.targetFor("26.2").artifactId());
        assertEquals("neoforge", NeoForgeInstaller.targetFor("1.21.1").artifactId());
    }

    @Test
    void twentyTwentyOneIsTheLegacyForgeNamedArtifact() {
        NeoForgeInstaller.Target target = NeoForgeInstaller.targetFor("1.20.1");
        assertEquals("forge", target.artifactId());
        assertEquals("1.20.1-", target.versionPrefix());
        assertTrue(target.legacyForge());
    }

    @Test
    void versionsNeoForgeNeverSupportedReportNoTarget() {
        // 1.16-1.19 predate NeoForge entirely; "1.20" is the 1.20.0 release with no NeoForge line;
        // snapshots aren't NeoForge targets either.
        assertNull(NeoForgeInstaller.targetFor("1.16.5"));
        assertNull(NeoForgeInstaller.targetFor("1.19.4"));
        assertNull(NeoForgeInstaller.targetFor("1.20"));
        assertNull(NeoForgeInstaller.targetFor("26.3-snapshot-2"));
        assertNull(NeoForgeInstaller.targetFor(""));
        assertNull(NeoForgeInstaller.targetFor(null));
    }

    @Test
    void profileIdMatchesWhatTheInstallerActuallyWrites() {
        NeoForgeInstaller.Target modern = NeoForgeInstaller.targetFor("26.2");
        assertEquals("neoforge-26.2.0.88", NeoForgeInstaller.profileId(modern, "26.2.0.88"));

        // Verified from the legacy installer's own install_profile.json: "1.20.1-forge-47.1.106".
        NeoForgeInstaller.Target legacy = NeoForgeInstaller.targetFor("1.20.1");
        assertEquals("1.20.1-forge-47.1.106", NeoForgeInstaller.profileId(legacy, "1.20.1-47.1.106"));
    }

    @Test
    void profileFileLivesUnderVersionsNamedAfterItself() {
        assertEquals(Path.of("/tmp/deyroot/versions/neoforge-26.2.0.88/neoforge-26.2.0.88.json"),
                NeoForgeInstaller.profileFile(Path.of("/tmp/deyroot"), "neoforge-26.2.0.88"));
    }

    @Test
    void installerUrlPointsAtNeoForgedsOwnMaven() {
        assertEquals(
                "https://maven.neoforged.net/releases/net/neoforged/neoforge/26.2.0.88/"
                        + "neoforge-26.2.0.88-installer.jar",
                NeoForgeInstaller.installerUrl(NeoForgeInstaller.targetFor("26.2"), "26.2.0.88"));
        assertEquals(
                "https://maven.neoforged.net/releases/net/neoforged/forge/1.20.1-47.1.106/"
                        + "forge-1.20.1-47.1.106-installer.jar",
                NeoForgeInstaller.installerUrl(NeoForgeInstaller.targetFor("1.20.1"), "1.20.1-47.1.106"));
    }

    /**
     * The choice that matters on a brand-new Minecraft release: 26.3 currently only has beta builds,
     * and refusing them would mean "NeoForge doesn't work" on the newest version -- but a stable build
     * must always win when one exists.
     */
    @Test
    void newestStableWinsAndBetasAreOnlyAFallback() {
        assertEquals("26.2.0.88", NeoForgeInstaller.pickLatest(List.of("26.2.0.86", "26.2.0.87", "26.2.0.88")));
        assertEquals("26.3.0.1-beta", NeoForgeInstaller.pickLatest(List.of("26.3.0.0-beta", "26.3.0.1-beta")));
        assertEquals("26.2.0.88",
                NeoForgeInstaller.pickLatest(List.of("26.2.0.88", "26.3.0.1-beta", "26.2.0.90-beta")));
        assertNull(NeoForgeInstaller.pickLatest(List.of()));
    }

    @Test
    void versionComparingIsNumericNotLexicographic() {
        assertTrue(NeoForgeInstaller.compareVersions("26.2.0.88", "26.2.0.9") > 0, "88 > 9 numerically");
        assertTrue(NeoForgeInstaller.compareVersions("26.3.0.1-beta", "26.2.0.88") > 0);
        assertTrue(NeoForgeInstaller.compareVersions("21.11.5", "21.1.72") > 0);
        assertEquals(0, NeoForgeInstaller.compareVersions("26.2.0.88", "26.2.0.88"));
    }
}
