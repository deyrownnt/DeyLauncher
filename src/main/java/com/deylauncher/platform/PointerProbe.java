package com.deylauncher.platform;

import javafx.geometry.Point2D;
import javafx.scene.robot.Robot;

/**
 * Reads the GLOBAL pointer -- where it is on the desktop even while it sits outside every JavaFX
 * window -- so an undecorated (borderless) window can be dragged like a real OS window and parked
 * partly/fully off any edge of any screen.
 *
 * <p>Two tiers, best first, chosen once per session:
 * <ol>
 *   <li><b>Pure JavaFX</b> -- {@link Robot#getMousePosition()} (JavaFX's own global pointer read, no
 *       extra dependency at all). It reports the position only: JavaFX exposes no button state, so
 *       callers still learn about the mouse-up from the JavaFX drag/release events.</li>
 *   <li><b>Optional native fallback</b> -- {@link NativeInput} (JNA): {@code GetCursorPos} +
 *       {@code GetAsyncKeyState} on Windows, {@code XQueryPointer} on X11. It reports the position
 *       <em>and</em> the primary-button state, which lets a drag that is pinned against a screen edge
 *       end itself on the true mouse-up even if no release event ever arrives.</li>
 * </ol>
 *
 * <p>Robot must be constructed and queried on the JavaFX Application Thread. Every probe is
 * best-effort: a tier that throws while being used is demoted permanently, so a platform that can't
 * support it costs one exception for the whole session, never one per frame.
 */
public final class PointerProbe {

    /** A global pointer sample. {@code downKnown} is false when the active tier can't see the button. */
    public record Sample(double x, double y, boolean downKnown, boolean down) {}

    private enum Tier { ROBOT, NATIVE, NONE }

    private static Tier tier = Tier.ROBOT; // pure JavaFX first; falls back to the native tier if it fails
    private static Robot robot;

    private PointerProbe() {}

    /** Name of the tier actually in use -- diagnostics only. */
    public static String describe() {
        switch (tier) {
            case ROBOT:  return "JavaFX Robot";
            case NATIVE: return "native (JNA, " + NativeInput.mode() + ")";
            default:     return "unavailable";
        }
    }

    /**
     * The current global pointer, or {@code null} when no tier can read it (the caller then keeps using
     * the JavaFX drag events it already receives). Must run on the JavaFX Application Thread.
     */
    public static Sample probe() {
        if (tier == Tier.ROBOT) {
            try {
                Robot r = robot;
                if (r == null) {
                    r = new Robot();
                    robot = r;
                }
                Point2D p = r.getMousePosition();
                if (p != null && !Double.isNaN(p.getX()) && !Double.isNaN(p.getY())) {
                    return new Sample(p.getX(), p.getY(), false, false);
                }
            } catch (Throwable ignored) {
                // No JavaFX Robot here (or it broke): demote once and use the optional native tier.
            }
            synchronized (PointerProbe.class) {
                if (tier == Tier.ROBOT) tier = NativeInput.isSupported() ? Tier.NATIVE : Tier.NONE;
            }
        }
        return tier == Tier.NATIVE ? probeNative() : null;
    }

    private static Sample probeNative() {
        NativeInput.NavPoint pt = NativeInput.read();
        return pt == null ? null : new Sample(pt.x(), pt.y(), true, pt.primaryDown());
    }
}
