package com.deylauncher.optionskits;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole shared "who has which option kits" table lives in ONE json file in the GitHub repo
 * (see GitHubConfig), keyed by account uuid -- same shape/reasoning as FriendsData. One GET fetches
 * everyone's kits (though the UI only ever shows the active account's own list); one PUT saves any
 * change, with {@link OptionsKitsRepository} handling the read-modify-write + conflict retry.
 */
public class OptionsKitsData {
    public int version = 1;
    public Map<String, List<OptionsKit>> kitsByUuid = new HashMap<>();

    public List<OptionsKit> forUuid(String uuid) {
        return kitsByUuid.computeIfAbsent(uuid, k -> new ArrayList<>());
    }
}
