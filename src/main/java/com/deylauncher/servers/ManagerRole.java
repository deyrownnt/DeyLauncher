package com.deylauncher.servers;

import java.util.Locale;

/**
 * What a person is allowed to do with a semi self-hosted server they did not create.
 *
 * <p>The owner always holds full control. Anyone else is added by the owner with <b>exactly one</b>
 * of the two roles below -- the choice is mandatory when the grant is made, because "which powers did
 * I hand out" must never be ambiguous later.
 *
 * <ul>
 *   <li><b>{@link #ADMINISTRATOR}</b> -- a co-host. Everything a full server manager needs: the
 *       console (start/stop, play, share over the Internet), and the Properties, Players, Addons and
 *       Files tabs. Two deliberate holes: <i>no</i> version changing (that can invalidate a world
 *       irreversibly, so it stays with the owner) and <i>no</i> Permissions tab at all (only the
 *       owner hands out roles). In Settings they get "delete this server from this PC" and "cancel my
 *       moderation" instead of the owner's permanent delete.</li>
 *   <li><b>{@link #STARTER}</b> -- the tightly limited role. The console (start/stop, play, share over
 *       the Internet) and the same restricted Settings actions as an administrator, plus a highly
 *       restricted Permissions tab where only the simulation distance and the view distance can be
 *       changed -- three tabs in total, and nothing that can damage the server's world or its setup.</li>
 * </ul>
 *
 * <p>Every rule lives here rather than in the UI (the same reason
 * {@link com.deylauncher.launch.ServerAddressMatch} holds the address-matching rule): the management
 * window asks this enum what to show, so a mis-gated button is a missing test here instead of a
 * scatter of {@code if} statements inside an 11k-line class.
 */
public enum ManagerRole {

    OWNER("Owner"),
    ADMINISTRATOR("Administrator"),
    STARTER("Starter");

    private final String displayName;

    ManagerRole(String displayName) {
        this.displayName = displayName;
    }

    /** Plain-English name shown in the role picker, the role pill on a card, and the docs. */
    public String displayName() {
        return displayName;
    }

    /** Stable value written to {@code managers.json}; renaming it would silently invalidate grants. */
    public String wire() {
        return name();
    }

    /** Lenient parse for anything read back from the repo -- anything unknown becomes null. */
    public static ManagerRole fromWire(String wire) {
        if (wire == null) return null;
        try {
            return valueOf(wire.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The two roles the owner can actually choose -- never OWNER (that cannot be granted). */
    public static ManagerRole[] grantable() {
        return new ManagerRole[]{ADMINISTRATOR, STARTER};
    }

    /** True for the two roles the owner may hand out. */
    public boolean isGrantable() {
        return this == ADMINISTRATOR || this == STARTER;
    }

    /**
     * Whether this role may open {@code tab} at all. A tab the role cannot open is not rendered as a
     * disabled tab -- it is left out, because showing a locked tab only advertises what the person
     * does not have.
     */
    public boolean canOpen(ServerTab tab) {
        if (tab == null) return false;
        if (this == OWNER) return true;
        if (this == ADMINISTRATOR) return tab != ServerTab.PERMISSIONS;
        // STARTER: console + the (restricted) settings and permissions tabs, nothing else.
        return tab == ServerTab.CONSOLE || tab == ServerTab.SETTINGS || tab == ServerTab.PERMISSIONS;
    }

    // ---------------------------------------------------------------------------------------------
    // Console
    // ---------------------------------------------------------------------------------------------

    /** Start / stop the server process, or play on it -- every role may. */
    public boolean canStartStop() {
        return true;
    }

    /** Share the server over the Internet through the free playit tunnel. */
    public boolean canShareOverInternet() {
        return true;
    }

    /** Send raw commands to the running server's console. */
    public boolean canSendConsoleCommands() {
        return true;
    }

    /**
     * Changing the Minecraft version (or the server software type). Owner only: a downgrade can make a
     * world unreadable, and the change is applied to the one world everybody shares.
     */
    public boolean canChangeVersion() {
        return this == OWNER;
    }

    // ---------------------------------------------------------------------------------------------
    // Server data (Properties / Players / Addons / Files)
    // ---------------------------------------------------------------------------------------------

    /**
     * Whether this role may change the shared server's own setup -- {@code server.properties},
     * players, the addon list and the server files.
     *
     * <p>Administrators are allowed; Starters are not. The owner may additionally force the server
     * back onto their own copy while somebody else is hosting it, which is how an owner's edit always
     * wins in the end.
     */
    public boolean canEditServerData() {
        return this == OWNER || this == ADMINISTRATOR;
    }

    /** The Properties tab. */
    public boolean canEditProperties() {
        return canEditServerData();
    }

    /** The Players tab (op, whitelist, ban) -- and where the owner adds managers. */
    public boolean canManagePlayers() {
        return canEditServerData();
    }

    /** The Addons tab (mods / plugins / modpacks). */
    public boolean canManageAddons() {
        return canEditServerData();
    }

    /** The Files tab. */
    public boolean canManageFiles() {
        return canEditServerData();
    }

    // ---------------------------------------------------------------------------------------------
    // Permissions tab
    // ---------------------------------------------------------------------------------------------

    /** Adding, removing and re-roling managers. Owner only -- that is the whole point of the role. */
    public boolean canManageRoles() {
        return this == OWNER;
    }

    /**
     * Whether the Permissions tab is limited to the two safe world-shape settings (simulation
     * distance and view distance). True for a Starter: they get those two and nothing else.
     */
    public boolean isPermissionsRestricted() {
        return this == STARTER;
    }

    /** Whether this role may edit one field of the (possibly restricted) Permissions view. */
    public boolean canEditPermissionField(String propertyKey) {
        if (this == OWNER) return true;
        if (this == STARTER) return isRestrictedPermissionKey(propertyKey);
        return false; // an administrator has no Permissions tab at all
    }

    /** The only two keys a Starter may touch on the restricted Permissions tab. */
    public static boolean isRestrictedPermissionKey(String propertyKey) {
        if (propertyKey == null) return false;
        String k = propertyKey.trim().toLowerCase(Locale.ROOT);
        return k.equals("simulation-distance") || k.equals("view-distance");
    }

    // ---------------------------------------------------------------------------------------------
    // Settings / danger zone
    // ---------------------------------------------------------------------------------------------

    /** The owner's permanent, account-wide delete of the server and its cloud data. */
    public boolean canDeleteServerEverywhere() {
        return this == OWNER;
    }

    /** Removing this server's files from only this PC (every non-owner role gets this instead). */
    public boolean canDeleteFromThisPc() {
        return true;
    }

    /** Cancelling your own moderation -- stops the server being shared with you. */
    public boolean canCancelOwnModeration() {
        return this != OWNER;
    }

    /** Pushing the current local world to the cloud, or pulling the latest one down. */
    public boolean canSyncCloud() {
        return this == OWNER || this == ADMINISTRATOR;
    }

    /**
     * Force-stopping whoever is currently hosting, or taking the hosting over. Owner only -- this is
     * the "the owner can stop a moderator's PC from hosting" power.
     */
    public boolean canForceStopHost() {
        return this == OWNER;
    }

    /** Whether this role may flip the owner-only "moderators may only host while publicly reachable" switch. */
    public boolean canEditSharedHostingPolicy() {
        return this == OWNER;
    }

    /** Whether this role may close the shared server to moderators entirely. */
    public boolean canCloseToModerators() {
        return this == OWNER;
    }

    /** One-line summary of the role, shown in the mandatory role picker. */
    public String summary() {
        return switch (this) {
            case OWNER -> "Full control of this server, including its version, roles and cloud data.";
            case ADMINISTRATOR -> "Co-host: console, Properties, Players, Addons and Files. No version "
                    + "changes and no Permissions tab; Settings can only remove the server from this PC.";
            case STARTER -> "Console (start/stop, play, share over the Internet), Settings without the "
                    + "owner's permanent delete, and a Permissions tab limited to view and simulation distance.";
        };
    }
}
