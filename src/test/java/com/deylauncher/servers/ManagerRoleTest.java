package com.deylauncher.servers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The role matrix is the contract the whole moderation feature hangs on, so it is pinned here rather than
 * trusted to the UI: if a tab or a toggle changes hands, one of these assertions has to change with it.
 */
class ManagerRoleTest {

    @Test
    void ownerGetsEverything() {
        for (ServerTab tab : ServerTab.values()) {
            assertTrue(ManagerRole.OWNER.canOpen(tab), "owner must keep " + tab);
        }
        assertTrue(ManagerRole.OWNER.canChangeVersion());
        assertTrue(ManagerRole.OWNER.canManageRoles());
        assertTrue(ManagerRole.OWNER.canForceStopHost());
        assertTrue(ManagerRole.OWNER.canDeleteServerEverywhere());
        assertTrue(ManagerRole.OWNER.canEditSharedHostingPolicy());
        assertFalse(ManagerRole.OWNER.canCancelOwnModeration(), "the owner has nothing to cancel");
    }

    @Test
    void administratorKeepsTheWorkTabsButLosesVersionsAndPermissions() {
        assertTrue(ManagerRole.ADMINISTRATOR.canOpen(ServerTab.CONSOLE));
        assertTrue(ManagerRole.ADMINISTRATOR.canOpen(ServerTab.PROPERTIES));
        assertTrue(ManagerRole.ADMINISTRATOR.canOpen(ServerTab.PLAYERS));
        assertTrue(ManagerRole.ADMINISTRATOR.canOpen(ServerTab.ADDONS));
        assertTrue(ManagerRole.ADMINISTRATOR.canOpen(ServerTab.FILES));
        assertTrue(ManagerRole.ADMINISTRATOR.canOpen(ServerTab.SETTINGS));
        assertFalse(ManagerRole.ADMINISTRATOR.canOpen(ServerTab.PERMISSIONS));
        assertFalse(ManagerRole.ADMINISTRATOR.canChangeVersion());
        assertFalse(ManagerRole.ADMINISTRATOR.canManageRoles());
        assertFalse(ManagerRole.ADMINISTRATOR.canForceStopHost());
    }

    @Test
    void administratorInSettingsGetsDeleteFromPcAndCancelModerationOnly() {
        assertTrue(ManagerRole.ADMINISTRATOR.canDeleteFromThisPc());
        assertTrue(ManagerRole.ADMINISTRATOR.canCancelOwnModeration());
        assertFalse(ManagerRole.ADMINISTRATOR.canDeleteServerEverywhere());
        assertTrue(ManagerRole.ADMINISTRATOR.canSyncCloud());
    }

    @Test
    void starterIsLimitedToThreeTabs() {
        assertTrue(ManagerRole.STARTER.canOpen(ServerTab.CONSOLE));
        assertTrue(ManagerRole.STARTER.canOpen(ServerTab.SETTINGS));
        assertTrue(ManagerRole.STARTER.canOpen(ServerTab.PERMISSIONS));
        assertFalse(ManagerRole.STARTER.canOpen(ServerTab.PROPERTIES));
        assertFalse(ManagerRole.STARTER.canOpen(ServerTab.PLAYERS));
        assertFalse(ManagerRole.STARTER.canOpen(ServerTab.ADDONS));
        assertFalse(ManagerRole.STARTER.canOpen(ServerTab.FILES));
    }

    @Test
    void starterConsoleCanJoinAndCancelModerationButCannotChangeVersionOrDeleteTheServer() {
        assertTrue(ManagerRole.STARTER.canStartStop());
        assertTrue(ManagerRole.STARTER.canShareOverInternet());
        assertTrue(ManagerRole.STARTER.canCancelOwnModeration());
        assertTrue(ManagerRole.STARTER.canDeleteFromThisPc());
        assertFalse(ManagerRole.STARTER.canChangeVersion());
        assertFalse(ManagerRole.STARTER.canDeleteServerEverywhere());
        assertFalse(ManagerRole.STARTER.canForceStopHost());
        assertFalse(ManagerRole.STARTER.canEditSharedHostingPolicy());
    }

    @Test
    void startersPermissionsTabIsRestrictedToViewAndSimulationDistance() {
        assertTrue(ManagerRole.STARTER.isPermissionsRestricted());
        assertTrue(ManagerRole.STARTER.canEditPermissionField("view-distance"));
        assertTrue(ManagerRole.STARTER.canEditPermissionField("simulation-distance"));
        assertTrue(ManagerRole.STARTER.canEditPermissionField("  VIEW-DISTANCE "));
        assertFalse(ManagerRole.STARTER.canEditPermissionField("max-players"));
        assertFalse(ManagerRole.STARTER.canEditPermissionField("white-list"));
        // An administrator has no Permissions tab at all, so no field is editable there either.
        assertFalse(ManagerRole.ADMINISTRATOR.canEditPermissionField("view-distance"));
        assertTrue(ManagerRole.OWNER.canEditPermissionField("max-players"));
    }

    @Test
    void onlyTheOwnerRoleIsNotGrantable() {
        assertFalse(ManagerRole.OWNER.isGrantable());
        assertTrue(ManagerRole.ADMINISTRATOR.isGrantable());
        assertTrue(ManagerRole.STARTER.isGrantable());
        assertEquals(2, ManagerRole.grantable().length);
    }

    @Test
    void wireRoundTripsAndUnknownValuesAreNull() {
        for (ManagerRole role : ManagerRole.values()) {
            assertEquals(role, ManagerRole.fromWire(role.wire()));
        }
        assertEquals(ManagerRole.STARTER, ManagerRole.fromWire("starter"));
        assertNull(ManagerRole.fromWire("SUPER_ADMIN"));
        assertNull(ManagerRole.fromWire(null));
        assertNull(ManagerRole.fromWire(""));
    }
}
