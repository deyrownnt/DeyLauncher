package org.junit.jupiter.api;
import java.util.Objects;
/** Minimal stand-in for JUnit 5 Assertions -- sandbox verification only, NOT part of the deliverable. */
public final class Assertions {
    private static String m(String msg) { return msg == null ? "" : msg + " ==> "; }
    public static void fail(String msg) { throw new AssertionError(msg); }
    public static void fail() { throw new AssertionError(); }
    public static void assertTrue(boolean c) { if (!c) throw new AssertionError("expected true"); }
    public static void assertTrue(boolean c, String msg) { if (!c) throw new AssertionError(m(msg) + "expected true"); }
    public static void assertFalse(boolean c) { if (c) throw new AssertionError("expected false"); }
    public static void assertFalse(boolean c, String msg) { if (c) throw new AssertionError(m(msg) + "expected false"); }
    public static void assertNull(Object o) { if (o != null) throw new AssertionError("expected null but was: " + o); }
    public static void assertNull(Object o, String msg) { if (o != null) throw new AssertionError(m(msg) + "expected null but was: " + o); }
    public static void assertNotNull(Object o) { if (o == null) throw new AssertionError("expected non-null"); }
    public static void assertNotNull(Object o, String msg) { if (o == null) throw new AssertionError(m(msg) + "expected non-null"); }
    public static void assertEquals(Object e, Object a) { if (!Objects.equals(e, a)) throw new AssertionError("expected: <" + e + "> but was: <" + a + ">"); }
    public static void assertEquals(Object e, Object a, String msg) { if (!Objects.equals(e, a)) throw new AssertionError(m(msg) + "expected: <" + e + "> but was: <" + a + ">"); }
    public static void assertEquals(long e, long a) { if (e != a) throw new AssertionError("expected: <" + e + "> but was: <" + a + ">"); }
    public static void assertEquals(long e, long a, String msg) { if (e != a) throw new AssertionError(m(msg) + "expected: <" + e + "> but was: <" + a + ">"); }
    public static void assertEquals(double e, double a, double d) { if (Math.abs(e - a) > d) throw new AssertionError("expected: <" + e + "> but was: <" + a + ">"); }
    public static void assertArrayEquals(byte[] e, byte[] a) { if (!java.util.Arrays.equals(e, a)) throw new AssertionError("arrays differ"); }
    public static void assertArrayEquals(Object[] e, Object[] a) { if (!java.util.Arrays.deepEquals(e, a)) throw new AssertionError("arrays differ"); }
    public static void assertNotEquals(Object u, Object a) { if (Objects.equals(u, a)) throw new AssertionError("expected different from <" + u + ">"); }
    public static void assertSame(Object e, Object a) { if (e != a) throw new AssertionError("expected same"); }
    public interface Executable { void execute() throws Throwable; }
    public static <T extends Throwable> T assertThrows(Class<T> t, Executable ex) {
        try { ex.execute(); } catch (Throwable x) { if (t.isInstance(x)) return t.cast(x); throw new AssertionError("wrong exception: " + x, x); }
        throw new AssertionError("expected " + t.getName() + " to be thrown");
    }
}
