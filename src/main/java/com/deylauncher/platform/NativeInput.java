package com.deylauncher.platform;

import com.sun.jna.Library;
import com.sun.jna.Native;

/**
 * Native access to the GLOBAL mouse -- pointer position and primary-button state -- regardless of
 * whether the cursor is inside a JavaFX window. This is what a custom (undecorated) window needs to
 * drag like a real system window: JavaFX only reports {@code MOUSE_DRAGGED}/{@code MOUSE_RELEASED}
 * while the cursor is inside the window, so a normal drag stops at the window/screen edge and can't
 * be parked off-screen. Reading the OS pointer directly lets the drag keep going (and stop cleanly on
 * release) even after the cursor leaves the window.
 *
 * <p>Implementation per platform:
 * <ul>
 *   <li><b>Windows</b> -- JNA {@code GetCursorPos} + {@code GetAsyncKeyState(VK_LBUTTON)}.</li>
 *   <li><b>Linux/macOS (X11)</b> -- JNA {@code XQueryPointer} on the root window (Button1Mask).</li>
 *   <li><b>Wayland / anything else</b> -- not supported; {@link #isSupported()} is false and callers
 *       fall back to the built-in JavaFX drag.</li>
 * </ul>
 * Everything is best-effort: if a platform can't be read it never throws, it just reports unsupported.
 */
public final class NativeInput {

    /** A snapshot of the global pointer. */
    public record NavPoint(int x, int y, boolean primaryDown) {}

    public enum Mode { NONE, WINDOWS, X11 }

    private interface WindowsLib extends Library {
        // BOOL GetCursorPos(POINT *pt) where POINT = { int x; int y; }
        int GetCursorPos(int[] point);
        // SHORT GetAsyncKeyState(int vk)   (VK_LBUTTON = 0x01)
        short GetAsyncKeyState(int vk);
    }

    private interface X11Lib extends Library {
        // Display *XOpenDisplay(const char *name)
        long XOpenDisplay(String name);
        // Window XDefaultRootWindow(Display *dpy)
        long XDefaultRootWindow(long display);
        // Status XQueryPointer(Display*, Window, Window*, Window*, int*, int*, int*, int*, unsigned int*)
        int XQueryPointer(long display, long w,
                          long[] rootReturn, long[] childReturn,
                          int[] rootXReturn, int[] rootYReturn,
                          int[] winXReturn, int[] winYReturn,
                          int[] maskReturn);
        int XCloseDisplay(long display);
    }

    private NativeInput() {}

    private static final Mode MODE;
    private static final WindowsLib WIN;
    private static final X11Lib X11LIB;
    private static volatile long X11_DISPLAY;

    static {
        Mode m = detectMode();
        WindowsLib win = null;
        X11Lib x11 = null;
        try {
            if (m == Mode.WINDOWS) {
                win = Native.load("User32", WindowsLib.class);
            } else if (m == Mode.X11) {
                x11 = Native.load("X11", X11Lib.class);
            }
        } catch (Throwable ignored) {
            m = Mode.NONE; // library missing/can't load -> callers fall back to JavaFX drag
        }
        MODE = m;
        WIN = win;
        X11LIB = x11;
    }

    public static boolean isSupported() { return MODE != Mode.NONE; }

    public static Mode mode() { return MODE; }

    private static Mode detectMode() {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) return Mode.WINDOWS;
            // If DISPLAY is set there is a real X server (native X11, or XWayland under Wayland, or via
            // ssh -X), and XQueryPointer gives valid global cursor position + primary-button state that
            // matches win.setX/Y. True headless/pure-Wayland-without-X has no DISPLAY -> NONE.
            if (System.getenv("DISPLAY") != null) return Mode.X11;
        } catch (Exception ignored) {
            // fall through
        }
        return Mode.NONE;
    }

    /** Reads the current global pointer position + primary-button state, or null if unsupported/unavailable. */
    public static NavPoint read() {
        try {
            switch (MODE) {
                case WINDOWS: return readWindows();
                case X11:     return readX11();
                default:      return null;
            }
        } catch (Throwable ignored) {
            return null; // never crash the UI over a pointer read
        }
    }

    private static NavPoint readWindows() {
        int[] pt = new int[2];
        if (WIN.GetCursorPos(pt) == 0) return null; // BOOL false -> couldn't read
        short state = WIN.GetAsyncKeyState(0x01);    // VK_LBUTTON, bit 15 = physically down
        return new NavPoint(pt[0], pt[1], (state & 0x8000) != 0);
    }

    private static NavPoint readX11() {
        long display;
        synchronized (X11Lib.class) {
            long d = X11_DISPLAY;
            if (d == 0) {
                d = X11LIB.XOpenDisplay(System.getenv("DISPLAY"));
                X11_DISPLAY = d;
            }
            display = d;
        }
        if (display == 0) return null;

        long root = X11LIB.XDefaultRootWindow(display);
        long[] rootReturn = new long[1], childReturn = new long[1];
        int[] rootX = new int[1], rootY = new int[1];
        int[] winX = new int[1], winY = new int[1];
        int[] mask = new int[1];
        int status = X11LIB.XQueryPointer(display, root, rootReturn, childReturn, rootX, rootY, winX, winY, mask);
        if (status == 0) return null;
        boolean down = (mask[0] & (1 << 8)) != 0; // Button1Mask
        return new NavPoint(rootX[0], rootY[0], down);
    }
}