package com.deylauncher.servers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The link file is how a moderator sees a shared server BEFORE installing it, so the "granted but not
 * installed" state has to survive a restart exactly as written.
 */
class SemiHostedLinkStoreTest {

    private static SemiHostedLinkStore.Link granted(String serverId, String repo) {
        SemiHostedLinkStore.Link link = new SemiHostedLinkStore.Link();
        link.serverId = serverId;
        link.repo = repo;
        link.name = "My SMP";
        link.ownerUuid = "uuid-owner";
        link.ownerUsername = "Owner";
        link.role = ManagerRole.STARTER.wire();
        return link;
    }

    @Test
    void aGrantedServerIsRememberedWithNoLocalFolderYet(@TempDir Path tmp) {
        SemiHostedLinkStore store = new SemiHostedLinkStore(tmp);
        SemiHostedLinkStore.Link incoming = granted("cloud-1", "DeyLauncher-Servers");
        incoming.alias = "mySMP";
        store.upsert(incoming);

        SemiHostedLinkStore.Link link = store.find("cloud-1");
        assertEquals("DeyLauncher-Servers", link.repo);
        assertEquals(ManagerRole.STARTER, link.parsedRole());
        assertEquals("dey|mySMP", link.deyAddress());
        assertNull(link.localServerId);
        assertFalse(link.isInstalledLocally(), "no install yet -- this is what shows the Install screen");
    }

    @Test
    void markingInstalledLinksTheLocalFolder(@TempDir Path tmp) {
        SemiHostedLinkStore store = new SemiHostedLinkStore(tmp);
        store.upsert(granted("cloud-1", "DeyLauncher-Servers"));

        store.markInstalled("cloud-1", "local-9");

        SemiHostedLinkStore.Link link = store.find("cloud-1");
        assertEquals("local-9", link.localServerId);
        assertTrue(link.isInstalledLocally());
        assertEquals("cloud-1", store.findForLocal("local-9").serverId);
        assertNull(store.findForLocal("local-other"));
    }

    @Test
    void linksSurviveAReload(@TempDir Path tmp) {
        new SemiHostedLinkStore(tmp).upsert(granted("cloud-1", "DeyLauncher-Servers1"));
        SemiHostedLinkStore reloaded = new SemiHostedLinkStore(tmp);
        assertEquals(1, reloaded.list().size());
        assertEquals("DeyLauncher-Servers1", reloaded.find("cloud-1").repo);
    }

    @Test
    void upsertReplacesRatherThanDuplicating(@TempDir Path tmp) {
        SemiHostedLinkStore store = new SemiHostedLinkStore(tmp);
        store.upsert(granted("cloud-1", "DeyLauncher-Servers"));
        SemiHostedLinkStore.Link again = granted("cloud-1", "DeyLauncher-Servers1");
        again.role = ManagerRole.ADMINISTRATOR.wire();
        store.upsert(again);

        assertEquals(1, store.list().size());
        assertEquals("DeyLauncher-Servers1", store.find("cloud-1").repo);
        assertEquals(ManagerRole.ADMINISTRATOR, store.find("cloud-1").parsedRole());
    }

    @Test
    void removeIsReportedAndPersisted(@TempDir Path tmp) {
        SemiHostedLinkStore store = new SemiHostedLinkStore(tmp);
        store.upsert(granted("cloud-1", "r"));
        assertTrue(store.remove("cloud-1"));
        assertFalse(store.remove("cloud-1"));
        assertTrue(new SemiHostedLinkStore(tmp).list().isEmpty());
    }

    @Test
    void aSyncFailureIsRecordedSoTheCardCanExplainItself(@TempDir Path tmp) {
        SemiHostedLinkStore store = new SemiHostedLinkStore(tmp);
        store.upsert(granted("cloud-1", "r"));
        store.recordSync("cloud-1", "GitHub returned 403");
        assertEquals("GitHub returned 403", store.find("cloud-1").lastSyncError);
        store.recordSync("cloud-1", null);
        assertNull(store.find("cloud-1").lastSyncError);
    }

    @Test
    void aCorruptFileReadsAsNoLinksInsteadOfCrashing(@TempDir Path tmp) throws Exception {
        java.nio.file.Files.writeString(tmp.resolve("semi-hosted.json"), "{ not json");
        assertTrue(new SemiHostedLinkStore(tmp).list().isEmpty());
    }
}
