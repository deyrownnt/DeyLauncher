package com.deylauncher.modpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The version/loader/modpack state machine, without any UI: a pack is attached only while the selectors
 * read what it was installed for, every change to them detaches it, and one resolver decides which
 * folder launches. The launcher's dropdowns are never locked -- these tests are the "select, change
 * version, change loader, select another, switch back, return to plain" flow the UI has to survive.
 */
class ModpackSelectionTest {

    /** Installs a pack record in its own folder, the way the launcher does. */
    private static ModpackMeta install(Path root, String name, String mc, String loader) {
        ModpackMeta meta = new ModpackMeta();
        meta.name = name;
        meta.version = "1.0";
        meta.mcVersion = mc;
        meta.loader = loader;
        meta.loaderVersion = "9.9.9";
        Path dir = ModpackMeta.installDirFor(root, name, mc, loader);
        meta.write(dir);
        return ModpackMeta.read(dir);
    }

    @Test
    void attachingNeedsTheSelectorsToReadExactlyWhatThePackTargets(@TempDir Path root) {
        ModpackMeta pack = install(root, "Pack", "1.20.1", "Fabric");
        ModpackSelection sel = new ModpackSelection();

        assertFalse(sel.attach(pack, "1.21.1", "Fabric", false), "wrong version");
        assertNull(sel.attached());
        assertFalse(sel.attach(pack, "1.20.1", "Forge", false), "wrong loader");
        assertNull(sel.attached());
        assertFalse(sel.attach(pack, "1.20.1", "Fabric", true), "DEY mode injects its own mods");
        assertNull(sel.attached());
        assertFalse(sel.attach(null, "1.20.1", "Fabric", false));

        assertTrue(sel.attach(pack, "1.20.1", "Fabric", false));
        assertEquals("Pack", sel.attached().name);
    }

    @Test
    void aFailedAttachAlsoDropsThePreviousPackSoNothingStaleSurvives(@TempDir Path root) {
        ModpackMeta a = install(root, "A", "1.20.1", "Fabric");
        ModpackMeta b = install(root, "B", "1.21.1", "Forge");
        ModpackSelection sel = new ModpackSelection();
        assertTrue(sel.attach(a, "1.20.1", "Fabric", false));

        assertFalse(sel.attach(b, "1.20.1", "Fabric", false));
        assertNull(sel.attached(), "A must not linger after the switch to B failed");
    }

    @Test
    void changingTheVersionOrTheLoaderDetachesThePack(@TempDir Path root) {
        ModpackMeta pack = install(root, "Pack", "1.20.1", "Fabric");
        ModpackSelection sel = new ModpackSelection();

        assertTrue(sel.attach(pack, "1.20.1", "Fabric", false));
        assertFalse(sel.reconcile("1.20.1", "Fabric", false), "an event that changes nothing keeps the pack");
        assertNotNull(sel.attached());
        assertTrue(sel.reconcile("1.21.1", "Fabric", false), "version change detaches");
        assertNull(sel.attached());

        assertTrue(sel.attach(pack, "1.20.1", "Fabric", false));
        assertTrue(sel.reconcile("1.20.1", "Forge", false), "loader change detaches");
        assertNull(sel.attached());

        assertTrue(sel.attach(pack, "1.20.1", "Fabric", false));
        assertTrue(sel.reconcile("1.20.1", null, false), "a cleared loader box (mid-rebuild) detaches");
        assertNull(sel.attached());
    }

    @Test
    void switchingToDeyDetachesAndReturningDoesNotResurrectThePack(@TempDir Path root) {
        ModpackMeta pack = install(root, "Pack", "1.20.1", "Fabric");
        ModpackSelection sel = new ModpackSelection();
        assertTrue(sel.attach(pack, "1.20.1", "Fabric", false));

        assertTrue(sel.reconcile("1.20.1", "Fabric", true));
        assertNull(sel.attached());
        assertFalse(sel.reconcile("1.20.1", "Fabric", false));
        assertNull(sel.attached(), "going back to the same values by hand is plain Vanilla, not the pack");
    }

    @Test
    void anAttachedPackLaunchesFromItsOwnFolderAndAPlainSelectionFromThePlainOne(@TempDir Path root) {
        ModpackMeta pack = install(root, "Pack", "1.20.1", "Fabric");
        ModpackSelection sel = new ModpackSelection();

        ModpackSelection.Target plain = sel.resolve(root, "1.20.1", "Fabric");
        assertEquals(ModpackMeta.instanceDirFor(root, "1.20.1", "Fabric"), plain.instanceDir());
        assertNull(plain.pack(), "selecting 1.20.1 + Fabric by hand must NOT silently launch an isolated pack");
        assertNull(plain.pinnedLoaderVersion());

        assertTrue(sel.attach(pack, "1.20.1", "Fabric", false));
        ModpackSelection.Target attached = sel.resolve(root, "1.20.1", "Fabric");
        assertEquals(pack.instanceDir(root), attached.instanceDir());
        assertEquals("Pack", attached.pack().name);
        assertEquals("9.9.9", attached.pinnedLoaderVersion(), "the pin comes from the pack that launches");
    }

    @Test
    void twoPacksOnTheSameVersionAndLoaderNeverGetMixedUp(@TempDir Path root) {
        ModpackMeta a = install(root, "Pack A", "1.20.1", "Fabric");
        ModpackMeta b = install(root, "Pack B", "1.20.1", "Fabric");
        ModpackSelection sel = new ModpackSelection();

        assertTrue(sel.attach(a, "1.20.1", "Fabric", false));
        assertEquals(a.instanceDir(root), sel.resolve(root, "1.20.1", "Fabric").instanceDir());
        assertTrue(sel.attach(b, "1.20.1", "Fabric", false));
        assertEquals(b.instanceDir(root), sel.resolve(root, "1.20.1", "Fabric").instanceDir());
        assertTrue(sel.attach(a, "1.20.1", "Fabric", false));
        assertEquals("Pack A", sel.resolve(root, "1.20.1", "Fabric").pack().name,
                "what is attached is exactly what launches -- never 'the first pack that matches'");
    }

    @Test
    void thePackLivingInThePlainFolderFromAnOlderBuildIsStillReportedAsWhatWillRun(@TempDir Path root) {
        ModpackMeta legacy = new ModpackMeta();
        legacy.name = "Legacy";
        legacy.mcVersion = "1.20.1";
        legacy.loader = "Forge";
        legacy.write(ModpackMeta.instanceDirFor(root, "1.20.1", "Forge"));

        ModpackSelection.Target t = new ModpackSelection().resolve(root, "1.20.1", "Forge");
        assertEquals("Legacy", t.pack().name, "the icon/pin/Mods window must describe the folder that really launches");
        assertEquals(ModpackMeta.instanceDirFor(root, "1.20.1", "Forge"), t.instanceDir());
    }

    @Test
    void aPackDeletedBehindTheLaunchersBackIsDetachedInsteadOfLaunchedAsAnEmptyFolder(@TempDir Path root) throws Exception {
        ModpackMeta pack = install(root, "Doomed", "1.20.1", "Fabric");
        ModpackSelection sel = new ModpackSelection();
        assertTrue(sel.attach(pack, "1.20.1", "Fabric", false));

        Files.delete(pack.instanceDir(root).resolve("modpack.json"));

        ModpackSelection.Target t = sel.resolve(root, "1.20.1", "Fabric");
        assertNull(sel.attached());
        assertNull(t.pack());
        assertEquals(ModpackMeta.instanceDirFor(root, "1.20.1", "Fabric"), t.instanceDir());
    }

    @Test
    void deletingAPackDetachesOnlyThatPack(@TempDir Path root) {
        ModpackMeta a = install(root, "A", "1.20.1", "Fabric");
        ModpackMeta b = install(root, "B", "1.20.1", "Fabric");
        ModpackSelection sel = new ModpackSelection();
        assertTrue(sel.attach(a, "1.20.1", "Fabric", false));

        sel.detachIfInstance(b.instanceId);
        assertNotNull(sel.attached(), "deleting another pack leaves the attached one alone");
        sel.detachIfInstance(a.instanceId);
        assertNull(sel.attached());
    }

    /** The whole flow from the bug report, step by step, asserting what the launcher would run each time. */
    @Test
    void theFullSelectChangeSwitchAndReturnFlowNeverGetsStuck(@TempDir Path root) {
        ModpackMeta a = install(root, "Pack A", "1.20.1", "Fabric");
        ModpackMeta b = install(root, "Pack B", "1.21.1", "NeoForge");
        Path plain121Fabric = ModpackMeta.instanceDirFor(root, "1.21.1", "Fabric");
        ModpackSelection sel = new ModpackSelection();
        String mc;
        String loader;

        // 1. select pack A (the UI points the selectors at the pack, then attaches)
        mc = "1.20.1";
        loader = "Fabric";
        assertTrue(sel.attach(a, mc, loader, false));
        assertEquals(a.instanceDir(root), sel.resolve(root, mc, loader).instanceDir());

        // 2. change the Minecraft version -> plain, and NOT locked: the selection is simply whatever the boxes say
        mc = "1.21.1";
        assertTrue(sel.reconcile(mc, loader, false));
        assertEquals(plain121Fabric, sel.resolve(root, mc, loader).instanceDir());
        assertNull(sel.attached());

        // 3. change the loader -> still plain, still free
        loader = "Forge";
        assertFalse(sel.reconcile(mc, loader, false));
        assertEquals(ModpackMeta.instanceDirFor(root, mc, loader), sel.resolve(root, mc, loader).instanceDir());

        // 4. select pack B
        mc = "1.21.1";
        loader = "NeoForge";
        assertTrue(sel.attach(b, mc, loader, false));
        assertEquals(b.instanceDir(root), sel.resolve(root, mc, loader).instanceDir());

        // 5. switch back to pack A directly (no restart, no intermediate reset needed)
        mc = "1.20.1";
        loader = "Fabric";
        assertTrue(sel.attach(a, mc, loader, false));
        assertEquals(a.instanceDir(root), sel.resolve(root, mc, loader).instanceDir());

        // 6. change the loader again
        loader = "Vanilla";
        assertTrue(sel.reconcile(mc, loader, false));
        assertEquals(ModpackMeta.instanceDirFor(root, mc, loader), sel.resolve(root, mc, loader).instanceDir());

        // 7. back to the normal flow (DEY): nothing attached, nothing stale
        assertFalse(sel.reconcile(mc, "Fabric", true));
        assertNull(sel.attached());
        assertNull(sel.resolve(root, "1.21.1", "Fabric").pack());
    }
}
