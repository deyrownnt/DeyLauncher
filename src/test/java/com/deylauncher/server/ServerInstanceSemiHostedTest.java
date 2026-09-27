package com.deylauncher.server;

import com.deylauncher.servers.ManagerRole;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The semi self-hosted fields were added to {@link ServerInstance} after server.json already existed on
 * people's PCs, so the two things that matter are: an old file still loads, and "is this server somebody
 * else's" is answered from the OWNER UUID rather than from a username.
 */
class ServerInstanceSemiHostedTest {

    @Test
    void aServerJsonWrittenBeforeThisFeatureStillLoadsAsANormalOwnedServer() {
        ServerInstance server = new Gson().fromJson("{\"name\":\"Old\",\"port\":25565}", ServerInstance.class);
        assertFalse(server.semiSelfHosted, "sharing must be opt-in, never inherited by an old server");
        assertNull(server.cloudAlias);
        assertNull(server.cloudServerId);
        assertNull(server.cloudRepo);
        assertTrue(server.openToModerators, "an untouched server keeps its moderators enabled");
        assertFalse(server.allowModeratorHostOnlyWhenPublic);
    }

    @Test
    void anUnownedServerBelongsToWhoeverIsUsingThisLauncher() {
        ServerInstance server = new ServerInstance("Mine", ServerType.VANILLA, "1.21");
        assertFalse(server.isGrantedToMe("uuid-me"));
        assertNull(server.myRoleOnGrantedServer("uuid-me"));
    }

    @Test
    void aGrantedServerKnowsItsRealOwnerAndItsOwnRole() {
        ServerInstance server = new ServerInstance("Shared", ServerType.FABRIC, "1.21");
        server.linkedOwnerUuid = "uuid-owner";
        server.linkedRole = ManagerRole.STARTER.wire();

        assertTrue(server.isGrantedToMe("uuid-me"));
        assertFalse(server.isGrantedToMe("uuid-owner"), "the owner is not a moderator of their own server");
        assertEquals(ManagerRole.STARTER, server.myRoleOnGrantedServer("uuid-me"));
    }

    @Test
    void anUnknownRoleNeverReadsAsFullAccess() {
        ServerInstance server = new ServerInstance("Shared", ServerType.VANILLA, "1.21");
        server.linkedOwnerUuid = "uuid-owner";
        server.linkedRole = "SUPER_ADMIN";
        assertNull(server.myRoleOnGrantedServer("uuid-me"));
    }

    @Test
    void theDeyAddressComesFromTheClaimedAlias() {
        ServerInstance server = new ServerInstance("My SMP", ServerType.PURPUR, "1.21");
        assertNull(server.deyAddress());
        server.cloudAlias = "mySMP";
        assertEquals("dey|mySMP", server.deyAddress());
    }
}
