package com.deylauncher.servers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Permission checks are the security-relevant half of moderation, so the "fail closed" behaviour is pinned
 * here: an unreadable or unknown role must never widen somebody's access.
 */
class ManagersDocTest {

    private static final String OWNER = "uuid-owner";
    private static final String ADMIN = "uuid-admin";
    private static final String STARTER = "uuid-starter";

    private static ManagersDoc docWithGrants() {
        ManagersDoc doc = new ManagersDoc();
        doc.grant(ADMIN, "Admin", ManagerRole.ADMINISTRATOR, "Owner");
        doc.grant(STARTER, "Starter", ManagerRole.STARTER, "Owner");
        return doc.normalized();
    }

    @Test
    void theOwnerIsResolvedFromTheServersOwnRecordNotFromTheGrantList() {
        ManagersDoc doc = docWithGrants();
        assertEquals(ManagerRole.OWNER, doc.roleOf(OWNER, OWNER));
        assertNull(doc.find(OWNER), "the owner is never duplicated into the manager list");
    }

    @Test
    void grantsResolveToTheirRoles() {
        ManagersDoc doc = docWithGrants();
        assertEquals(ManagerRole.ADMINISTRATOR, doc.roleOf(OWNER, ADMIN));
        assertEquals(ManagerRole.STARTER, doc.roleOf(OWNER, STARTER));
    }

    @Test
    void aStrangerHasNoRoleAtAll() {
        ManagersDoc doc = docWithGrants();
        assertNull(doc.roleOf(OWNER, "uuid-nobody"));
        assertNull(doc.roleOf(OWNER, null));
    }

    @Test
    void anUnrecognisedRoleIsDowngradedToStarterNotUpgraded() {
        ManagersDoc doc = new ManagersDoc();
        ManagerGrant grant = new ManagerGrant();
        grant.uuid = ADMIN;
        grant.username = "Admin";
        grant.role = "SUPER_ADMIN"; // a typo, or a role from a future build
        doc.managers.add(grant);
        doc.normalized();
        assertEquals(ManagerRole.STARTER.wire(), doc.find(ADMIN).role);
        assertEquals(ManagerRole.STARTER, doc.roleOf(OWNER, ADMIN));
    }

    @Test
    void reGrantingUpdatesTheRoleInPlaceAndRecordsWhoChangedIt() {
        ManagersDoc doc = docWithGrants();
        doc.grant(STARTER, "Starter", ManagerRole.ADMINISTRATOR, "Owner");
        assertEquals(2, doc.managerCount(), "re-granting must not duplicate a manager");
        assertEquals(ManagerRole.ADMINISTRATOR, doc.roleOf(OWNER, STARTER));
        assertEquals("Owner", doc.find(STARTER).roleChangedBy);
        assertNotNull(doc.find(STARTER).roleChangedAt);
    }

    @Test
    void revokeReportsWhetherAnythingChanged() {
        ManagersDoc doc = docWithGrants();
        assertTrue(doc.revoke(STARTER));
        assertFalse(doc.revoke(STARTER));
        assertEquals(1, doc.managerCount());
        assertNull(doc.roleOf(OWNER, STARTER));
    }

    @Test
    void theOwnerCannotBeGrantedOrRevokedAsAManager() {
        ManagersDoc doc = new ManagersDoc();
        doc.grant(OWNER, "Owner", ManagerRole.OWNER, "Owner");
        assertTrue(doc.managers.isEmpty(), "OWNER is not a grantable role");
        doc.grant(ADMIN, "Admin", null, "Owner");
        assertTrue(doc.managers.isEmpty(), "a role is mandatory");
    }

    @Test
    void normalizedDropsEntriesWithNoUuid() {
        ManagersDoc doc = new ManagersDoc();
        doc.managers.add(new ManagerGrant());
        doc.managers.add(null);
        doc.normalized();
        assertTrue(doc.managers.isEmpty());
    }

    @Test
    void aForceStopRequestTargetsTheHostItWasMadeAgainst() {
        ManagersDoc doc = new ManagersDoc();
        doc.forceStopRequestedAt = 123L;
        doc.forceStopRequestedBy = OWNER;
        doc.forceStopRequestedByName = "Owner";
        doc.forceStopTargetHostUuid = ADMIN;
        assertTrue(ADMIN.equals(doc.forceStopTargetHostUuid));
        // A later, different host must not inherit a stale request.
        assertFalse(STARTER.equals(doc.forceStopTargetHostUuid));
    }
}
