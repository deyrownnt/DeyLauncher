package com.deylauncher.friends;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole shared friends graph lives in ONE json file in the GitHub repo
 * (see GitHubConfig). This is deliberate: a single GET gives every
 * DeyLauncher user their friends list, their pending requests, AND lets
 * them discover incoming requests -- because a "request" is stored on the
 * SENDER's own entry, targeting a username (not necessarily a uuid, since
 * the target may not have used DeyLauncher yet). Any client can compute
 * "who has requested me" just by scanning outgoingRequests across the
 * file it already downloaded -- no extra API calls needed.
 *
 * This keeps the whole friends feature to exactly one GET + one PUT per
 * sync, which matters since every DeyLauncher user currently shares the
 * same bot account's GitHub API rate limit (see GITHUB_SETUP.md).
 */
public class FriendsData {
    public int version = 1;
    public Map<String, UserEntry> users = new HashMap<>();

    public static class UserEntry {
        public String username;
        /** Only ever "ONLINE" or "OFFLINE" on the wire -- "invisible" is a purely local choice
         *  (see LauncherPrefs.invisibleMode) that makes a client publish "OFFLINE" while actually
         *  online; it's never transmitted as its own state, or it would defeat the point. */
        public String status = "OFFLINE";
        public long lastSeen;
        /** Only set (and only trusted) if the user opted in via Settings > "Share my server address with friends". */
        public String serverAddress;
        /** Friendly name of the server currently being shared (published alongside serverAddress so
         *  friends see where you're playing without us having to look the address up). */
        public String currentServerName;
        /** Optional image URL for the server currently being shared (many self-hosted servers have
         *  no hosted icon, in which case this stays null and the profile shows a generated tile). */
        public String currentServerIconUrl;
        /** Socials the user chose to publish on their friend profile, edited in Account settings. */
        public List<Social> socials = new ArrayList<>();
        /** Servers this user owns in DeyLauncher (name + how to reach them), published for their profile. */
        public List<ServerInfo> servers = new ArrayList<>();
        public List<FriendRef> friends = new ArrayList<>();
        public List<OutgoingRequest> outgoingRequests = new ArrayList<>();
    }

    /** One social handle/link shown on a friend's profile. {@code type} is a display label such as
     *  "Discord", "YouTube", "Twitch", "X/Twitter", "Email", "Website" or "Other"; {@code value} is
     *  the handle/URL/address. Only ever published if the user added it in Account settings. */
    public static class Social {
        public String type;
        public String value;
        public Social() {}
        public Social(String type, String value) { this.type = type; this.value = value; }
    }

    /** A server a user owns in DeyLauncher, shown on their friend profile. {@code name} is the local
     *  server name and {@code port} how it's reachable; {@code iconUrl} is optional (few self-hosted
     *  servers have a hosted icon). */
    public static class ServerInfo {
        public String name;
        public int port;
        public String iconUrl;
        public ServerInfo() {}
        public ServerInfo(String name, int port, String iconUrl) {
            this.name = name;
            this.port = port;
            this.iconUrl = iconUrl;
        }
    }

    public static class FriendRef {
        public String uuid;
        public String username;
        public FriendRef() {}
        public FriendRef(String uuid, String username) { this.uuid = uuid; this.username = username; }
    }

    public static class OutgoingRequest {
        /** Always set -- this is how a request can target someone who has never opened DeyLauncher yet. */
        public String targetUsername;
        /** Filled in once the target is known to exist (resolved on accept, or opportunistically if already registered). */
        public String targetUuid;
        public long sentAt;
    }

    public UserEntry getOrCreate(String uuid, String username) {
        UserEntry u = users.get(uuid);
        if (u == null) {
            u = new UserEntry();
            u.username = username;
            users.put(uuid, u);
        } else if (!username.equals(u.username)) {
            u.username = username; // keep display name current if it changed
        }
        return u;
    }
}
