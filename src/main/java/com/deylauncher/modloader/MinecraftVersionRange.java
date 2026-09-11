package com.deylauncher.modloader;

/**
 * Minimal parser for the Minecraft version-constraint strings Fabric/Forge mods declare (and that the
 * game's mod loader enforces) -- e.g. {@code "1.21.1"}, {@code ">=1.19.4"}, {@code "<1.21"},
 * {@code "1.19 || 1.19.1 || 1.19.2"}, {@code "1.19.4 - 1.21"}, {@code "1.19.x"}, {@code "(-∞,∞)"}.
 * It is intentionally small and tolerant: anything it can't parse is treated as an unbounded "matches".
 */
public final class MinecraftVersionRange {

    private MinecraftVersionRange() {}

    /**
     * Modrinth / Fabric dependency strings sometimes wrap the constraint in square or curly brackets
     * (e.g. {@code "[0.9.x]"}, {@code "[1.19.4 - 1.21]"}). Strip those so the rest of the parser sees
     * the bare range -- Fabric's own loader writes them this way in {@code fabric.mod.json}.
     */
    private static String normalize(String r) {
        r = r.trim();
        while (r.length() >= 2 && ((r.startsWith("[") && r.endsWith("]"))
                || (r.startsWith("{") && r.endsWith("}")))) {
            r = r.substring(1, r.length() - 1).trim();
        }
        return r;
    }

    /** True if {@code version} satisfies {@code range}. A blank / unknown range always matches. */
    public static boolean matches(String range, String version) {
        if (range == null) return true;
        range = normalize(range);
        if (range.isBlank()) return true;
        // Modrinth/others sometimes use "(-∞,∞)" or "*" to mean unlimited.
        if (range.equals("*") || range.equals("(-∞,∞)") || range.contains("∞")) return true;
        // Multiple alternatives joined with "||".
        for (String part : range.split("\\|\\|")) {
            if (matchesSingle(part.trim(), version)) return true;
        }
        return false;
    }

    private static boolean matchesSingle(String r, String version) {
        if (r.isBlank() || r.equals("*")) return true;

        int[] ver = parse(version);

        // Inclusive range "A - B" (e.g. "1.19.4 - 1.21").
        int dash = r.indexOf(" - ");
        if (dash >= 0) {
            return compare(parse(r.substring(0, dash).trim()), ver) <= 0
                    && compare(parse(r.substring(dash + 3).trim()), ver) >= 0;
        }

        // Comparison operators. compare(X, version) is -1/0/1 for X < / == / > version.
        if (r.startsWith(">=")) return compare(parse(r.substring(2).trim()), ver) <= 0;
        if (r.startsWith("<=")) return compare(parse(r.substring(2).trim()), ver) >= 0;
        if (r.startsWith("==")) return compare(parse(r.substring(2).trim()), ver) == 0;
        if (r.startsWith("!=")) return compare(parse(r.substring(2).trim()), ver) != 0;
        if (r.startsWith(">")) return compare(parse(r.substring(1).trim()), ver) < 0;
        if (r.startsWith("<")) return compare(parse(r.substring(1).trim()), ver) > 0;
        if (r.startsWith("=")) return compare(parse(r.substring(1).trim()), ver) == 0;

        // Family wildcard: "1.19.x" / "1.19.*" covers 1.19, 1.19.1, 1.19.4, etc.
        if (r.endsWith(".x") || r.endsWith(".*")) {
            String prefix = r.substring(0, r.length() - 2).replace('*', 'x');
            return version.startsWith(prefix)
                    && (version.length() == prefix.length()
                        || version.charAt(prefix.length()) == '.');
        }

        // Fabric/Forge tilde-compatible-release: "~1.21.1" => >=1.21.1 <1.21.2; "~26.2" => >=26.2 <26.3.
        if (r.startsWith("~") && r.length() > 1) {
            int[] lo = parse(r.substring(1).trim());
            int[] hi = lo.clone();
            hi[hi.length - 1] += 1; // open upper bound: bump the last numeric segment
            return compare(lo, ver) <= 0 && compare(ver, hi) < 0;
        }

        // Bare version: exact match.
        return compare(parse(r), ver) == 0;
    }

    private static int compare(int[] a, int[] b) {
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int av = i < a.length ? a[i] : 0;
            int bv = i < b.length ? b[i] : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    /** Numeric segments of a version string; non-numeric trailing junk is ignored. */
    private static int[] parse(String s) {
        if (s == null) s = "";
        // Strip build/pre-release suffixes like "+mc26.2" or "-alpha" but keep numeric pre like "-beta"? 
        // Keep it simple: take the leading digit/number pattern then split on '.', dropping non-numeric tokens.
        java.util.List<Integer> parts = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(s);
        while (m.find() && parts.size() < 8) {
            parts.add(Integer.parseInt(m.group()));
        }
        int[] out = new int[parts.size()];
        for (int i = 0; i < out.length; i++) out[i] = parts.get(i);
        return out;
    }
}