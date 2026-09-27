package com.deylauncher.servers;

import com.deylauncher.friends.GitHubConfig;
import com.deylauncher.identity.AccountType;
import com.deylauncher.server.ServerInstance;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Everything the launcher does with semi self-hosted servers, in one place: publishing a server so other
 * PCs can see it, granting and revoking moderation, claiming the running host, syncing the world, and
 * resolving {@code dey|alias} addresses.
 *
 * <p><b>Multiple servers repos, one token.</b> A config may list several servers repos
 * ({@code DeyLauncher-Servers}, {@code DeyLauncher-Servers1}, ...) and they all use the same bot token.
 * This service holds one {@link ServersRepository} per repo; every server records the repo it lives in
 * ({@link HostedServer#repo}), and every operation routes to that repo rather than to "the" configured
 * one -- which is what lets another repo be added later without moving or rewriting anything.
 *
 * <p><b>One host at a time.</b> {@link #acquireLease} is a compare-and-swap on {@code runtime.json}: the
 * first launcher to write wins, everyone else is told who holds it. {@link #heartbeat} keeps that claim
 * alive, and lease expiry (see {@link ServerRuntimeDoc}) is what recovers a server whose host vanished.
 *
 * <p><b>Nothing here trusts a username.</b> Ownership and roles are matched on account uuid, because an
 * offline account's uuid is derived from its name alone and a name is trivial to type.
 *
 * <p>The service contains no UI and no JavaFX, so every rule can be exercised in tests.
 */
public class SemiHostedService {

    /** Grace added to a lease before another PC may take a server over, absorbing small clock drift. */
    public static final long LEASE_GRACE_MILLIS = 30_000L;

    private final GitHubConfig config;
    private final Map<String, ServersRepository> repos = new LinkedHashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public SemiHostedService(GitHubConfig config) {
        this.config = config;
        if (config != null && config.isConfigured()) {
            String dir = config.serversDirOrDefault();
            for (String repo : config.serversRepos()) {
                repos.put(repo, new ServersRepository(new ServersRepository.Config(
                        config.token, config.owner, repo, dir)));
            }
        }
    }

    /** True when a token, owner and at least one servers repo are configured. */
    public boolean configured() {
        return !repos.isEmpty();
    }

    /** The servers repos this build talks to, primary first. */
    public List<String> repoNames() {
        return new ArrayList<>(repos.keySet());
    }

    /** The repo new servers are published into unless another one is chosen. */
    public String primaryRepo() {
        return configured() ? repos.keySet().iterator().next() : config.primaryServersRepo();
    }

    /** The GitHub account that owns the repos (shown in Settings). */
    public String ownerLogin() {
        return config == null ? null : config.owner;
    }

    /**
     * The repository client for {@code repoName}, falling back to the primary repo for an unknown or blank
     * name -- so a server whose recorded repo was dropped from configuration still resolves to something
     * usable instead of throwing while a card is being drawn.
     */
    public ServersRepository repo(String repoName) {
        if (!configured()) {
            throw new IllegalStateException("The semi self-hosted backend isn't set up in this build.");
        }
        if (repoName != null && repos.containsKey(repoName)) return repos.get(repoName);
        return repos.values().iterator().next();
    }

    /** Whether this token may write to a repo -- checked by Settings and before a push. */
    public boolean canWrite(String repoName) {
        try {
            return repo(repoName).hasWriteAccess();
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Paths (server-relative; ServersRepository applies the servers/ prefix)
    // ---------------------------------------------------------------------------------------------

    public static final String INDEX_FILE = "index.json";

    public static String metaPath(String serverId) {
        return serverId + "/meta.json";
    }

    public static String managersPath(String serverId) {
        return serverId + "/managers.json";
    }

    public static String runtimePath(String serverId) {
        return serverId + "/runtime.json";
    }

    // ---------------------------------------------------------------------------------------------
    // Discovery
    // ---------------------------------------------------------------------------------------------

    /** One shared server, as listed by a repo's index. The full record is fetched on demand. */
    public record CloudServer(String repo, HostedIndex.Entry entry) {

        public String serverId() {
            return entry.serverId;
        }

        public String name() {
            return entry.name;
        }

        public String ownerUuid() {
            return entry.ownerUuid;
        }

        public String ownerUsername() {
            return entry.ownerUsername;
        }

        public String alias() {
            return entry.alias;
        }

        /** The {@code dey|alias} address, or null when the owner never claimed one. */
        public String deyAddress() {
            return entry.deyAddress();
        }

        public boolean isOwnedBy(String uuid) {
            return entry.isOwnedBy(uuid);
        }

        public boolean allowModeratorHostOnlyWhenPublic() {
            return entry.allowModeratorHostOnlyWhenPublic;
        }

        public boolean openToModerators() {
            return entry.openToModerators;
        }

        public String type() {
            return entry.type;
        }

        public String minecraftVersion() {
            return entry.minecraftVersion;
        }

        public String ownerAccountType() {
            return entry.ownerAccountType;
        }

        /** The repo this server actually lives in -- the target of every write for it. */
        public String repoName() {
            return repo;
        }
    }

    /** The index of one repo, repaired so callers never see nulls. */
    public HostedIndex readIndex(ServersRepository repository) throws Exception {
        String text = repository.read(INDEX_FILE).text();
        if (text == null || text.isBlank()) return new HostedIndex();
        try {
            HostedIndex index = gson.fromJson(text, HostedIndex.class);
            return index == null ? new HostedIndex() : index.normalized();
        } catch (RuntimeException e) {
            // A damaged index must not brick the Servers page: an empty one simply lists nothing until
            // the next successful publish rewrites it.
            return new HostedIndex();
        }
    }

    private void mutateIndex(ServersRepository repository, String message,
                             UnaryOperator<HostedIndex> mutator) throws Exception {
        repository.syncJson(INDEX_FILE, HostedIndex.class, HostedIndex::new, message,
                index -> mutator.apply(index.normalized()));
    }

    /** Every shared server across every configured repo. */
    public List<CloudServer> listAll() throws Exception {
        List<CloudServer> all = new ArrayList<>();
        for (Map.Entry<String, ServersRepository> e : repos.entrySet()) {
            HostedIndex index = readIndex(e.getValue());
            for (HostedIndex.Entry entry : index.servers) {
                if (entry.serverId == null || entry.serverId.isBlank()) continue;
                if (entry.repo == null || entry.repo.isBlank()) entry.repo = e.getKey();
                all.add(new CloudServer(e.getKey(), entry));
            }
        }
        return all;
    }

    /** Servers owned by this account, across every repo -- the shared half of "your servers". */
    public List<CloudServer> listOwned(String ownerUuid) throws Exception {
        List<CloudServer> out = new ArrayList<>();
        if (ownerUuid == null) return out;
        for (CloudServer cs : listAll()) {
            if (ownerUuid.equals(cs.ownerUuid())) out.add(cs);
        }
        return out;
    }

    /**
     * Servers this account may manage but does not own -- the "shared with you" list.
     *
     * <p>Whitelisted managers are included by design: a grant IS the whitelist entry, so as soon as the
     * owner adds somebody (even as a restricted Starter) the server appears for them.
     */
    public List<CloudServer> listManagedBy(String uuid) throws Exception {
        List<CloudServer> out = new ArrayList<>();
        if (uuid == null) return out;
        for (CloudServer cs : listAll()) {
            if (uuid.equals(cs.ownerUuid())) continue;
            ManagersDoc managers = readManagers(cs.repoName(), cs.serverId());
            if (managers.find(uuid) != null) out.add(cs);
        }
        return out;
    }

    /** The full record for a shared server, or null when it is gone. */
    public HostedServer fetchMeta(String repoName, String serverId) throws Exception {
        String text = repo(repoName).read(metaPath(serverId)).text();
        if (text == null || text.isBlank()) return null;
        try {
            HostedServer meta = gson.fromJson(text, HostedServer.class);
            if (meta != null && (meta.repo == null || meta.repo.isBlank())) meta.repo = repoName;
            return meta;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Convenience for a {@link CloudServer} whose full record is needed. */
    public HostedServer fetchMeta(CloudServer cs) throws Exception {
        return cs == null ? null : fetchMeta(cs.repoName(), cs.serverId());
    }

    /** Finds a shared server by its stable id, searching every repo. */
    public Optional<CloudServer> findById(String serverId) throws Exception {
        if (serverId == null) return Optional.empty();
        for (CloudServer cs : listAll()) {
            if (serverId.equals(cs.serverId())) return Optional.of(cs);
        }
        return Optional.empty();
    }

    /** Finds a shared server by DEY alias, compared case-insensitively, searching every repo. */
    public Optional<CloudServer> findByAlias(String alias) throws Exception {
        String wanted = DeyAddress.normalizeAlias(alias);
        if (wanted == null) return Optional.empty();
        for (CloudServer cs : listAll()) {
            if (wanted.equals(DeyAddress.normalizeAlias(cs.alias()))) return Optional.of(cs);
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------------------------------------
    // Publishing, quota and aliases
    // ---------------------------------------------------------------------------------------------

    /** How many shared servers this account already owns -- the number the cloud slot limit checks. */
    public int countOwned(String ownerUuid) throws Exception {
        return listOwned(ownerUuid).size();
    }

    /** True when {@code alias} is already claimed by a different server, in any repo. */
    public boolean aliasTaken(String alias, String exceptServerId) throws Exception {
        String wanted = DeyAddress.normalizeAlias(alias);
        if (wanted == null) return false;
        for (CloudServer cs : listAll()) {
            if (exceptServerId != null && exceptServerId.equals(cs.serverId())) continue;
            if (wanted.equals(DeyAddress.normalizeAlias(cs.alias()))) return true;
        }
        return false;
    }

    /**
     * A free DEY alias derived from a server's name, so the owner can accept a suggestion instead of
     * inventing one. Collisions across every repo get a numeric suffix rather than being reused, because
     * an alias must identify exactly one server for {@code dey|alias} to mean anything.
     */
    public String suggestAlias(String name) throws Exception {
        String base = (name == null ? "" : name).replaceAll("[^A-Za-z0-9_-]", "");
        if (base.isEmpty()) base = "server";
        if (base.length() > DeyAddress.MAX_ALIAS_LENGTH) {
            base = base.substring(0, DeyAddress.MAX_ALIAS_LENGTH);
        }
        String candidate = base;
        int n = 1;
        while (aliasTaken(candidate, null)) {
            n++;
            String suffix = String.valueOf(n);
            candidate = base.length() + suffix.length() > DeyAddress.MAX_ALIAS_LENGTH
                    ? base.substring(0, DeyAddress.MAX_ALIAS_LENGTH - suffix.length()) + suffix
                    : base + suffix;
        }
        return candidate;
    }

    /** Writes a server's own record and refreshes its index entry in the same operation. */
    public void saveMeta(HostedServer meta) throws Exception {
        ServersRepository repository = repo(meta.repo);
        ServersRepository.Snapshot existing = repository.read(metaPath(meta.serverId));
        repository.write(metaPath(meta.serverId), gson.toJson(meta), existing.sha(),
                "DeyLauncher: server " + meta.serverId + " (" + meta.name + ")");
        HostedIndex.Entry entry = meta.toIndexEntry();
        mutateIndex(repository, "DeyLauncher: index " + meta.serverId, index -> {
            index.upsert(entry);
            return index;
        });
    }

    /**
     * Publishes (or re-publishes) a local server to a servers repo.
     *
     * <p>The cloud slot limit is enforced here, for the account that will own the server, and only when the
     * server is not already published -- otherwise editing a server you already shared would be blocked by
     * your own limit.
     */
    public HostedServer publish(ServerInstance local, String ownerUuid, String ownerUsername,
                                AccountType accountType, String repoName, String iconBase64)
            throws Exception {
        if (!configured()) {
            throw new IllegalStateException("Semi self-hosted servers need the DeyLauncher backend, which "
                    + "isn't set up in this build.");
        }
        if (ownerUuid == null || ownerUuid.isBlank()) {
            throw new IllegalStateException("Set up an account first -- a shared server needs an owner.");
        }
        String targetRepo = (repoName == null || repoName.isBlank()) ? primaryRepo() : repoName;
        ServersRepository repository = repo(targetRepo);

        String serverId = (local.cloudServerId == null || local.cloudServerId.isBlank())
                ? local.id : local.cloudServerId;

        List<CloudServer> mine = listOwned(ownerUuid);
        boolean alreadyPublished = false;
        for (CloudServer cs : mine) {
            if (serverId.equals(cs.serverId())) {
                alreadyPublished = true;
                break;
            }
        }
        if (!alreadyPublished && !SemiHostedLimits.canAddAnother(accountType, mine.size())) {
            throw new IllegalStateException(SemiHostedLimits.capReachedMessage(accountType));
        }

        HostedServer meta = new HostedServer();
        meta.serverId = serverId;
        meta.repo = repository.repoName();
        meta.alias = DeyAddress.normalizeAlias(local.cloudAlias);
        meta.name = local.name;
        meta.ownerUuid = ownerUuid;
        meta.ownerUsername = ownerUsername;
        meta.ownerAccountType = (accountType == null ? AccountType.OFFLINE : accountType).name();
        meta.type = local.type.name();
        meta.minecraftVersion = local.minecraftVersion;
        meta.port = local.port;
        meta.semiSelfHosted = true;
        meta.allowModeratorHostOnlyWhenPublic = local.allowModeratorHostOnlyWhenPublic;
        meta.openToModerators = local.openToModerators;
        meta.iconPngBase64 = sanitizeIcon(iconBase64);
        meta.createdAt = local.createdAt > 0 ? local.createdAt : System.currentTimeMillis();
        meta.touchedBy(ownerUsername);
        saveMeta(meta);
        return meta;
    }

    /** Keeps a published icon small: it must never bloat the record every reader has to download. */
    public static String sanitizeIcon(String base64) {
        if (base64 == null) return null;
        String trimmed = base64.trim();
        return trimmed.length() > HostedServer.MAX_ICON_BYTES ? null : trimmed;
    }

    /**
     * Removes a server from the cloud entirely: its world parts, manifest, manager grants, runtime claim
     * and index entry. Used by the owner's "Delete cloud data" action and by the owner's permanent delete.
     */
    public void deleteCloudData(String repoName, String serverId) throws Exception {
        ServersRepository repository = repo(repoName);
        WorldManifest manifest = WorldSync.readManifest(repository, serverId);
        if (manifest != null) {
            for (WorldManifest.Part part : manifest.orderedParts()) {
                repository.delete(WorldSync.partPath(serverId, part.name),
                        "DeyLauncher: remove world part for " + serverId);
            }
        }
        repository.delete(WorldSync.manifestPath(serverId), "DeyLauncher: remove world manifest " + serverId);
        repository.delete(metaPath(serverId), "DeyLauncher: remove server " + serverId);
        repository.delete(managersPath(serverId), "DeyLauncher: remove managers for " + serverId);
        repository.delete(runtimePath(serverId), "DeyLauncher: remove runtime for " + serverId);
        mutateIndex(repository, "DeyLauncher: unindex " + serverId, index -> {
            index.remove(serverId);
            return index;
        });
    }

    /** Persists owner-editable policy changes on an already-published server. */
    public void updatePolicy(HostedServer meta, String updatedBy) throws Exception {
        saveMeta(meta.touchedBy(updatedBy));
    }

    // ---------------------------------------------------------------------------------------------
    // Managers (the whitelist of moderators, with a mandatory role each)
    // ---------------------------------------------------------------------------------------------

    /** The manager grants for a server, or an empty document when nobody has been added yet. */
    public ManagersDoc readManagers(String repoName, String serverId) throws Exception {
        String text = repo(repoName).read(managersPath(serverId)).text();
        if (text == null || text.isBlank()) return new ManagersDoc();
        try {
            ManagersDoc doc = gson.fromJson(text, ManagersDoc.class);
            return doc == null ? new ManagersDoc() : doc.normalized();
        } catch (RuntimeException e) {
            // An unreadable grant file must fail CLOSED: an empty document means "nobody is a manager",
            // never "everybody is".
            return new ManagersDoc();
        }
    }

    /** Adds a manager, or re-roles an existing one. A role is mandatory -- nothing is granted without one. */
    public void grantManager(String repoName, String serverId, String uuid, String username,
                            ManagerRole role, String addedBy) throws Exception {
        if (uuid == null || uuid.isBlank() || role == null) return;
        repo(repoName).syncJson(managersPath(serverId), ManagersDoc.class, ManagersDoc::new,
                "DeyLauncher: " + role.displayName().toLowerCase(java.util.Locale.ROOT)
                        + " for " + serverId + " (" + username + ")",
                doc -> {
                    doc.normalized();
                    doc.grant(uuid, username, role, addedBy);
                    return doc;
                });
    }

    /** Removes a manager. Safe to call for somebody who is not a manager (a no-op). */
    public void revokeManager(String repoName, String serverId, String uuid, String byName)
            throws Exception {
        if (uuid == null || uuid.isBlank()) return;
        repo(repoName).syncJson(managersPath(serverId), ManagersDoc.class, ManagersDoc::new,
                "DeyLauncher: remove manager " + uuid + " from " + serverId,
                doc -> {
                    doc.normalized();
                    if (!doc.revoke(uuid)) return null; // nothing to change -- don't create a commit
                    return doc;
                });
    }

    /** The role {@code uuid} holds on a server (OWNER for its owner, null when they have no access). */
    public ManagerRole roleOf(String repoName, String serverId, String ownerUuid, String uuid)
            throws Exception {
        return readManagers(repoName, serverId).roleOf(ownerUuid, uuid);
    }

    // ---------------------------------------------------------------------------------------------
    // The running host: one at a time, held by a lease
    // ---------------------------------------------------------------------------------------------

    /** The current claim on a server, or an empty (STOPPED) document when nothing is recorded yet. */
    public ServerRuntimeDoc readRuntime(String repoName, String serverId) throws Exception {
        String text = repo(repoName).read(runtimePath(serverId)).text();
        if (text == null || text.isBlank()) return new ServerRuntimeDoc();
        try {
            ServerRuntimeDoc doc = gson.fromJson(text, ServerRuntimeDoc.class);
            return doc == null ? new ServerRuntimeDoc() : doc.normalized();
        } catch (RuntimeException e) {
            // Unreadable runtime -> treat as stopped, so a damaged file can never permanently lock a server.
            return new ServerRuntimeDoc();
        }
    }

    /** True when somebody's live (unexpired) claim holds this server right now. */
    public boolean isHeldLive(ServerRuntimeDoc runtime) {
        return runtime != null && runtime.isLive(System.currentTimeMillis(), LEASE_GRACE_MILLIS);
    }

    /** The outcome of trying to become the host. */
    public record LeaseResult(boolean granted, String message, ServerRuntimeDoc runtime) {
    }

    /**
     * Tries to become the host of a shared server.
     *
     * <p>This is the "only one server running, first one to open it wins" rule. The claim is written with
     * the file's current sha, so if another PC claimed it in between, GitHub refuses this write, the loop
     * re-reads, and the second PC is told who got there first instead of both starting a copy. A claim that
     * already belongs to the same account is simply refreshed, so reconnecting after a launcher restart is
     * not treated as a conflict with yourself.
     */
    public LeaseResult acquireLease(CloudServer cs, String hostUuid, String hostName, String accountType,
                                    String publicAddress, String lanAddress, int port) throws Exception {
        boolean[] granted = {false};
        String[] message = {null};
        ServerRuntimeDoc held = repo(cs.repoName()).syncJson(runtimePath(cs.serverId()),
                ServerRuntimeDoc.class, ServerRuntimeDoc::new, "DeyLauncher: host " + cs.serverId(),
                runtime -> {
                    runtime.normalized();
                    boolean mineAlready = hostUuid != null && hostUuid.equals(runtime.hostUuid);
                    if (isHeldLive(runtime) && !mineAlready) {
                        granted[0] = false;
                        message[0] = runtime.hostedByMessage();
                        return null; // abort: leave the other host's claim exactly as it is
                    }
                    runtime.claimBy(hostUuid, hostName, accountType);
                    runtime.publicAddress = publicAddress;
                    runtime.lanAddress = lanAddress;
                    runtime.port = port;
                    granted[0] = true;
                    message[0] = "You're hosting " + (cs.name() == null ? "this server" : cs.name()) + " now.";
                    return runtime;
                });
        return new LeaseResult(granted[0], message[0], held);
    }

    /**
     * Refreshes this host's lease and republishes the live address plus the player list.
     *
     * <p>Returns false when this PC is no longer the host (the claim expired and somebody else took over,
     * or the owner force-stopped it) -- the caller is expected to shut its server down in that case.
     */
    public boolean heartbeat(CloudServer cs, String hostUuid, String publicAddress,
                             List<String> onlinePlayers) throws Exception {
        boolean[] ok = {false};
        repo(cs.repoName()).syncJson(runtimePath(cs.serverId()), ServerRuntimeDoc.class,
                ServerRuntimeDoc::new, "DeyLauncher: heartbeat " + cs.serverId(),
                runtime -> {
                    runtime.normalized();
                    if (!runtime.renewLease(hostUuid)) return null; // not ours any more -- don't write
                    if (publicAddress != null && !publicAddress.isBlank()) {
                        runtime.publicAddress = publicAddress;
                    }
                    runtime.onlinePlayers = onlinePlayers == null
                            ? new ArrayList<>() : new ArrayList<>(onlinePlayers);
                    ok[0] = true;
                    return runtime;
                });
        return ok[0];
    }

    /** Releases the claim, recording why (a clean stop, a crash, or a forced stop). */
    public void releaseLease(CloudServer cs, String hostUuid, String cause) throws Exception {
        repo(cs.repoName()).syncJson(runtimePath(cs.serverId()), ServerRuntimeDoc.class,
                ServerRuntimeDoc::new, "DeyLauncher: stop " + cs.serverId(),
                runtime -> {
                    runtime.normalized();
                    // Only the host (or nobody) may clear a claim; a bystander must not stop a live server.
                    if (runtime.claimsRunning() && hostUuid != null
                            && !hostUuid.equals(runtime.hostUuid)) {
                        return null;
                    }
                    runtime.stopWith(cause, hostUuid);
                    return runtime;
                });
    }

    /**
     * Asks whoever is hosting to stop. Only the owner may do this, and it is deliberately a REQUEST: the
     * host's launcher sees it on its next heartbeat. A host whose launcher is gone cannot be told anything
     * -- for that case the lease simply expires.
     */
    public void requestForceStop(CloudServer cs, ServerRuntimeDoc current, String byUuid, String byName,
                                 String reason) throws Exception {
        String targetHost = current == null ? null : current.hostUuid;
        repo(cs.repoName()).syncJson(managersPath(cs.serverId()), ManagersDoc.class, ManagersDoc::new,
                "DeyLauncher: force stop " + cs.serverId(),
                doc -> {
                    doc.normalized();
                    doc.forceStopRequestedAt = System.currentTimeMillis();
                    doc.forceStopRequestedBy = byUuid;
                    doc.forceStopRequestedByName = byName;
                    doc.forceStopReason = reason;
                    doc.forceStopTargetHostUuid = targetHost;
                    return doc;
                });
    }

    /** True when the owner has asked {@code hostUuid} to stop; the host checks this on every heartbeat. */
    public boolean forceStopRequestedFor(CloudServer cs, String hostUuid) throws Exception {
        ManagersDoc doc = readManagers(cs.repoName(), cs.serverId());
        if (doc.forceStopRequestedAt == null) return false;
        return hostUuid != null && hostUuid.equals(doc.forceStopTargetHostUuid);
    }

    /** Clears a force-stop request once the host has complied (or a new host has taken over). */
    public void clearForceStop(CloudServer cs) throws Exception {
        repo(cs.repoName()).syncJson(managersPath(cs.serverId()), ManagersDoc.class, ManagersDoc::new,
                "DeyLauncher: clear force stop " + cs.serverId(),
                doc -> {
                    doc.normalized();
                    if (doc.forceStopRequestedAt == null) return null;
                    doc.forceStopRequestedAt = null;
                    doc.forceStopRequestedBy = null;
                    doc.forceStopRequestedByName = null;
                    doc.forceStopReason = null;
                    doc.forceStopTargetHostUuid = null;
                    return doc;
                });
    }

    // ---------------------------------------------------------------------------------------------
    // Worlds
    // ---------------------------------------------------------------------------------------------

    /** Packs the local server and uploads it, downgrading to settings-only when the world is too large. */
    public WorldSync.Outcome pushWorld(CloudServer cs, Path serverDir, String pushedBy,
                                       WorldSync.Progress progress) throws Exception {
        HostedServer meta = fetchMeta(cs);
        return WorldSync.push(repo(cs.repoName()), cs.serverId(), serverDir, pushedBy,
                meta == null ? null : meta.minecraftVersion,
                meta == null ? null : meta.type,
                progress);
    }

    /** Downloads and unpacks the latest pushed snapshot, or returns null when nothing was pushed yet. */
    public WorldManifest pullWorld(CloudServer cs, Path serverDir, WorldSync.Progress progress)
            throws Exception {
        return WorldSync.pull(repo(cs.repoName()), cs.serverId(), serverDir, progress);
    }

    /** The snapshot currently stored for a server, or null -- used by the Settings panel. */
    public WorldManifest worldStatus(CloudServer cs) throws Exception {
        return WorldSync.readManifest(repo(cs.repoName()), cs.serverId());
    }

    // ---------------------------------------------------------------------------------------------
    // DEY addresses
    // ---------------------------------------------------------------------------------------------

    /**
     * What a {@code dey|alias} address currently resolves to.
     *
     * <p>{@code address} is the live {@code host:port} to actually connect to, or null when the server is
     * offline (or running without a public address) -- in which case {@code note} says why, because "offline"
     * on its own tells a player nothing about whether the owner stopped, crashed, or never shared an address.
     */
    public record Resolution(String address, String label, HostedServer meta, ServerRuntimeDoc runtime,
                             String note) {
        public boolean online() {
            return address != null && !address.isBlank();
        }
    }

    /** Resolves a DEY address against the live host record; a non-DEY address is returned unchanged. */
    public Resolution resolveDeyAddress(String address) throws Exception {
        String alias = DeyAddress.aliasOf(address);
        if (alias == null) return new Resolution(address, null, null, null, null);

        Optional<CloudServer> found = findByAlias(alias);
        if (found.isEmpty()) {
            return new Resolution(null, DeyAddress.of(alias), null, null,
                    "No shared server uses the address " + DeyAddress.of(alias) + " yet.");
        }
        CloudServer cs = found.get();
        HostedServer meta = fetchMeta(cs);
        ServerRuntimeDoc runtime = readRuntime(cs.repoName(), cs.serverId());
        String name = cs.name() == null ? DeyAddress.of(alias) : cs.name();

        if (!isHeldLive(runtime)) {
            String who = cs.ownerUsername() == null || cs.ownerUsername().isBlank()
                    ? "its owner" : cs.ownerUsername();
            String when = runtime.lastStoppedAt > 0
                    ? " It was last online " + describeAgo(runtime.lastStoppedAt) + "."
                    : "";
            return new Resolution(null, name, meta, runtime,
                    "Offline -- nobody is hosting " + name + " right now (owner: " + who + ")." + when);
        }

        String publicAddr = runtime.publicAddress == null ? null : runtime.publicAddress.trim();
        if (publicAddr != null && !publicAddr.isEmpty()) {
            return new Resolution(publicAddr, name, meta, runtime,
                    name + " is live on " + runtime.hostUsername + "'s PC (" + publicAddr + ").");
        }
        String lan = runtime.lanAddress == null ? null : runtime.lanAddress.trim();
        if (lan != null && !lan.isEmpty()) {
            return new Resolution(lan, name, meta, runtime,
                    name + " is running on " + runtime.hostUsername + "'s PC, but its public address isn't "
                            + "shared yet -- only their own network can join.");
        }
        return new Resolution(null, name, meta, runtime,
                name + " is running on " + runtime.hostUsername + "'s PC, but no address was published.");
    }

    /** A short "3 minutes ago" style phrase for the offline message. */
    static String describeAgo(long then) {
        long seconds = Math.max(0L, (System.currentTimeMillis() - then) / 1000L);
        if (seconds < 90) return "moments ago";
        long minutes = seconds / 60;
        if (minutes < 90) return minutes + " minutes ago";
        long hours = minutes / 60;
        if (hours < 36) return hours + " hour" + (hours == 1 ? "" : "s") + " ago";
        long days = hours / 24;
        return days + " day" + (days == 1 ? "" : "s") + " ago";
    }
}
