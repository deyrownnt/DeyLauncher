package com.deylauncher.modpack;

import java.util.Locale;

/**
 * Decides whether DeyLauncher may download a build of a mod from a content source on someone's
 * behalf -- i.e. whether the mod's own terms actually allow that redistribution.
 *
 * <p>This exists because DeyLauncher deliberately uses only FREE, KEY-LESS routes, and neither of
 * the key-less CurseForge routes it can reach states a per-file distribution flag:
 * <ul>
 *   <li>the keyless file endpoint answers with {@code fileName}/{@code fileLength}/{@code gameVersions}
 *       only -- no {@code downloadUrl} and no {@code allowModDistribution};</li>
 *   <li>the third-party {@code api.cfwidget.com} mirror returns no license and no distribution flag at
 *       all -- its per-file {@code url} is just the human CurseForge <i>page</i>.</li>
 * </ul>
 * The official CurseForge API does expose those flags, but it needs an API key, and DeyLauncher
 * deliberately does not ship or ask for one.
 *
 * <p>So permission is established from the one signal we CAN read legitimately and offline: the
 * license the mod itself declares in its own jar ({@code fabric.mod.json}, {@code mods.toml}, or a
 * bundled LICENSE file -- see {@code ModsManager#modLicense}). That is exactly the case the
 * autofix/checker path runs in: the jar is already installed, so its own terms are on disk.
 *
 * <p>The rule is deliberately conservative and fails closed:
 * <ul>
 *   <li><b>ALLOWED</b> -- only for a recognised identifier/phrase that grants redistribution
 *       (MIT, Apache-2.0, (L/A)GPL, BSD, MPL, ISC, Zlib, Unlicense, CC0, ... or an explicit
 *       "you may redistribute" statement).</li>
 *   <li><b>NOT_ALLOWED</b> -- an explicit "All Rights Reserved"/"do not redistribute"-style term.</li>
 *   <li><b>UNKNOWN</b> -- anything unrecognised, missing, or a custom/EULA-style license. Never
 *       treated as permission: the caller skips the file and reports why.</li>
 * </ul>
 *
 * <p>Pure text logic, no network and no I/O, so it is trivially unit-testable and cannot be talked
 * into a wrong answer by a failing request.
 */
public final class ModDistributionPolicy {

    private ModDistributionPolicy() {}

    /** What a mod's own declared license says about downloading one of its builds for a user. */
    public enum Verdict {
        /** The license grants redistribution -- the build may be fetched from a content source. */
        ALLOWED,
        /** The license explicitly forbids it -- never fetched automatically. */
        NOT_ALLOWED,
        /** Nothing we can rely on (custom terms, missing license, unrecognised text) -- not permission. */
        UNKNOWN
    }

    /**
     * License identifiers whose terms permit redistributing the licensed work (and therefore
     * downloading a build of it on a user's behalf). Matched case-insensitively against the mod's
     * declared license string, so both SPDX ids ("MIT", "GPL-3.0-only") and the human spellings
     * mods actually put in their metadata ("LGPLv3", "Apache License 2.0") line up.
     */
    private static final String[] REDISTRIBUTABLE_IDS = {
            "mit", "mit-0", "mit license",
            "apache", "apache-2.0", "apache license",
            "bsd", "bsd-2-clause", "bsd-3-clause",
            "gpl", "gpl-2.0", "gpl-3.0", "gplv2", "gplv3", "gnu general public license",
            "lgpl", "lgpl-2.1", "lgpl-3.0", "lgplv2", "lgplv3", "gnu lesser general public license",
            "agpl", "agpl-3.0", "agplv3",
            "mpl", "mpl-2.0", "mozilla public license",
            "epl-2.0", "eclipse public license",
            "cddl", "cddl-1.0",
            "isc", "zlib", "libpng", "boost", "wtfpl",
            "unlicense", "cc0", "public domain",
            "cc-by-4.0", "cc-by-sa-4.0", "creative commons attribution",
            "osl-3.0", "eupl"
    };

    /** Phrases that clearly forbid redistribution, whatever else the license says. */
    private static final String[] FORBIDDING_PHRASES = {
            "all rights reserved",
            "no redistribution",
            "not redistribute",
            "do not redistribute",
            "redistribution is prohibited",
            "proprietary"
    };

    /** Phrases that clearly grant redistribution even when no identifier is present. */
    private static final String[] GRANTING_PHRASES = {
            "you may redistribute",
            "you are free to redistribute",
            "may be redistributed",
            "permission is granted to redistribute",
            "redistribution is permitted",
            "redistribution is allowed"
    };
    /**
     * Classifies a mod's declared license. {@code null}/blank text is {@link Verdict#UNKNOWN}: a mod
     * with no stated license has not granted anything, so we do not fetch its builds for a user.
     */
    public static Verdict classify(String licenseText) {
        if (licenseText == null || licenseText.isBlank()) return Verdict.UNKNOWN;
        String t = normalize(licenseText);

        // An explicit "all rights reserved" wins over a permissive-looking identifier: a mod that is
        // "MIT (code) / All Rights Reserved (assets)" must not be treated as freely redistributable.
        for (String phrase : FORBIDDING_PHRASES) {
            if (t.contains(phrase)) return Verdict.NOT_ALLOWED;
        }
        for (String phrase : GRANTING_PHRASES) {
            if (t.contains(phrase)) return Verdict.ALLOWED;
        }
        for (String id : REDISTRIBUTABLE_IDS) {
            if (containsIdentifier(t, id)) return Verdict.ALLOWED;
        }
        return Verdict.UNKNOWN;
    }

    /** Shorthand for the only verdict that permits downloading a build. */
    public static boolean allowsRedistribution(String licenseText) {
        return classify(licenseText) == Verdict.ALLOWED;
    }

    /** Human-readable explanation for the log/alert, tailored to the verdict. */
    public static String explain(Verdict verdict, String licenseText) {
        switch (verdict) {
            case ALLOWED:
                return "its own license (" + shortForm(licenseText) + ") allows redistribution";
            case NOT_ALLOWED:
                return "its own license (" + shortForm(licenseText) + ") does not allow redistribution";
            default:
                return licenseText == null || licenseText.isBlank()
                        ? "it declares no license we can rely on"
                        : "its license (" + shortForm(licenseText) + ") doesn't let us confirm "
                          + "distribution permission";
        }
    }

    /** Lowercased, whitespace-collapsed text, with the punctuation mods sprinkle around ids removed. */
    private static String normalize(String licenseText) {
        String t = licenseText.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        return t.replaceAll("[,;()\\[\\]{}\"'`]", " ").replaceAll("\\s+", " ").trim();
    }

    /** True when {@code id} appears as a whole term, so "mit" never matches "permit" or "submit". */
    private static boolean containsIdentifier(String text, String id) {
        int from = 0;
        while (true) {
            int at = text.indexOf(id, from);
            if (at < 0) return false;
            boolean leftOk = at == 0 || !isAttached(text.charAt(at - 1));
            int end = at + id.length();
            boolean rightOk = end >= text.length() || !isAttached(text.charAt(end));
            if (leftOk && rightOk) return true;
            from = at + 1;
        }
    }

    /**
     * Whether a character counts as glued to a license id. Only letters and digits do: that keeps
     * "mit" out of "permit"/"submit" while still letting SPDX's decorations through, so
     * {@code GPL-3.0-only}, {@code LGPL-2.1-or-later} and {@code Apache-2.0} all match their base id.
     */
    private static boolean isAttached(char c) {
        return Character.isLetterOrDigit(c);
    }

    /** The first line of a license blob, trimmed and capped -- enough for a readable log message. */
    private static String shortForm(String licenseText) {
        if (licenseText == null) return "unknown";
        String first = licenseText.trim().split("\\R", 2)[0].trim();
        if (first.length() > 60) first = first.substring(0, 57) + "...";
        return first.isBlank() ? "unknown" : first;
    }
}

