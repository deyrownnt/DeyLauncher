package org.junit.jupiter.api;
public final class Assumptions {
    public static class Violated extends RuntimeException { public Violated(String m){super(m);} }
    public static void assumeTrue(boolean b) { if (!b) throw new Violated("assumption failed"); }
    public static void assumeTrue(boolean b, String m) { if (!b) throw new Violated(m); }
    public static void assumeFalse(boolean b) { if (b) throw new Violated("assumption failed"); }
}
