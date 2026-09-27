package com.deylauncher.servers;

/**
 * One person's permission on a semi self-hosted server they do not own: who they are, which
 * {@link ManagerRole} the owner gave them, and when.
 *
 * <p>A grant is deliberately identified by the account <b>uuid</b>, with the username kept only as a
 * label for the UI. Usernames change (a Mojang rename) and, crucially, an offline account's uuid is
 * derived from its name alone -- so matching grants by name would let someone take over another
 * person's moderation simply by typing their name. The uuid is the identity; the name is decoration.
 */
public class ManagerGrant {

    /** The manager's account uuid -- the only field a permission check ever trusts. */
    public String uuid;

    /** Display label only (kept current whenever the manager's launcher writes to this server). */
    public String username;

    /** {@link ManagerRole#wire()} of the granted role. */
    public String role = ManagerRole.STARTER.wire();

    public long addedAt = System.currentTimeMillis();
    public String addedBy;

    /** Set when the owner changes an existing grant's role (null when it was never changed). */
    public Long roleChangedAt;
    public String roleChangedBy;

    public ManagerGrant() {
    }

    public ManagerGrant(String uuid, String username, ManagerRole role, String addedBy) {
        this.uuid = uuid;
        this.username = username;
        this.role = role.wire();
        this.addedBy = addedBy;
    }

    /** The parsed role, or null when the stored value is not one this build understands. */
    public ManagerRole parsedRole() {
        return ManagerRole.fromWire(role);
    }
}
