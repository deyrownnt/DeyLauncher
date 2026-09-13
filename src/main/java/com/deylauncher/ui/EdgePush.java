package com.deylauncher.ui;

import javafx.geometry.Rectangle2D;

import java.util.List;

/**
 * Pure geometry behind "parking a window off any edge": given the global pointer position, the bounds
 * of the screen it is actually on, and every screen on the desktop, works out the outward slide for
 * the current frame.
 *
 * <p>Rules:
 * <ul>
 *   <li>The pointer counts as <em>pinned</em> when it is within {@code edgePx} of one of the FOUR edges
 *       of the screen under the pointer -- not the outer bound of the whole desktop, which would force
 *       the user to drag into an extreme outer corner on a multi-monitor setup.</li>
 *   <li>A direction is suppressed when another screen lies immediately beyond it, so a window can still
 *       be slid from one monitor to an adjacent one instead of being flung off the shared seam.</li>
 *   <li>{@code topEnabled} lets the caller keep the top edge reserved for drag-to-top maximize until the
 *       pointer has dwelled there long enough to mean "push the window off the top".</li>
 * </ul>
 *
 * <p>Stateless, allocation-free, and free of JavaFX runtime dependencies (only the immutable
 * {@link Rectangle2D} geometry value), so the behaviour is unit-testable headlessly.
 */
public final class EdgePush {

    /** Direction flags used by {@link #axes} and {@link #slide}. */
    public static final int LEFT = 1, RIGHT = 2, TOP = 4, BOTTOM = 8;

    /** Longest frame time (s) one slide step may use, so a stalled pulse can't jump the window. */
    public static final double MAX_DT = 0.05;

    private EdgePush() {}

    /** True when the pointer is within {@code edgePx} of any edge of {@code screen} (cheap: no screen list). */
    public static boolean inAnyBand(double px, double py, Rectangle2D screen, double edgePx) {
        return px <= screen.getMinX() + edgePx
                || px >= screen.getMaxX() - edgePx
                || py <= screen.getMinY() + edgePx
                || py >= screen.getMaxY() - edgePx;
    }

    /**
     * Which edges of {@code screen} the pointer is pinned against, or {@code 0} when it isn't pinned.
     *
     * @param allScreens every screen on the desktop, so an edge shared with a neighbour doesn't push
     * @param topEnabled false while the top edge is still reserved for drag-to-top maximize
     */
    public static int axes(double px, double py, Rectangle2D screen,
                           List<Rectangle2D> allScreens, double edgePx, boolean topEnabled) {
        int axes = 0;

        // Probe one edge-width BEYOND the pointer. Because the pointer is already inside a band, that
        // point is guaranteed to lie outside `screen`, so landing on a screen there means there really
        // is desktop space in that direction and the window must not be pushed off it.
        double beyond = edgePx + 2.0;

        if (px <= screen.getMinX() + edgePx && !covered(px - beyond, py, allScreens)) axes |= LEFT;
        else if (px >= screen.getMaxX() - edgePx && !covered(px + beyond, py, allScreens)) axes |= RIGHT;

        if (py >= screen.getMaxY() - edgePx && !covered(px, py + beyond, allScreens)) axes |= BOTTOM;
        else if (topEnabled && py <= screen.getMinY() + edgePx && !covered(px, py - beyond, allScreens)) axes |= TOP;

        return axes;
    }

    /** Converts a direction mask + speed + frame time into a screen delta, written into {@code out = {dx, dy}}. */
    public static void slide(int axes, double speedPxPerSec, double dt, double[] out) {
        double t = dt <= 0 ? 0 : Math.min(dt, MAX_DT);
        double step = speedPxPerSec * t;
        double dx = 0, dy = 0;
        if ((axes & LEFT) != 0) dx -= step;
        if ((axes & RIGHT) != 0) dx += step;
        if ((axes & TOP) != 0) dy -= step;
        if ((axes & BOTTOM) != 0) dy += step;
        out[0] = dx;
        out[1] = dy;
    }

    /**
     * The half of a work area a left/right edge snap fills. An odd width splits with no gap and no
     * overlap (the left half is floored, the right half takes the remainder).
     *
     * @param leftHalf true for the screen's left half, false for its right half
     */
    public static Rectangle2D halfTile(Rectangle2D workArea, boolean leftHalf) {
        double w = Math.floor(workArea.getWidth() / 2.0);
        return new Rectangle2D(
                leftHalf ? workArea.getMinX() : workArea.getMinX() + w,
                workArea.getMinY(),
                leftHalf ? w : workArea.getWidth() - w,
                workArea.getHeight());
    }

    /** True when (x,y) lies inside any of the given screens. */
    private static boolean covered(double x, double y, List<Rectangle2D> screens) {
        for (int i = 0; i < screens.size(); i++) {
            Rectangle2D s = screens.get(i);
            if (x >= s.getMinX() && x < s.getMaxX() && y >= s.getMinY() && y < s.getMaxY()) return true;
        }
        return false;
    }
}
