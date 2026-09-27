package com.deylauncher.servers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * This PC's own record of which servers it is linked to -- {@code ~/.deylauncher/semi-hosted.json}.
 *
 * <p>Two things live here that the shared repo cannot answer on its own:
 *
 * <ul>
 *   <li><b>The local half of the link.</b> A server has an id in the cloud and a different id on this PC
 *       ({@link com.deylauncher.server.ServerInstance#id}, generated when its folder was created). Only
 *       this machine knows which local folder is which shared server, so joining, syncing and "is it
 *       installed here" all read this file.</li>
 *   <li><b>Servers shared with me that I have not installed yet.</b> A moderator must see the server
 *       appear <i>before</i> any files exist locally -- that is exactly what the "Install server" screen
 *       is for -- so a link is written the moment a grant is discovered, with
 *       {@link Link#localServerId} still null.</li>
 * </ul>
 *
 * <p>Deliberately a local file rather than more state in the repo: it is per-PC by definition, and
 * putting it in the repo would leak every moderator's install status to everyone else.
 */
public class SemiHostedLinkStore {

    /** One linked server. */
    public static class Link {
        /** The cloud id (the owner's original local id, adopted as the shared id). */
        public String serverId;
        /** The servers repo holding this server -- every later operation must use exactly this one. */
        public String repo;
        /** This PC's local server id, or null while the server is granted but not installed here. */
        public String localServerId;
        public String alias;
        public String name;
        public String ownerUuid;
        public String ownerUsername;
        public String ownerAccountType = "OFFLINE";
        /** This account's role on the server ({@link ManagerRole#wire()}), or null when unknown yet. */
        public String role;
        public boolean installed;
        public long linkedAt = System.currentTimeMillis();
        public long lastSyncedAt;
        /** Why the last sync failed, so the card can explain itself instead of silently lagging. */
        public String lastSyncError;

        public Link() {
        }

        /** The role this PC believes it holds, or null when it has not been resolved from the repo yet. */
        public ManagerRole parsedRole() {
            return ManagerRole.fromWire(role);
        }

        /** True when {@code localServerId} points at an installed copy on this PC. */
        public boolean isInstalledLocally() {
            return installed && localServerId != null && !localServerId.isBlank();
        }

        /** The DEY address for this link, or null when the owner never claimed an alias. */
        public String deyAddress() {
            return DeyAddress.of(alias);
        }
    }

    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public SemiHostedLinkStore(Path launcherRoot) {
        this.file = launcherRoot.resolve("semi-hosted.json");
    }

    /** The path this store persists to (shown by the UI so people can find it). */
    public Path file() {
        return file;
    }

    public List<Link> list() {
        if (!Files.exists(file)) return new ArrayList<>();
        try {
            Link[] arr = gson.fromJson(Files.readString(file), Link[].class);
            List<Link> out = new ArrayList<>();
            if (arr != null) {
                for (Link l : arr) {
                    if (l != null && l.serverId != null && !l.serverId.isBlank()) out.add(l);
                }
            }
            return out;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** The link for a cloud server id, or null. */
    public Link find(String serverId) {
        if (serverId == null) return null;
        for (Link l : list()) {
            if (serverId.equals(l.serverId)) return l;
        }
        return null;
    }

    /** The link owning a local server folder, or null when this folder was never shared. */
    public Link findForLocal(String localServerId) {
        if (localServerId == null) return null;
        for (Link l : list()) {
            if (localServerId.equals(l.localServerId)) return l;
        }
        return null;
    }

    /** Adds or replaces a link, matched by cloud server id. */
    public void upsert(Link link) {
        if (link == null || link.serverId == null || link.serverId.isBlank()) return;
        List<Link> current = list();
        current.removeIf(l -> link.serverId.equals(l.serverId));
        current.add(link);
        save(current);
    }

    /** Removes a link; true when something was actually removed. */
    public boolean remove(String serverId) {
        if (serverId == null) return false;
        List<Link> current = list();
        boolean removed = current.removeIf(l -> serverId.equals(l.serverId));
        if (removed) save(current);
        return removed;
    }

    /** Convenience for the very common "this local folder is now the shared server" step. */
    public void markInstalled(String serverId, String localServerId) {
        Link link = find(serverId);
        if (link == null) return;
        link.localServerId = localServerId;
        link.installed = true;
        link.lastSyncedAt = System.currentTimeMillis();
        link.lastSyncError = null;
        upsert(link);
    }

    /** Records the outcome of a sync attempt on a link, so the UI can say what happened last time. */
    public void recordSync(String serverId, String errorOrNull) {
        Link link = find(serverId);
        if (link == null) return;
        link.lastSyncedAt = System.currentTimeMillis();
        link.lastSyncError = errorOrNull;
        upsert(link);
    }

    private void save(List<Link> links) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, gson.toJson(links));
        } catch (IOException ignored) {
            // Best-effort -- worst case this PC forgets which local folder is which shared server.
        }
    }
}
