package com.deylauncher.friends;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The semi self-hosted servers repos are configured alongside the friends repo but point somewhere else, so
 * these invariants protect the whole feature from a build that has no servers repo named at all: there is
 * always at least one repo to address, and it always has a usable name.
 */
class GitHubConfigServersReposTest {

    @Test
    void thereIsAlwaysAtLeastOneServersRepoToAddress() {
        // Whatever the environment resolves to (a local override, the embedded blob, or nothing at all),
        // callers must never have to null-check the servers repo list.
        GitHubConfig config = GitHubConfig.load();
        assertFalse(config.serversRepos().isEmpty());
        assertTrue(config.primaryServersRepo() != null && !config.primaryServersRepo().isBlank());
        assertTrue(config.serversDirOrDefault() != null && !config.serversDirOrDefault().isBlank());
    }

    @Test
    void theServersRepoDefaultsToTheDeyLauncherServersRepoNotTheFriendsOne() {
        // Publishing server data into the friends repo would mix two very different kinds of content and
        // would need a different token scope; the default must stay the dedicated servers repo.
        GitHubConfig config = GitHubConfig.load();
        assertTrue(GitHubConfig.DEFAULT_SERVERS_REPO.contains("Servers"));
        assertFalse(GitHubConfig.DEFAULT_SERVERS_REPO.equals(config.repo == null ? "" : config.repo));
        assertTrue(config.serversRepos().get(0).equals(config.primaryServersRepo()));
    }

    @Test
    void thePrimaryRepoComesFirstSoNewServersLandSomewherePredictable() {
        GitHubConfig config = GitHubConfig.load();
        assertTrue(config.serversRepos().size() >= 1);
        // No duplicates, so a repo listed twice cannot be scanned twice on every refresh.
        assertTrue(config.serversRepos().stream().distinct().count() == config.serversRepos().size());
    }
}
