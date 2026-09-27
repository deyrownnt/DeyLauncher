package com.deylauncher.servers;

import com.deylauncher.identity.AccountType;

/**
 * How many semi self-hosted servers one account may keep in the shared servers repos.
 *
 * <p>The limits exist because the repos are written with the project's single shared bot token and
 * every stored server costs repo space plus API rate limit for <i>every</i> DeyLauncher user:
 *
 * <ul>
 *   <li>an <b>offline</b> account -- a locally chosen name with no Mojang verification behind it -- may
 *       own <b>1</b>;</li>
 *   <li>an <b>online</b> (Microsoft/Mojang) account may own <b>5</b>.</li>
 * </ul>
 *
 * <p>An offline identity is derived from the player's name alone ({@code OfflinePlayer:<name>}), so
 * these two numbers are enforced against the <i>owner uuid recorded in the repo</i>, which is what
 * makes the limit follow the account across PCs instead of being a per-install counter.
 */
public final class SemiHostedLimits {

    /** Semi self-hosted servers an offline account may own. */
    public static final int OFFLINE_MAX = 1;

    /** Semi self-hosted servers a Microsoft/Mojang account may own. */
    public static final int ONLINE_MAX = 5;

    private SemiHostedLimits() {
    }

    /** The cap for one account type. A null/unknown type is treated as the stricter offline cap. */
    public static int maxFor(AccountType type) {
        return type == AccountType.ONLINE ? ONLINE_MAX : OFFLINE_MAX;
    }

    /**
     * The cap for the wire value stored in the repo ({@code "ONLINE"} / {@code "OFFLINE"}).
     *
     * <p>Anything unrecognised -- a missing value, a typo, an account type a future build adds -- resolves
     * to the STRICTER offline cap rather than throwing, because a record read from a shared repo must never
     * be able to crash the Servers page, and an unknown type must never buy extra cloud slots.
     */
    public static int maxForWire(String accountTypeWire) {
        if (accountTypeWire == null || accountTypeWire.isBlank()) return OFFLINE_MAX;
        return "ONLINE".equalsIgnoreCase(accountTypeWire.trim()) ? ONLINE_MAX : OFFLINE_MAX;
    }

    /** True when one more server of this account type may be published given {@code alreadyOwned}. */
    public static boolean canAddAnother(AccountType type, int alreadyOwned) {
        return alreadyOwned < maxFor(type);
    }

    /** Plain-English limit line for Settings and for the "you're at the cap" message. */
    public static String describe(AccountType type) {
        return "A " + (type == AccountType.ONLINE ? "Microsoft (online)" : "offline")
                + " account can keep up to " + maxFor(type) + " semi self-hosted server"
                + (maxFor(type) == 1 ? "" : "s") + " in the cloud.";
    }

    /** What to tell someone who just hit the cap. */
    public static String capReachedMessage(AccountType type) {
        return "This " + (type == AccountType.ONLINE ? "online" : "offline")
                + " account already has all " + maxFor(type) + " semi self-hosted server"
                + (maxFor(type) == 1 ? "" : "s") + " it can share."
                + (type == AccountType.ONLINE ? "" : " Sign in with a Microsoft account to raise the limit to "
                + ONLINE_MAX + ".");
    }

    /** A quota readout for the UI, e.g. {@code "2 of 5 cloud slots used"}. */
    public static String usage(AccountType type, int owned) {
        return owned + " of " + maxFor(type) + " cloud slot" + (maxFor(type) == 1 ? "" : "s") + " used";
    }
}
