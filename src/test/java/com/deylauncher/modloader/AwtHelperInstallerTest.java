package com.deylauncher.modloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The gate that decides whether {@code deylauncher-awt-init.jar} gets installed, and the
 * install/self-heal behavior once it does. See {@link AwtHelperInstaller}'s class docs for the full
 * story of why this exists.
 */
class AwtHelperInstallerTest {

    @Test
    void onlyLinuxFabricNeedsTheHelper() {
        assertTrue(AwtHelperInstaller.platformNeedsHelper("Linux", "Fabric"));
        assertTrue(AwtHelperInstaller.platformNeedsHelper("Ubuntu 24.04", "fabric"),
                "loader match should be case-insensitive");

        assertFalse(AwtHelperInstaller.platformNeedsHelper("Windows 11", "Fabric"),
                "Windows already defaults AWT to non-headless -- never needs this");
        assertFalse(AwtHelperInstaller.platformNeedsHelper("Mac OS X", "Fabric"));
        assertFalse(AwtHelperInstaller.platformNeedsHelper("Linux", "Forge"),
                "only Fabric's prelaunch entrypoints are what this helper hooks into");
        assertFalse(AwtHelperInstaller.platformNeedsHelper("Linux", "NeoForge"));
        assertFalse(AwtHelperInstaller.platformNeedsHelper("Linux", null));
        assertFalse(AwtHelperInstaller.platformNeedsHelper(null, "Fabric"));
    }

    @Test
    void aPackWithNoAwtSwingModNeedsNoHelper(@TempDir Path modsDir) throws Exception {
        Files.writeString(modsDir.resolve("sodium-fabric-0.5.jar"), "not a real jar");
        Files.writeString(modsDir.resolve("fabric-api-0.9.jar"), "not a real jar");

        assertFalse(AwtHelperInstaller.modsDirHasTriggerMod(modsDir));
        assertNull(AwtHelperInstaller.ensureInstalled("Linux", "Fabric", modsDir),
                "nothing to trigger the HeadlessException -- installing the helper would be pointless");
        assertFalse(Files.exists(modsDir.resolve("deylauncher-awt-init.jar")));
    }

    @Test
    void earlyLoadingBarPresentIsTheTrigger(@TempDir Path modsDir) throws Exception {
        Files.writeString(modsDir.resolve("earlyloadingbar-fabric-2.1.0.jar"), "not a real jar");

        assertTrue(AwtHelperInstaller.modsDirHasTriggerMod(modsDir));
    }

    @Test
    void triggerModMatchIsCaseInsensitive(@TempDir Path modsDir) throws Exception {
        Files.writeString(modsDir.resolve("EarlyLoadingBar-Fabric-2.1.0.JAR"), "not a real jar");

        assertTrue(AwtHelperInstaller.modsDirHasTriggerMod(modsDir));
    }

    @Test
    void aNonexistentModsDirNeedsNoHelperAndIsNeverCreatedJustToCheck(@TempDir Path base) {
        Path modsDir = base.resolve("does-not-exist-yet");
        assertFalse(AwtHelperInstaller.modsDirHasTriggerMod(modsDir));
    }

    /**
     * The end-to-end install: given the gate is satisfied, the exact bundled resource lands in mods/
     * under its fixed, launcher-managed name -- and running it again is a no-op (isCurrent short-circuits),
     * which is what makes calling this on every single launch safe and cheap.
     */
    @Test
    void installsTheBundledHelperWhenTheGateIsSatisfied(@TempDir Path modsDir) throws Exception {
        Files.writeString(modsDir.resolve("earlyloadingbar-fabric-2.1.0.jar"), "not a real jar");

        String installed = AwtHelperInstaller.ensureInstalled("Linux", "Fabric", modsDir);
        assertEquals("deylauncher-awt-init.jar", installed);

        Path target = modsDir.resolve("deylauncher-awt-init.jar");
        assertTrue(Files.isRegularFile(target));
        assertTrue(Files.size(target) > 0);
        assertTrue(AwtHelperInstaller.isCurrent(modsDir));

        // Second call: already installed and current -- nothing changes, nothing re-copied.
        assertNull(AwtHelperInstaller.ensureInstalled("Linux", "Fabric", modsDir));
    }

    /**
     * The self-heal case this whole class exists for: a modpack reinstall/repair can wipe an instance's
     * mods folder back down to just the pack's own manifest, silently taking a hand-copied extra file
     * with it. Because ensureInstalled runs on every launch, a missing/corrupted helper is put back
     * automatically -- no manual copy, no user action.
     */
    @Test
    void aMissingOrCorruptedHelperIsRestoredOnTheNextLaunch(@TempDir Path modsDir) throws Exception {
        Files.writeString(modsDir.resolve("earlyloadingbar-fabric-2.1.0.jar"), "not a real jar");
        assertNotNull(AwtHelperInstaller.ensureInstalled("Linux", "Fabric", modsDir));

        // Simulate a modpack reinstall clobbering the file with garbage (or deleting it outright).
        Path target = modsDir.resolve("deylauncher-awt-init.jar");
        Files.writeString(target, "corrupted by something else");
        assertFalse(AwtHelperInstaller.isCurrent(modsDir));

        String restored = AwtHelperInstaller.ensureInstalled("Linux", "Fabric", modsDir);
        assertEquals("deylauncher-awt-init.jar", restored);
        assertTrue(AwtHelperInstaller.isCurrent(modsDir));

        Files.delete(target);
        assertFalse(AwtHelperInstaller.isCurrent(modsDir));
        assertEquals("deylauncher-awt-init.jar", AwtHelperInstaller.ensureInstalled("Linux", "Fabric", modsDir));
    }

    @Test
    void windowsNeverGetsTheHelperEvenWithTheTriggerModPresent(@TempDir Path modsDir) throws Exception {
        Files.writeString(modsDir.resolve("earlyloadingbar-fabric-2.1.0.jar"), "not a real jar");
        assertNull(AwtHelperInstaller.ensureInstalled("Windows 11", "Fabric", modsDir));
        assertFalse(Files.exists(modsDir.resolve("deylauncher-awt-init.jar")));
    }
}
