package com.deylauncher.server;

import com.deylauncher.version.VersionManifest;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how a version change is classified, because the classification decides whether the world gets
 * backed up before an older server is allowed near it. The reported bug ("changed the version to a
 * lower one and it crashes on the next launch") only becomes safe if a downgrade is actually
 * recognised -- and if an id we cannot order is treated as risky rather than as an upgrade.
 */
class VersionChangePlanTest {

    /** A manifest in the order Mojang publishes it: newest first. */
    private static List<VersionManifest.VersionEntry> manifest(String... idsNewestFirst) {
        List<VersionManifest.VersionEntry> out = new ArrayList<>();
        for (String id : idsNewestFirst) {
            out.add(new VersionManifest.VersionEntry(id, "release", "https://example.invalid/" + id, null));
        }
        return out;
    }

    @Test
    void manifestOrderDecidesWhenBothVersionsAreInIt() {
        var list = manifest("1.21.11", "1.21.5", "1.20.6", "1.20.1");

        assertEquals(VersionChangePlan.Kind.DOWNGRADE, VersionChangePlan.classify("1.21.11", "1.20.1", list));
        assertEquals(VersionChangePlan.Kind.UPGRADE, VersionChangePlan.classify("1.20.1", "1.21.11", list));
        assertEquals(VersionChangePlan.Kind.SAME, VersionChangePlan.classify("1.20.1", "1.20.1", list));
    }

    @Test
    void numericOrderCoversVersionsTheManifestDoesNotKnow() {
        // "26.2" is DeyLauncher's synthetic seed and is genuinely absent from Mojang's manifest, so
        // only the numeric fallback can order it -- the exact case a server created on 26.2 hits when
        // it is changed to a real release.
        var list = manifest("1.21.5", "1.21.1", "1.20.1");

        assertEquals(VersionChangePlan.Kind.DOWNGRADE, VersionChangePlan.classify("26.2", "1.20.1", list));
        assertEquals(VersionChangePlan.Kind.UPGRADE, VersionChangePlan.classify("1.20.1", "26.2", list));
        // Numeric tuples also order patch levels correctly (11 > 5, not string order).
        assertEquals(VersionChangePlan.Kind.DOWNGRADE, VersionChangePlan.classify("1.21.11", "1.21.5", null));
        // Trailing zeros are the same release.
        assertEquals(VersionChangePlan.Kind.SAME, VersionChangePlan.classify("1.20", "1.20.0", null));
    }

    @Test
    void unorderedIdsAreUnknownAndTreatedAsRisky() {
        // Snapshots read as "w" + week + letter: not a dotted number, so they can't be ordered.
        assertEquals(VersionChangePlan.Kind.UNKNOWN, VersionChangePlan.classify("26w31a", "1.20.1", manifest()));
        assertEquals(VersionChangePlan.Kind.UNKNOWN, VersionChangePlan.classify(null, "1.20.1", manifest()));
        assertEquals(VersionChangePlan.Kind.UNKNOWN, VersionChangePlan.classify("1.20.1", "  ", manifest()));

        assertTrue(VersionChangePlan.Kind.DOWNGRADE.needsWorldBackupOffer());
        assertTrue(VersionChangePlan.Kind.UNKNOWN.needsWorldBackupOffer());
        assertFalse(VersionChangePlan.Kind.UPGRADE.needsWorldBackupOffer());
        assertFalse(VersionChangePlan.Kind.SAME.needsWorldBackupOffer());
    }

    @Test
    void worldFoldersFollowTheServersLevelName() {
        assertEquals(List.of("world", "world_nether", "world_the_end"),
                VersionChangePlan.worldDirNames(null));
        assertEquals(List.of("myworld", "myworld_nether", "myworld_the_end"),
                VersionChangePlan.worldDirNames("  myworld  "));
    }

    @Test
    void backupNameKeepsTheOriginalNameTheVersionAndTheDate() {
        String name = VersionChangePlan.backupDirName("world", "1.21.11",
                LocalDateTime.of(2026, 9, 16, 19, 4, 32));
        assertEquals("world-1.21.11-20260916-190432", name);

        // A missing version still produces a usable name rather than "null".
        assertTrue(VersionChangePlan.backupDirName("mods", null, LocalDateTime.of(2026, 1, 2, 3, 4, 5))
                .startsWith("mods-previous-20260102-030405"));
    }
}