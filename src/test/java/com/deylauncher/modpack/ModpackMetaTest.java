package com.deylauncher.modpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The instance's pack record is what a launch-time repair reads, so its file manifest has to survive
 * being written as JSON and read back (including records and null hash fields).
 */
class ModpackMetaTest {

    @Test
    void theFileManifestSurvivesARoundTripThroughTheInstanceRecord(@TempDir Path instance) {
        ModpackMeta meta = new ModpackMeta();
        meta.name = "Test Pack";
        meta.version = "1.0";
        meta.mcVersion = "1.20.1";
        meta.loader = "Forge";
        meta.loaderVersion = "47.4.20";
        meta.format = ModpackFormat.CURSEFORGE_ZIP.name();
        meta.source = "/tmp/test-pack.zip";
        meta.files = List.of(
                ModpackFile.download("mods/a.jar", "https://example.invalid/a.jar", "abc", "def", 123,
                        true, false, true, false),
                ModpackFile.bundled("config/pack.cfg", "overrides/config/pack.cfg", 5));
        meta.unresolved = List.of("999/111 -- Gone");

        meta.write(instance);
        ModpackMeta read = ModpackMeta.read(instance);

        assertNotNull(read, "the pack must still be listed after a restart");
        assertEquals("Test Pack", read.name);
        assertEquals("47.4.20", read.loaderVersion, "the pinned loader build is what makes the pack launchable");
        assertTrue(read.managesFiles());
        assertEquals(2, read.packFiles().size());

        ModpackFile mod = read.packFiles().get(0);
        assertEquals("mods/a.jar", mod.path());
        assertEquals("https://example.invalid/a.jar", mod.url());
        assertEquals("abc", mod.sha1());
        assertEquals(123, mod.sizeBytes());
        assertTrue(mod.clientRequired());
        assertFalse(mod.clientUnsupported());
        assertTrue(mod.downloadable());

        ModpackFile bundled = read.packFiles().get(1);
        assertEquals("config/pack.cfg", bundled.path());
        assertEquals("overrides/config/pack.cfg", bundled.archiveEntry());
        assertFalse(bundled.downloadable(), "a bundled file is unpacked from the pack, not downloaded");

        assertEquals(List.of("999/111 -- Gone"), read.unresolvedFiles());
    }

    @Test
    void aRecordFromAnOlderBuildStillReads(@TempDir Path instance) throws Exception {
        Files.writeString(instance.resolve("modpack.json"),
                "{\"name\":\"Old Pack\",\"mcVersion\":\"1.20.1\",\"loader\":\"Forge\"}");

        ModpackMeta read = ModpackMeta.read(instance);

        assertNotNull(read);
        assertEquals("Old Pack", read.name);
        assertTrue(read.packFiles().isEmpty(), "an older record simply has no manifest to verify");
        assertFalse(read.managesFiles());
    }
}
