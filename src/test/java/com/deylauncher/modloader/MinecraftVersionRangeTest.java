package com.deylauncher.modloader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the semantics of {@link MinecraftVersionRange}. The comparison-operator direction here
 * is the subtle part that was wrong before fixes landed: {@code compare(rangeValue, mcVersion)}
 * returns -1/0/1 for {@code rangeValue </==/>  mcVersion}, so e.g. {@code ">=1.21"} must be satisfied
 * by {@code 1.21.1} (range value is less than the version).
 */
class MinecraftVersionRangeTest {

    @Test
    void blankAndUnlimitedAlwaysMatch() {
        assertTrue(MinecraftVersionRange.matches(null, "1.21.1"));
        assertTrue(MinecraftVersionRange.matches("", "1.21.1"));
        assertTrue(MinecraftVersionRange.matches("   ", "1.21.1"));
        assertTrue(MinecraftVersionRange.matches("*", "1.21.1"));
        assertTrue(MinecraftVersionRange.matches("(-∞,∞)", "1.21.1"));
        assertTrue(MinecraftVersionRange.matches("1.19 || * || 1.20", "26.2"));
    }

    @Test
    void bareVersionIsExactMatch() {
        assertTrue(MinecraftVersionRange.matches("1.21.1", "1.21.1"));
        assertFalse(MinecraftVersionRange.matches("1.21.1", "1.21.2"));
        assertFalse(MinecraftVersionRange.matches("1.21", "1.21.1")); // bare family tag used as exact, not prefix
    }

    @Test
    void comparisonOperatorsAreVersionRelative() {
        // ">=1.21" means the version must be >= 1.21; parse("1.21") < parse("1.21.1").
        assertTrue(MinecraftVersionRange.matches(">=1.21", "1.21.1"));
        assertTrue(MinecraftVersionRange.matches(">=1.21", "1.21"));
        assertFalse(MinecraftVersionRange.matches(">=1.22", "1.21.1"));

        assertTrue(MinecraftVersionRange.matches("<=1.21.1", "1.21.1"));
        assertTrue(MinecraftVersionRange.matches("<=1.21", "1.20.4"));
        assertFalse(MinecraftVersionRange.matches("<=1.21", "1.21.1"));

        assertTrue(MinecraftVersionRange.matches("<1.21.2", "1.21.1"));
        assertFalse(MinecraftVersionRange.matches("<1.21", "1.21.1"));

        assertTrue(MinecraftVersionRange.matches(">1.21", "1.21.1"));
        assertFalse(MinecraftVersionRange.matches(">1.21.1", "1.21.1"));

        assertTrue(MinecraftVersionRange.matches("=1.21.1", "1.21.1"));
        assertFalse(MinecraftVersionRange.matches("=1.21", "1.21.1"));

        assertTrue(MinecraftVersionRange.matches("!=1.21", "1.21.1"));
        assertFalse(MinecraftVersionRange.matches("!=1.21.1", "1.21.1"));
    }

    @Test
    void inclusiveDashRange() {
        assertTrue(MinecraftVersionRange.matches("1.19.4 - 1.21", "1.19.4"));
        assertTrue(MinecraftVersionRange.matches("1.19.4 - 1.21", "1.20"));
        assertTrue(MinecraftVersionRange.matches("1.19.4 - 1.21", "1.21"));
        assertFalse(MinecraftVersionRange.matches("1.19.4 - 1.21", "1.19.3"));
        assertFalse(MinecraftVersionRange.matches("1.19.4 - 1.21", "1.21.5"));
    }

    @Test
    void alternativesWithDoublePipe() {
        assertTrue(MinecraftVersionRange.matches("1.19 || 1.19.1 || 1.19.2", "1.19.1"));
        assertTrue(MinecraftVersionRange.matches("1.19 || 1.19.1", "1.19"));
        assertFalse(MinecraftVersionRange.matches("1.19 || 1.19.1", "1.21"));
        assertFalse(MinecraftVersionRange.matches("1.19 || 1.19.1", "1.19.4"));
    }

    @Test
    void familyWildcard() {
        assertTrue(MinecraftVersionRange.matches("1.19.x", "1.19"));
        assertTrue(MinecraftVersionRange.matches("1.19.x", "1.19.1"));
        assertTrue(MinecraftVersionRange.matches("1.19.x", "1.19.4"));
        assertFalse(MinecraftVersionRange.matches("1.19.x", "1.20"));
        assertFalse(MinecraftVersionRange.matches("1.19.x", "1.190")); // different family, must not match
        assertTrue(MinecraftVersionRange.matches("1.21.*", "1.21.4"));
    }

    @Test
    void tildeCompatibleRelease() {
        // ~1.21.1 => >=1.21.1 <1.21.2
        assertTrue(MinecraftVersionRange.matches("~1.21.1", "1.21.1"));
        assertFalse(MinecraftVersionRange.matches("~1.21.1", "1.21"));
        assertFalse(MinecraftVersionRange.matches("~1.21.1", "1.21.2"));
        assertFalse(MinecraftVersionRange.matches("~1.21.1", "1.21.5"));
        assertFalse(MinecraftVersionRange.matches("~1.21.1", "1.20"));

        // ~26.2 => >=26.2 <26.3
        assertTrue(MinecraftVersionRange.matches("~26.2", "26.2"));
        assertTrue(MinecraftVersionRange.matches("~26.2", "26.2.1"));
        assertFalse(MinecraftVersionRange.matches("~26.2", "26.1"));
        assertFalse(MinecraftVersionRange.matches("~26.2", "26.3"));

        // Ranges drawn from the actual bundled DeyCapes jars.
        assertFalse(MinecraftVersionRange.matches(">=1.19.4", "1.19.3")); // the reported crash
        assertTrue(MinecraftVersionRange.matches(">=1.19.4", "1.19.4"));
        assertFalse(MinecraftVersionRange.matches(">=1.20.6", "1.20.0"));
        assertTrue(MinecraftVersionRange.matches(">=1.18.2", "1.18.2"));
        assertFalse(MinecraftVersionRange.matches(">=1.18.2", "1.18.1"));
        assertFalse(MinecraftVersionRange.matches(">=1.21.2", "1.21.1"));
        assertTrue(MinecraftVersionRange.matches(">=1.21.2", "1.21.2"));
    }
}