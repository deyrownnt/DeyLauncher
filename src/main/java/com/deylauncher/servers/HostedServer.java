package com.deylauncher.servers;

/**
 * One semi self-hosted server as it exists in a shared servers repo ({@code servers/&lt;id&gt;/meta.json}).
 *
 * <p>This is the record every PC reads to learn "who owns this server, what is it, and what is its DEY
 * address" -- so it is deliberately small, versioned, and free of anything machine-specific (no local
 * paths, no RAM numbers, no Java runtime). Those stay in the owner's local
 * {@link com.deylauncher.server.ServerInstance}, because they only make sense on one PC.
 *
 * <p><b>{@link #repo} is stored here on purpose.</b> Several servers repos can exist at once
 * ({@code DeyLauncher-Servers}, {@code DeyLauncher-Servers1}, ...) and they all share the same bot
 * token, so a record has to remember which repo it actually lives in -- otherwise a PC that reads a
 * server could not write it back, and the launcher would have to guess. Every write path takes the
 * repo name from this field rather than from configuration.
 */
public class HostedServer {

    /** Bumped only if a change would not be readable by an older build. New fields never bump it. */
    public int version = 1;

    /** Stable id for this server across every PC -- the owner's local server id, adopted as the cloud id. */
    public String serverId;

    /** Which servers repo this record lives in, e.g. {@code DeyLauncher-Servers}. Never blank once published. */
    public String repo;

    /** The DEY address alias without the {@code dey|} prefix, e.g. {@code mySMP}. Optional but recommended. */
    public String alias;

    public String name;

    public String ownerUuid;
    public String ownerUsername;
    /** {@code ONLINE} or {@code OFFLINE} -- decides the cloud slot limit for this owner. */
    public String ownerAccountType = "OFFLINE";

    /** {@link com.deylauncher.server.ServerType} name, e.g. {@code FABRIC}. */
    public String type = "VANILLA";
    public String minecraftVersion;

    /** The owner's listening port at home; useful as a hint, never the address players use. */
    public int port = 25565;

    /** True for a semi self-hosted server (the only kind this record describes). */
    public boolean semiSelfHosted = true;

    /**
     * Owner-only policy: when true a moderator may only start this server while they can also make it
     * reachable from outside their own network (a live playit tunnel or an equivalent public address),
     * so "moderator hosting" can never silently produce a server nobody can join.
     */
    public boolean allowModeratorHostOnlyWhenPublic = false;

    /**
     * Owner-only policy: when false the server stops being offered to its moderators at all (the
     * grants are kept, so turning it back on restores them).
     */
    public boolean openToModerators = true;

    /** The server icon as base64 PNG, or null. Kept small (see {@link #MAX_ICON_BYTES}). */
    public String iconPngBase64;

    /** Friendly name of the machine that last published this record, for "updated by" text. */
    public String updatedBy;

    public long createdAt = System.currentTimeMillis();
    public long updatedAt = System.currentTimeMillis();

    /** A server icon is a 64x64 PNG; anything bigger is refused rather than bloating every reader's GET. */
    public static final int MAX_ICON_BYTES = 64 * 1024;

    public HostedServer() {
    }

    /** The DEY address players can add, e.g. {@code dey|mySMP}, or null when no alias was claimed. */
    public String deyAddress() {
        return DeyAddress.of(alias);
    }

    /** True when this record names the account holding full control. */
    public boolean isOwnedBy(String uuid) {
        return uuid != null && uuid.equals(ownerUuid);
    }

    /** True when this record still has everything a usable server needs. */
    public boolean isValid() {
        return serverId != null && !serverId.isBlank()
                && ownerUuid != null && !ownerUuid.isBlank()
                && name != null && !name.isBlank();
    }

    /** The one-line index entry other PCs read from {@code index.json} without fetching this file. */
    public HostedIndex.Entry toIndexEntry() {
        HostedIndex.Entry e = new HostedIndex.Entry();
        e.serverId = serverId;
        e.repo = repo;
        e.alias = alias;
        e.name = name;
        e.ownerUuid = ownerUuid;
        e.ownerUsername = ownerUsername;
        e.ownerAccountType = ownerAccountType;
        e.type = type;
        e.minecraftVersion = minecraftVersion;
        e.semiSelfHosted = semiSelfHosted;
        e.openToModerators = openToModerators;
        e.allowModeratorHostOnlyWhenPublic = allowModeratorHostOnlyWhenPublic;
        e.updatedAt = updatedAt;
        return e;
    }

    /** Returns a copy with the timestamp and "updated by" refreshed -- every write goes through this. */
    public HostedServer touchedBy(String who) {
        updatedAt = System.currentTimeMillis();
        updatedBy = who;
        return this;
    }
}
