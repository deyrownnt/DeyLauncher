package com.deylauncher.modpack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The permission gate for the key-less CurseForge fallback. It must never say "yes" by accident: a
 * mod's build is only fetched automatically when its OWN declared license clearly permits it, which is
 * the only distribution signal available without a CurseForge API key (see the class doc).
 */
class ModDistributionPolicyTest {

    @Test
    void permissiveLicensesAllowRedistribution() {
        for (String license : new String[]{
                "MIT", "mit", "MIT License", "MIT-0",
                "Apache-2.0", "Apache License 2.0",
                "GPL-3.0", "GPL-3.0-only", "gplv2", "GNU General Public License v3.0",
                "LGPL-2.1-or-later", "LGPLv3",
                "MPL-2.0", "BSD-3-Clause", "ISC", "Zlib", "The Unlicense", "CC0-1.0",
                "CC-BY-4.0", "public domain"}) {
            assertEquals(ModDistributionPolicy.Verdict.ALLOWED,
                    ModDistributionPolicy.classify(license), license);
            assertTrue(ModDistributionPolicy.allowsRedistribution(license), license);
        }
    }

    @Test
    void explicitReservationOfRightsIsRespectedEvenBesideAPermissiveId() {
        assertEquals(ModDistributionPolicy.Verdict.NOT_ALLOWED,
                ModDistributionPolicy.classify("All Rights Reserved"));
        assertEquals(ModDistributionPolicy.Verdict.NOT_ALLOWED,
                ModDistributionPolicy.classify("MIT (code), All Rights Reserved (assets)"),
                "a partly-reserved license must not be treated as freely redistributable");
        assertEquals(ModDistributionPolicy.Verdict.NOT_ALLOWED,
                ModDistributionPolicy.classify("Please do not redistribute this mod"));
        assertFalse(ModDistributionPolicy.allowsRedistribution("Proprietary"));
    }

    @Test
    void unknownOrMissingLicensesNeverCountAsPermission() {
        assertEquals(ModDistributionPolicy.Verdict.UNKNOWN, ModDistributionPolicy.classify(null));
        assertEquals(ModDistributionPolicy.Verdict.UNKNOWN, ModDistributionPolicy.classify("   "));
        assertEquals(ModDistributionPolicy.Verdict.UNKNOWN, ModDistributionPolicy.classify("Custom"));
        assertEquals(ModDistributionPolicy.Verdict.UNKNOWN,
                ModDistributionPolicy.classify("See the project page for terms"));
        assertFalse(ModDistributionPolicy.allowsRedistribution("Custom"),
                "failing closed is the whole point: unknown never becomes permission");
    }

    @Test
    void anIdOnlyMatchesAsAWholeTerm() {
        assertFalse(ModDistributionPolicy.allowsRedistribution("You may not permit others"),
                "\"permit\" must not be mistaken for the MIT license");
        assertFalse(ModDistributionPolicy.allowsRedistribution("Submitted by a fan"));
        assertTrue(ModDistributionPolicy.allowsRedistribution("SPDX-License-Identifier: MIT"));
    }

    @Test
    void rewrittenGrantLanguageAlsoCounts() {
        assertEquals(ModDistributionPolicy.Verdict.ALLOWED,
                ModDistributionPolicy.classify("You may redistribute this mod, with credit"));
        assertEquals(ModDistributionPolicy.Verdict.ALLOWED,
                ModDistributionPolicy.classify("Redistribution is permitted"));
    }

    @Test
    void explainSaysWhyInPlainLanguage() {
        assertTrue(ModDistributionPolicy.explain(ModDistributionPolicy.Verdict.NOT_ALLOWED,
                "All Rights Reserved").contains("does not allow"));
        assertTrue(ModDistributionPolicy.explain(ModDistributionPolicy.Verdict.UNKNOWN, null)
                .contains("no license"));
        assertTrue(ModDistributionPolicy.explain(ModDistributionPolicy.Verdict.UNKNOWN, "Custom")
                .contains("Custom"));
    }
}
