package com.deylauncher.servers;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chunking is what makes a large world uploadable, and it is also the step where a bug corrupts somebody's
 * save, so split/join is pinned hard here -- including the failure cases, which must throw rather than
 * quietly hand back a short world.
 */
class WorldChunkerTest {

    private static byte[] bytes(int count) {
        byte[] out = new byte[count];
        for (int i = 0; i < count; i++) out[i] = (byte) (i % 251);
        return out;
    }

    @Test
    void splitProducesOrderedPartsAndJoinRebuildsThemExactly() {
        byte[] payload = bytes(1000);
        List<WorldManifest.Part> parts = WorldChunker.split(payload, 300);

        assertEquals(4, parts.size());
        assertEquals(0, parts.get(0).index);
        assertEquals("part-000.bin", parts.get(0).name);
        assertEquals("part-003.bin", parts.get(3).name);
        assertEquals(100, parts.get(3).size);

        List<byte[]> chunks = new ArrayList<>();
        for (WorldManifest.Part part : parts) {
            int offset = part.index * 300;
            chunks.add(java.util.Arrays.copyOfRange(payload, offset, offset + (int) part.size));
        }
        assertArrayEquals(payload, WorldChunker.join(chunks, 1000, WorldChunker.sha256(payload)));
    }

    @Test
    void anEmptyPayloadSplitsIntoNothingRatherThanOneEmptyPart() {
        assertTrue(WorldChunker.split(new byte[0], 100).isEmpty());
        assertTrue(WorldChunker.split(null, 100).isEmpty());
    }

    @Test
    void partNamesSortInOrderAsStrings() {
        // A directory listing (or a human reading the repo) must see part-002 before part-010.
        assertTrue(WorldChunker.partName(2).compareTo(WorldChunker.partName(10)) < 0);
        assertEquals("part-000.bin", WorldChunker.partName(0));
    }

    @Test
    void aMissingPartIsRefusedInsteadOfProducingATruncatedWorld() {
        byte[] payload = bytes(500);
        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                WorldChunker.join(List.of(java.util.Arrays.copyOfRange(payload, 0, 100)), 500, null));
        assertTrue(error.getMessage().contains("incomplete"));
    }

    @Test
    void aCorruptPartIsCaughtByTheChecksum() {
        byte[] payload = bytes(200);
        byte[] tampered = payload.clone();
        tampered[7] = (byte) (tampered[7] + 1);
        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                WorldChunker.join(List.of(tampered), 200, WorldChunker.sha256(payload)));
        assertTrue(error.getMessage().contains("checksum"));
    }

    @Test
    void aNullExpectedChecksumSkipsTheIntegrityCheckRatherThanFailing() {
        byte[] payload = bytes(10);
        assertArrayEquals(payload, WorldChunker.join(List.of(payload), 10, null));
        assertArrayEquals(payload, WorldChunker.join(List.of(payload), 0, null));
    }

    @Test
    void manifestDescribesWhatWasPushed() {
        byte[] payload = bytes(700);
        WorldManifest manifest = WorldChunker.manifestFor(payload, false, "world.zip", "Alice", null, 300);
        assertEquals(3, manifest.partCount);
        assertEquals(700, manifest.totalBytes);
        assertEquals(WorldChunker.sha256(payload), manifest.sha256);
        assertTrue(manifest.isUsable());
        assertTrue(manifest.describe().contains("full world"));
    }

    @Test
    void aSettingsOnlyManifestSaysSo() {
        byte[] payload = bytes(50);
        WorldManifest manifest = WorldChunker.manifestFor(payload, true, "world.zip", "Alice", "too big", 300);
        assertTrue(manifest.settingsOnly);
        assertTrue(manifest.describe().contains("settings only"));
    }

    @Test
    void aManifestWithoutPartsIsNotUsable() {
        WorldManifest empty = new WorldManifest();
        empty.totalBytes = 0;
        assertTrue(!empty.isUsable());
    }

    @Test
    void sha256IsStableAndContentDependent() {
        assertEquals(WorldChunker.sha256(bytes(5)), WorldChunker.sha256(bytes(5)));
        assertNotEquals(WorldChunker.sha256(bytes(5)), WorldChunker.sha256(bytes(6)));
        assertEquals(64, WorldChunker.sha256(bytes(5)).length());
    }

    @Test
    void humanSizeReadsSensibly() {
        assertEquals("512 B", WorldChunker.humanSize(512));
        assertEquals("1.0 KB", WorldChunker.humanSize(1024));
        assertEquals("1.0 MB", WorldChunker.humanSize(1024 * 1024));
        assertEquals("1.00 GB", WorldChunker.humanSize(1024L * 1024L * 1024L));
    }

    @Test
    void archiveNamesAreSafeForARepoPath() {
        String name = WorldChunker.archiveName("my world/1", 0L);
        assertTrue(name.startsWith("my_world_1-"));
        assertTrue(name.endsWith(".zip"));
        assertTrue(WorldChunker.archiveName(null, 0L).startsWith("world-"));
    }
}
