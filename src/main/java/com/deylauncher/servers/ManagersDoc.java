package com.deylauncher.servers;

import java.util.ArrayList;
import java.util.List;

/**
 * Who may manage a semi self-hosted server, and with which role -- {@code servers/&lt;id&gt;/managers.json}.
 *
 * <p>Kept in its own file rather than inside {@link HostedServer} because it is the one document that
 * changes on its own schedule (the owner grants someone at 9pm; the world is pushed at 10pm) and
 * because a grant has to be readable <i>before</i> a moderator has ever installed the server: the
 * "servers shared with you" list is built from it.
 *
 * <p>The owner is intentionally NOT listed in {@link #managers}: ownership is a property of the server
 * ({@link HostedServer#ownerUuid}), and duplicating it here would create two sources of truth for the
 * most important fact about the server. Managers listed here are always grants by the owner.
 *
 * <p>{@link #forceStopRequestedAt} lives here too: it is "the owner has asked the current host to
 * stop", which is a moderation action on this server, and putting it beside the manager list means one
 * fetch answers both "may I host this?" and "is the owner recalling my session?".
 */
public class ManagersDoc {

    public int version = 1;
    public List<ManagerGrant> managers = new ArrayList<>();

    /** Set when the owner asks whoever is hosting to stop; cleared once the host complies or is replaced. */
    public Long forceStopRequestedAt;
    public String forceStopRequestedBy;
    public String forceStopRequestedByName;
    public String forceStopReason;

    /** Who was hosting when the request was made, so a stale request never stops a later, different host. */
    public String forceStopTargetHostUuid;

    /** Repairs a document read back from the repo so callers never see null fields. */
    public ManagersDoc normalized() {
        if (managers == null) managers = new ArrayList<>();
        managers.removeIf(m -> m == null || m.uuid == null || m.uuid.isBlank());
        for (ManagerGrant m : managers) {
            // Anything this build does not recognise becomes the LEAST powerful grantable role. A missing
            // role, a typo, or a role invented by a future build must never read as "administrator".
            if (ManagerRole.fromWire(m.role) == null) m.role = ManagerRole.STARTER.wire();
        }
        return this;
    }

    /** The grant for {@code uuid}, or null when this person is not a manager of the server. */
    public ManagerGrant find(String uuid) {
        if (uuid == null) return null;
        for (ManagerGrant m : managers) {
            if (uuid.equals(m.uuid)) return m;
        }
        return null;
    }

    /**
     * The role {@code uuid} holds on this server, given the server's owner.
     *
     * <p>An unknown or missing grant is NOT a role -- it returns null, and callers must treat null as
     * "no access at all". A grant whose role string is unrecognised was already downgraded to
     * {@code STARTER} by {@link #normalized()}, so a future/typo'd role can never widen access.
     */
    public ManagerRole roleOf(String ownerUuid, String uuid) {
        if (uuid == null) return null;
        if (uuid.equals(ownerUuid)) return ManagerRole.OWNER;
        ManagerGrant m = find(uuid);
        return m == null ? null : ManagerRole.fromWire(m.role);
    }

    /** Adds or re-roles a manager. Re-granting an existing manager updates the role in place. */
    public void grant(String uuid, String username, ManagerRole role, String addedBy) {
        if (uuid == null || role == null || !role.isGrantable()) return;
        ManagerGrant existing = find(uuid);
        if (existing != null) {
            existing.username = username;
            existing.role = role.wire();
            existing.roleChangedAt = System.currentTimeMillis();
            existing.roleChangedBy = addedBy;
            return;
        }
        ManagerGrant g = new ManagerGrant();
        g.uuid = uuid;
        g.username = username;
        g.role = role.wire();
        g.addedAt = System.currentTimeMillis();
        g.addedBy = addedBy;
        managers.add(g);
    }

    /** Removes a manager; true when a grant was actually removed. */
    public boolean revoke(String uuid) {
        if (uuid == null) return false;
        return managers.removeIf(m -> uuid.equals(m.uuid));
    }

    /** How many people besides the owner may manage this server. */
    public int managerCount() {
        return managers == null ? 0 : managers.size();
    }
}
