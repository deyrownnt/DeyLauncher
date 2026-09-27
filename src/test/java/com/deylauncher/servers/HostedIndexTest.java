package com.deylauncher.servers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The index is the one read that lists every shared server, so its lookups and repair rules matter. */
class HostedIndexTest {

    private static HostedIndex.Entry entry(String id, String owner, String alias, String repo) {
        HostedIndex.Entry e = new HostedIndex.Entry();
        e.serverId = id;
        e.ownerUuid = owner;
        e.ownerUsername = owner;
        e.alias = alias;
        e.repo = repo;
        e.name = "Server " + id;
        return e;
    }

    @Test
    void upsertAddsThenReplacesInPlace() {
        HostedIndex index = new HostedIndex().normalized();
        index.upsert(entry("1", "alice", "one", "DeyLauncher-Servers"));
        index.upsert(entry("2", "alice", "two", "DeyLauncher-Servers"));
        assertEquals(2, index.servers.size());

        HostedIndex.Entry updated = entry("1", "alice", "one", "DeyLauncher-Servers");
        updated.minecraftVersion = "1.21";
        index.upsert(updated);
        assertEquals(2, index.servers.size(), "an update must not append a duplicate");
        assertEquals("1.21", index.find("1").minecraftVersion);
        assertEquals(0, index.servers.indexOf(index.find("1")), "position is preserved across updates");
    }

    @Test
    void removeReportsWhetherAnythingWasRemoved() {
        HostedIndex index = new HostedIndex().normalized();
        index.upsert(entry("1", "alice", "one", "r"));
        assertTrue(index.remove("1"));
        assertTrue(!index.remove("1"));
        assertNull(index.find("1"));
    }

    @Test
    void ownedByFeedsTheCloudSlotLimit() {
        HostedIndex index = new HostedIndex().normalized();
        index.upsert(entry("1", "alice", "one", "r"));
        index.upsert(entry("2", "bob", "two", "r"));
        index.upsert(entry("3", "alice", "three", "r"));
        assertEquals(2, index.ownedBy("alice").size());
        assertEquals(1, index.ownedBy("bob").size());
        assertTrue(index.ownedBy(null).isEmpty());
    }

    @Test
    void aliasLookupIsCaseInsensitive() {
        HostedIndex index = new HostedIndex().normalized();
        index.upsert(entry("1", "alice", "mySMP", "r"));
        assertNotNull(index.findByAlias("mysmp"));
        assertNotNull(index.findByAlias("  MySMP  "));
        assertEquals("1", index.findByAlias("MYSMP").serverId);
        assertNull(index.findByAlias("other"));
        assertNull(index.findByAlias(null));
    }

    @Test
    void aDamagedIndexIsRepairedRatherThanExploding() {
        HostedIndex index = new HostedIndex();
        index.servers = null;
        index.normalized();
        assertNotNull(index.servers);
        index.upsert(null);
        index.upsert(entry(null, "alice", "x", "r")); // a server with no id can never be addressed
        assertTrue(index.servers.isEmpty());
    }

    @Test
    void aServerCopiesItsOwnFieldsIntoTheIndexEntry() {
        HostedServer meta = new HostedServer();
        meta.serverId = "abc";
        meta.repo = "DeyLauncher-Servers1";
        meta.alias = "mySMP";
        meta.name = "My SMP";
        meta.ownerUuid = "uuid-alice";
        meta.ownerUsername = "Alice";
        meta.ownerAccountType = "ONLINE";
        meta.type = "FABRIC";
        meta.minecraftVersion = "1.21";
        meta.openToModerators = false;

        HostedIndex.Entry e = meta.toIndexEntry();
        assertEquals("abc", e.serverId);
        assertEquals("DeyLauncher-Servers1", e.repo, "the repo must travel with the entry");
        assertEquals("dey|mySMP", e.deyAddress());
        assertEquals("ONLINE", e.ownerAccountType);
        assertEquals("FABRIC", e.type);
        assertTrue(e.isOwnedBy("uuid-alice"));
        assertTrue(!e.openToModerators);
    }

    @Test
    void touchedByRefreshesTheTimestampAndRecordsWhoWrote() {
        HostedServer meta = new HostedServer();
        meta.updatedAt = 1L;
        assertSame(meta, meta.touchedBy("Alice"));
        assertEquals("Alice", meta.updatedBy);
        assertTrue(meta.updatedAt > 1L);
    }

    @Test
    void deyAddressIsNullWithoutAnAlias() {
        HostedServer meta = new HostedServer();
        assertNull(meta.deyAddress());
        meta.alias = "mySMP";
        assertEquals("dey|mySMP", meta.deyAddress());
    }
}
