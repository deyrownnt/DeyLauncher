package com.deylauncher.modpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The launch-time repair: what happens when a pack's mods go missing, which is exactly the case the
 * launcher used to leave to the player ("download each one and put it in the right folder yourself").
 */
class ModpackVerifierTest {

    /** Stands in for the network: writes a file of the expected size. */
    static final PackFileOps.Downloader WRITER = (url, dest, size) -> {
        try {
            if (dest.getParent() != null) Files.createDirectories(dest.getParent());
            Files.write(dest, new byte[(int) Math.max(1, size)]);
            return true;
        } catch (Exception e) {
            return false;
        }
    };

    @Test
    void restoresWhatIsMissingAndLeavesDisabledModsAlone(@TempDir Path instance, @TempDir Path elsewhere)
            throws Exception {
        write(instance.resolve("mods/present.jar"), 7);                 // already there, correct size
        write(instance.resolve("mods-disabled/off.jar"), 4);            // switched off on purpose

        Path bundledSource = elsewhere.resolve("pack.txt");
        Files.writeString(bundledSource, "hi");

        ModpackMeta meta = new ModpackMeta();
        meta.name = "Test Pack";
        meta.mcVersion = "1.20.1";
        meta.loader = "Forge";
        meta.files = List.of(
                ModpackFile.download("mods/present.jar", "https://example.invalid/present.jar",
                        null, null, 7, true, false, true, false),
                ModpackFile.download("mods/gone.jar", "https://example.invalid/gone.jar",
                        null, null, 5, true, false, true, false),
                ModpackFile.download("mods/off.jar", "https://example.invalid/off.jar",
                        null, null, 4, true, false, true, false),
                ModpackFile.bundled("config/pack.cfg", bundledSource.toString(), 2),
                ModpackFile.bundled("config/lost.cfg",
                        elsewhere.resolve("deleted-pack.zip").toString(), 3));

        ModpackVerifier.Report report = new ModpackVerifier(WRITER).verifyClient(meta, instance, null);

        assertEquals(5, report.checked());
        assertEquals(3, report.missing(), "gone.jar, the bundled config and the unrestorable one");
        assertEquals(2, report.restored());
        assertEquals(1, report.failed());
        assertEquals(1, report.skipped(), "a mod the user disabled must never be downloaded back");
        assertFalse(report.ok()); // one file could not be restored, and that must be visible

        assertTrue(Files.exists(instance.resolve("mods/gone.jar")), "the missing mod is downloaded again");
        assertTrue(Files.exists(instance.resolve("config/pack.cfg")), "a bundled file is restored from the pack");
        assertFalse(Files.exists(instance.resolve("config/lost.cfg")));
        assertFalse(Files.exists(instance.resolve("mods/off.jar")), "the disabled mod stays disabled");
        assertTrue(report.summary().contains("3 missing file(s)"), report.summary());
    }

    @Test
    void aHealthyPackIsReportedAsCompleteAndTouchesNothing(@TempDir Path instance) throws Exception {
        write(instance.resolve("mods/here.jar"), 9);
        ModpackMeta meta = new ModpackMeta();
        meta.name = "Healthy Pack";
        meta.files = List.of(ModpackFile.download("mods/here.jar", "https://example.invalid/here.jar",
                null, null, 9, true, false, true, false));

        PackFileOps.Downloader exploding = (url, dest, size) -> {
            throw new AssertionError("nothing may be downloaded for a complete pack");
        };
        ModpackVerifier.Report report = new ModpackVerifier(exploding).verifyClient(meta, instance, null);

        assertTrue(report.clean());
        assertTrue(report.ok());
        assertEquals(1, report.checked());
        assertEquals(0, report.restored());
        assertTrue(report.summary().contains("complete"), report.summary());
    }

    @Test
    void aWrongSizedFileIsReplacedRatherThanTrusted(@TempDir Path instance) throws Exception {
        write(instance.resolve("mods/half.jar"), 3); // truncated download from an earlier run
        ModpackMeta meta = new ModpackMeta();
        meta.name = "Truncated Pack";
        meta.files = List.of(ModpackFile.download("mods/half.jar", "https://example.invalid/half.jar",
                null, null, 12, true, false, true, false));

        ModpackVerifier.Report report = new ModpackVerifier(WRITER).verifyClient(meta, instance, null);

        assertEquals(1, report.missing());
        assertEquals(1, report.restored());
        assertEquals(12, Files.size(instance.resolve("mods/half.jar")));
    }

    @Test
    void aPackRecordWithNoManifestIsLeftAlone(@TempDir Path instance) {
        ModpackMeta meta = new ModpackMeta();
        meta.name = "Old Pack"; // installed by a build that didn't record its files

        ModpackVerifier.Report report = new ModpackVerifier(WRITER).verifyClient(meta, instance, null);

        assertEquals(0, report.checked());
        assertTrue(report.clean());
    }

    private static void write(Path file, int bytes) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[bytes]);
    }
}
