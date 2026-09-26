package com.deylauncher.friends;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The build bakes the shared backend credentials in as an obfuscated blob ({@code embedGithubCredentials}
 * in {@code build.gradle.kts}); the app reverses that at startup. These tests pin both halves of that
 * contract, because a mismatch between them fails silently -- Friends would simply look "not set up" in
 * every distributed build, which is exactly the bug this feature exists to remove.
 */
class GitHubConfigTest {

    /** Must match EMBEDDED_KEY in GitHubConfig and embeddedBackendKey in build.gradle.kts. */
    private static final String KEY = "DeyLauncher-backend-v1";

    /** The Gradle side's algorithm, reproduced so the Java decoder can be exercised offline. */
    private static String pack(String text) {
        byte[] key = KEY.getBytes(StandardCharsets.UTF_8);
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[raw.length];
        for (int i = 0; i < raw.length; i++) out[i] = (byte) (raw[i] ^ key[i % key.length]);
        return Base64.getEncoder().encodeToString(out);
    }

    @Test
    void aPackedPropertiesBlockRoundTrips() {
        String props = "token=github_pat_placeholder\nowner=onpishi\n"
                + "repo=DeyLauncher-Friends\nfriendsPath=friends.json\n";
        assertEquals(props, GitHubConfig.deobfuscate(pack(props)));
    }

    @Test
    void textThatIsNotOurFormatIsRejectedInsteadOfCrashing() {
        assertNull(GitHubConfig.deobfuscate("not base64 at all!!"));
    }

    /**
     * The real thing: whatever the build actually put on the classpath must decode into credentials the
     * app can use. Skipped when the build carries no backend at all (no CI secret and no local
     * {@code secrets/embedded-github.properties}), so an unconfigured build still passes.
     */
    @Test
    void theBlobTheBuildEmbeddedDecodesIntoUsableCredentials() throws Exception {
        try (InputStream in = GitHubConfig.class.getResourceAsStream("/deylauncher-backend.dat")) {
            assumeTrue(in != null, "this build has no embedded backend configured");
            String packed = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            String plain = GitHubConfig.deobfuscate(packed);
            assertNotNull(plain, "the embedded blob must be in the format GitHubConfig expects -- "
                    + "packEmbeddedBackend (build.gradle.kts) and deobfuscate must stay compatible");
            Properties props = new Properties();
            props.load(new StringReader(plain));
            assertTrue(props.getProperty("owner", "").contains("onpishi"),
                    "the embedded owner must be the backend bot account");
            assertTrue(props.getProperty("repo", "").isBlank() == false,
                    "the embedded repo must be named");
            assertTrue(props.getProperty("capesRepo", "").isBlank() == false
                            || GitHubConfig.DEFAULT_CAPES_REPO.equals(props.getProperty("capesRepo", "DeyLauncher-Capes")),
                    "the embedded cape repo must be named (or default to the public cape repo)");
            GitHubConfig cfg = GitHubConfig.fromProperties(props);
            assertTrue(!cfg.capesRepo.equals(cfg.repo),
                    "capes must resolve to the public cape repo, never the private friends repo");
        }
    }

    /**
     * The cape split: {@code capes.json} + the cape textures are read ANONYMOUSLY by the in-game mod, so
     * they live in the public cape repo, while {@code friends.json} / {@code options-kits.json} /
     * {@code capes-owned.json} stay in the private repo. A properties block that never mentions capes
     * must still resolve capes to the public repo -- otherwise a minimal (or older) backend file would
     * silently point DeyCapes back at a private repo and nothing would ever render.
     */
    @Test
    void capeDataResolvesToThePublicCapeRepoWhileFriendsStayPrivate() {
        Properties props = new Properties();
        props.setProperty("token", "github_pat_placeholder");
        props.setProperty("owner", "onpishi");
        props.setProperty("repo", "DeyLauncher-Friends");

        GitHubConfig cfg = GitHubConfig.fromProperties(props);

        assertEquals("onpishi", cfg.owner);
        assertEquals("DeyLauncher-Friends", cfg.repo, "friends data must stay in the private repo");
        assertEquals("onpishi", cfg.capesOwner, "the cape repo lives on the same account by default");
        assertEquals(GitHubConfig.DEFAULT_CAPES_REPO, cfg.capesRepo,
                "cape data must go to the public repo the in-game mod can read with no token");
        assertEquals("capes.json", cfg.capesPath);
    }

    @Test
    void anExplicitCapeRepoOverrideIsHonoured() {
        Properties props = new Properties();
        props.setProperty("token", "github_pat_placeholder");
        props.setProperty("owner", "somegroup");
        props.setProperty("repo", "private-data");
        props.setProperty("capesOwner", "somegroup");
        props.setProperty("capesRepo", "public-capes");

        GitHubConfig cfg = GitHubConfig.fromProperties(props);

        assertEquals("private-data", cfg.repo, "an override must not disturb the private repo");
        assertEquals("public-capes", cfg.capesRepo);
        assertEquals("somegroup", cfg.capesOwner);
    }

    @Test
    void thePublicCapeRepoIsNeverThePrivateFriendsRepo() {
        assertTrue(!"DeyLauncher-Friends".equals(GitHubConfig.DEFAULT_CAPES_REPO),
                "the in-game mod reads the cape repo with no credentials, so it must never be "
                        + "the private friends repo");
    }
}
