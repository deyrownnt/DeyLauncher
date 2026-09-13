package com.deylauncher.server;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the small persistence additions: renaming a bookmarked server, and the visibility default. */
class AddedServersStoreTest {

    @Test
    void rename_changesOnlyTheNameAndPersists(@TempDir Path tmp) {
        AddedServersStore store = new AddedServersStore(tmp);
        store.add("Old name", "mc.example.com:25565");
        String id = store.list().get(0).id();

        store.rename(id, "  New name  ");
        var list = store.list();
        assertEquals(1, list.size());
        assertEquals("New name", list.get(0).name()); // trimmed
        assertEquals("mc.example.com:25565", list.get(0).address()); // address untouched

        // A blank rename is ignored rather than wiping the name.
        store.rename(id, "   ");
        assertEquals("New name", store.list().get(0).name());
    }

    @Test
    void rename_unknownIdIsANoOp(@TempDir Path tmp) {
        AddedServersStore store = new AddedServersStore(tmp);
        store.add("Keep", "host:25565");
        store.rename("no-such-id", "Other");
        assertEquals("Keep", store.list().get(0).name());
    }

    @Test
    void serverInstance_missingVisibilityFieldDefaultsToVisible() {
        // Existing server.json files predate the field. Gson runs the no-arg constructor (whose
        // initializers set true) and only overwrites fields actually present, so old servers default
        // to being visible rather than silently becoming hidden.
        ServerInstance s = new Gson().fromJson("{\"name\":\"Old\"}", ServerInstance.class);
        assertNotNull(s);
        assertTrue(s.visibleToFriends);
    }
}