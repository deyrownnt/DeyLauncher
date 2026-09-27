package com.deylauncher.servers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lease is what makes "only one PC hosts this server" work without a server of our own, and lease
 * EXPIRY is what recovers a server whose host died. Both directions matter, so both are pinned.
 */
class ServerRuntimeDocTest {

    @Test
    void aFreshDocumentIsStopped() {
        ServerRuntimeDoc doc = new ServerRuntimeDoc();
        assertFalse(doc.claimsRunning());
        assertFalse(doc.isLive(System.currentTimeMillis(), 0));
        assertEquals(ServerRuntimeDoc.STOPPED, doc.state);
    }

    @Test
    void aClaimedDocumentIsLiveUntilItsLeaseRunsOut() {
        ServerRuntimeDoc doc = new ServerRuntimeDoc();
        doc.leaseSeconds = 60;
        doc.claimBy("uuid-a", "Alice", "ONLINE");

        assertTrue(doc.claimsRunning());
        assertTrue(doc.isLive(System.currentTimeMillis(), 0));
        // Just inside the lease.
        assertTrue(doc.isLive(doc.leaseRenewedAt + 59_000, 0));
        // Past it: the host is assumed gone, so somebody else may take over.
        assertFalse(doc.isLive(doc.leaseRenewedAt + 61_000, 0));
    }

    @Test
    void graceAbsorbsClockDriftBetweenTwoPcs() {
        ServerRuntimeDoc doc = new ServerRuntimeDoc();
        doc.leaseSeconds = 60;
        doc.claimBy("uuid-a", "Alice", "ONLINE");
        long justPast = doc.leaseRenewedAt + 65_000;
        assertFalse(doc.isLive(justPast, 0));
        assertTrue(doc.isLive(justPast, 30_000), "the grace window keeps a live server from being stolen");
    }

    @Test
    void onlyTheCurrentHostMayExtendTheLease() {
        ServerRuntimeDoc doc = new ServerRuntimeDoc();
        doc.leaseSeconds = 60;
        doc.claimBy("uuid-a", "Alice", "ONLINE");
        long before = doc.leaseRenewedAt;

        assertFalse(doc.renewLease("uuid-b"), "a bystander must not extend somebody else's claim");
        assertEquals(before, doc.leaseRenewedAt);
        assertTrue(doc.renewLease("uuid-a"));
        assertTrue(doc.leaseRenewedAt >= before);
    }

    @Test
    void aRenewalAfterExpiryDoesNotResurrectAStoppedDocument() {
        ServerRuntimeDoc doc = new ServerRuntimeDoc();
        doc.claimBy("uuid-a", "Alice", "ONLINE");
        doc.stopWith(ServerRuntimeDoc.STOP_EXPIRED, "uuid-a");
        assertFalse(doc.renewLease("uuid-a"), "a stopped document is never renewable");
    }

    @Test
    void stoppingClearsEverythingThatOnlyMakesSenseWhileLive() {
        ServerRuntimeDoc doc = new ServerRuntimeDoc();
        doc.leaseSeconds = 60;
        doc.claimBy("uuid-a", "Alice", "ONLINE");
        doc.publicAddress = "practice-downhill.ply.gg";
        doc.onlinePlayers.add("Bob");

        doc.stopWith(ServerRuntimeDoc.STOP_FORCED, "uuid-a");

        assertFalse(doc.claimsRunning());
        assertNull(doc.publicAddress, "a stale address would keep handing players a dead server");
        assertTrue(doc.onlinePlayers.isEmpty());
        assertEquals(0, doc.leaseRenewedAt);
        assertEquals(ServerRuntimeDoc.STOP_FORCED, doc.lastStopCause);
        assertTrue(doc.lastStoppedAt > 0);
    }

    @Test
    void hostedByMessageNamesTheHolderAndTheAddress() {
        ServerRuntimeDoc doc = new ServerRuntimeDoc();
        doc.claimBy("uuid-a", "Alice", "ONLINE");
        doc.publicAddress = "practice-downhill.ply.gg";
        String message = doc.hostedByMessage();
        assertTrue(message.contains("Alice"));
        assertTrue(message.contains("practice-downhill.ply.gg"));
    }

    @Test
    void normalizedRepairsADamagedDocumentInsteadOfLeavingNulls() {
        ServerRuntimeDoc doc = new ServerRuntimeDoc();
        doc.state = null;
        doc.onlinePlayers = null;
        doc.leaseSeconds = 0;
        doc.normalized();
        assertEquals(ServerRuntimeDoc.STOPPED, doc.state);
        assertTrue(doc.onlinePlayers.isEmpty());
        assertTrue(doc.leaseSeconds > 0);
    }
}
