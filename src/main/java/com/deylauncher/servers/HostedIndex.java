package com.deylauncher.servers;

import java.util.ArrayList;
import java.util.List;

/**
 * The whole list of published semi self-hosted servers, as it exists in ONE servers repo
 * ({@code servers/index.json}).
 *
 * <p>Why a single index file per repo, when the servers themselves each have their own folder:
 * a launcher that signs in has to answer two questions immediately -- "which of my servers are up
 * here?" and "does {@code dey|mySMP} exist, and is it running?" -- and asking those with one GET per
 * repo is the difference between a usable feature and one that burns the shared token's API budget.
 * The mutable, contended parts (the running lease, the manager list, the world parts) stay in their own
 * files so two people editing two different servers never collide, and two people editing the SAME
 * server conflict only on the one small file they both touch.
 *
 * <p>Entries are a denormalised snapshot of {@link HostedServer}: whoever writes a server's own
 * {@code meta.json} rewrites its entry here in the same operation, so the index never drifts.
 */
public class HostedIndex {

    public int version = 1;
    public List<Entry> servers = new ArrayList<>();

    /** One server, reduced to what the list needs (no icon bytes, no policy details beyond a badge). */
    public static class Entry {
        public String serverId;
        /** Which servers repo holds this server -- replicated here so an entry is self-describing. */
        public String repo;
        public String alias;
        public String name;
        public String ownerUuid;
        public String ownerUsername;
        public String ownerAccountType = "OFFLINE";
        public String type = "VANILLA";
        public String minecraftVersion;
        public boolean semiSelfHosted = true;
        public boolean openToModerators = true;
        public boolean allowModeratorHostOnlyWhenPublic = false;
        public long updatedAt;

        /** The DEY address players can add for this server, or null when it claimed no alias. */
        public String deyAddress() {
            return DeyAddress.of(alias);
        }

        public boolean isOwnedBy(String uuid) {
            return uuid != null && uuid.equals(ownerUuid);
        }
    }

    /** The entry for {@code serverId}, or null when this repo has no such server. */
    public Entry find(String serverId) {
        if (serverId == null) return null;
        for (Entry e : servers) {
            if (serverId.equals(e.serverId)) return e;
        }
        return null;
    }

    /**
     * A copy of this entry with {@code incoming} merged in. The position in the list is preserved so
     * the on-disk order stays stable across writes (which keeps diffs, and therefore conflicts,
     * small).
     */
    public void upsert(Entry incoming) {
        if (incoming == null || incoming.serverId == null) return;
        for (int i = 0; i < servers.size(); i++) {
            if (incoming.serverId.equals(servers.get(i).serverId)) {
                servers.set(i, incoming);
                return;
            }
        }
        servers.add(incoming);
    }

    /** Removes a server from the index; true when something was actually removed. */
    public boolean remove(String serverId) {
        if (serverId == null) return false;
        return servers.removeIf(e -> serverId.equals(e.serverId));
    }

    /** Repairs a document that came back from the repo incomplete, so callers never see null fields. */
    public HostedIndex normalized() {
        if (servers == null) servers = new ArrayList<>();
        servers.removeIf(e -> e == null || e.serverId == null || e.serverId.isBlank());
        return this;
    }

    /** Every entry owned by {@code ownerUuid} -- the input to the per-account cloud slot limit. */
    public List<Entry> ownedBy(String ownerUuid) {
        List<Entry> out = new ArrayList<>();
        if (ownerUuid == null) return out;
        for (Entry e : servers) {
            if (ownerUuid.equals(e.ownerUuid)) out.add(e);
        }
        return out;
    }

    /** The first entry claiming {@code alias} (compared case-insensitively), or null. */
    public Entry findByAlias(String alias) {
        String wanted = DeyAddress.normalizeAlias(alias);
        if (wanted == null) return null;
        for (Entry e : servers) {
            if (wanted.equals(DeyAddress.normalizeAlias(e.alias))) return e;
        }
        return null;
    }
}
