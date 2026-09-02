package com.deylauncher.friends;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Two ways this gets configured, checked in this order:
 *
 * 1. LOCAL OVERRIDE: ~/.deylauncher/github.properties -- for anyone running
 *    from source/building their own jar. Never shipped, never in the repo.
 *
 * 2. EMBEDDED (for distributed installers): a resource baked into the jar
 *    at build time from a local, gitignored file (see build.gradle.kts'
 *    processResources block and GITHUB_SETUP.md). This is what makes
 *    "friends who install the built jar/app-image don't need to configure
 *    anything" work -- the dev embeds their own bot-account token once,
 *    at their own build time, and every installer they hand out already
 *    has it. The token is still extractable by anyone who decompiles the
 *    jar; see GITHUB_SETUP.md for the blast-radius mitigations (dedicated
 *    bot account, repo-scoped fine-grained token) that make this an
 *    acceptable tradeoff for a small friend group.
 *
 * If neither is present, Friends just reports "not set up" -- never a
 * silent failure.
 */
public class GitHubConfig {

    public final String token;
    public final String owner;
    public final String repo;
    public final String friendsPath;

    private GitHubConfig(String token, String owner, String repo, String friendsPath) {
        this.token = token;
        this.owner = owner;
        this.repo = repo;
        this.friendsPath = friendsPath;
    }

    public boolean isConfigured() {
        return token != null && !token.isBlank() && owner != null && !owner.isBlank()
                && repo != null && !repo.isBlank();
    }

    public static Path localOverrideFile() {
        return Path.of(System.getProperty("user.home"), ".deylauncher", "github.properties");
    }

    public static GitHubConfig load() {
        GitHubConfig local = loadFrom(localOverrideFile());
        if (local != null && local.isConfigured()) return local;

        GitHubConfig embedded = loadFromClasspath("/embedded-github.properties");
        if (embedded != null && embedded.isConfigured()) return embedded;

        return new GitHubConfig(null, null, null, "friends.json");
    }

    private static GitHubConfig loadFrom(Path file) {
        if (!Files.exists(file)) return null;
        try (var in = Files.newInputStream(file)) {
            return fromProperties(load(in));
        } catch (IOException e) {
            return null;
        }
    }

    private static GitHubConfig loadFromClasspath(String resourcePath) {
        try (InputStream in = GitHubConfig.class.getResourceAsStream(resourcePath)) {
            if (in == null) return null;
            return fromProperties(load(in));
        } catch (IOException e) {
            return null;
        }
    }

    private static Properties load(InputStream in) throws IOException {
        Properties props = new Properties();
        props.load(in);
        return props;
    }

    private static GitHubConfig fromProperties(Properties props) {
        return new GitHubConfig(
                props.getProperty("token"),
                props.getProperty("owner"),
                props.getProperty("repo"),
                props.getProperty("friendsPath", "friends.json")
        );
    }
}
