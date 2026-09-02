package com.deylauncher.friends;

import java.util.ArrayList;
import java.util.List;

public class FriendsService {

    private final FriendsRepository repo;

    public FriendsService(GitHubConfig config) {
        this.repo = new FriendsRepository(config);
    }

    public record IncomingRequest(String fromUuid, String fromUsername, long sentAt) {}

    /** Everything the Friends page needs, computed from a single fetch -- see FriendsData's class doc for why. */
    public record FriendsView(
            List<FriendsData.FriendRef> friends,
            List<IncomingRequest> incoming,
            List<FriendsData.OutgoingRequest> outgoing,
            java.util.Map<String, FriendsData.UserEntry> allUsers // for looking up a friend's live status/server address
    ) {}

    public FriendsView load(String myUuid) throws Exception {
        FriendsData data = repo.read();
        return buildView(data, myUuid);
    }

    private FriendsView buildView(FriendsData data, String myUuid) {
        FriendsData.UserEntry me = data.users.get(myUuid);
        List<FriendsData.FriendRef> friends = me != null ? me.friends : new ArrayList<>();
        List<FriendsData.OutgoingRequest> outgoing = me != null ? me.outgoingRequests : new ArrayList<>();

        List<IncomingRequest> incoming = new ArrayList<>();
        String myUsername = me != null ? me.username : null;
        if (myUsername != null) {
            for (var entry : data.users.entrySet()) {
                if (entry.getKey().equals(myUuid)) continue;
                for (var req : entry.getValue().outgoingRequests) {
                    if (req.targetUsername.equalsIgnoreCase(myUsername)) {
                        incoming.add(new IncomingRequest(entry.getKey(), entry.getValue().username, req.sentAt));
                    }
                }
            }
        }
        return new FriendsView(friends, incoming, outgoing, data.users);
    }

    /** Registers/updates the caller's own entry and publishes current presence in the same write. */
    public void publishPresence(String myUuid, String myUsername, String status, String serverAddress) throws Exception {
        repo.sync("presence update", data -> {
            var me = data.getOrCreate(myUuid, myUsername);
            me.status = status;
            me.lastSeen = System.currentTimeMillis();
            me.serverAddress = serverAddress; // null clears it -- e.g. sharing turned off in Settings
            return data;
        });
    }

    /** Works even if targetUsername has never used DeyLauncher -- see FriendsData's class doc. */
    public FriendsView sendRequest(String myUuid, String myUsername, String targetUsername) throws Exception {
        FriendsData result = repo.sync("friend request", data -> {
            var me = data.getOrCreate(myUuid, myUsername);
            boolean alreadyFriends = me.friends.stream().anyMatch(f -> f.username.equalsIgnoreCase(targetUsername));
            boolean alreadyRequested = me.outgoingRequests.stream()
                    .anyMatch(r -> r.targetUsername.equalsIgnoreCase(targetUsername));
            if (!alreadyFriends && !alreadyRequested && !targetUsername.equalsIgnoreCase(myUsername)) {
                var req = new FriendsData.OutgoingRequest();
                req.targetUsername = targetUsername;
                req.sentAt = System.currentTimeMillis();
                me.outgoingRequests.add(req);
            }
            return data;
        });
        return buildView(result, myUuid);
    }

    /** Accepting adds both directions of the friendship and removes the original request, in one write. */
    public FriendsView acceptRequest(String myUuid, String myUsername, String fromUuid) throws Exception {
        FriendsData result = repo.sync("accept friend request", data -> {
            var me = data.getOrCreate(myUuid, myUsername);
            var sender = data.users.get(fromUuid);
            if (sender == null) return data; // request vanished/was withdrawn -- nothing to do

            sender.outgoingRequests.removeIf(r -> r.targetUsername.equalsIgnoreCase(myUsername));

            if (me.friends.stream().noneMatch(f -> f.uuid.equals(fromUuid))) {
                me.friends.add(new FriendsData.FriendRef(fromUuid, sender.username));
            }
            if (sender.friends.stream().noneMatch(f -> f.uuid.equals(myUuid))) {
                sender.friends.add(new FriendsData.FriendRef(myUuid, myUsername));
            }
            return data;
        });
        return buildView(result, myUuid);
    }

    public FriendsView declineRequest(String myUuid, String myUsername, String fromUuid) throws Exception {
        FriendsData result = repo.sync("decline friend request", data -> {
            var sender = data.users.get(fromUuid);
            if (sender != null) {
                sender.outgoingRequests.removeIf(r -> r.targetUsername.equalsIgnoreCase(myUsername));
            }
            return data;
        });
        return buildView(result, myUuid);
    }

    public FriendsView cancelOutgoingRequest(String myUuid, String myUsername, String targetUsername) throws Exception {
        FriendsData result = repo.sync("cancel friend request", data -> {
            var me = data.getOrCreate(myUuid, myUsername);
            me.outgoingRequests.removeIf(r -> r.targetUsername.equalsIgnoreCase(targetUsername));
            return data;
        });
        return buildView(result, myUuid);
    }

    public FriendsView removeFriend(String myUuid, String myUsername, String friendUuid) throws Exception {
        FriendsData result = repo.sync("remove friend", data -> {
            var me = data.getOrCreate(myUuid, myUsername);
            me.friends.removeIf(f -> f.uuid.equals(friendUuid));
            var friend = data.users.get(friendUuid);
            if (friend != null) friend.friends.removeIf(f -> f.uuid.equals(myUuid));
            return data;
        });
        return buildView(result, myUuid);
    }
}
