package com.deylauncher.ui;

import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the pure geometry that parks a window off any edge of the screen.
 *
 * <p>The cases that matter in practice:
 * <ul>
 *   <li>each of the four edges of the screen the pointer is <em>actually</em> on pushes outward -- and
 *       a corner pushes both axes at once;</li>
 *   <li>it must NOT require the outer bound of the whole desktop (the old "extreme outer corner"
 *       behaviour), so a two-monitor desktop still pushes along a screen's own free edge;</li>
 *   <li>a seam shared with a neighbouring monitor must be silent, otherwise dragging a window from one
 *       display to the next would fling it off;</li>
 *   <li>the top edge stays reserved for drag-to-top maximize until the caller enables it.</li>
 * </ul>
 */
class EdgePushTest {

    private static final Rectangle2D SCREEN = new Rectangle2D(0, 0, 1920, 1080);
    private static final List<Rectangle2D> ONE = List.of(SCREEN);
    private static final double EDGE = 6;
    private static final double SPEED = 700;

    @Test
    void halfTile_splitsTheWorkAreaWithoutGapOrOverlap() {
        Rectangle2D workArea = new Rectangle2D(0, 40, 1920, 1040);
        assertEquals(new Rectangle2D(0, 40, 960, 1040), EdgePush.halfTile(workArea, true));
        assertEquals(new Rectangle2D(960, 40, 960, 1040), EdgePush.halfTile(workArea, false));

        // Odd width: the left half is floored and the right half takes the remainder, so the two tiles
        // still cover the whole work area exactly (no 1px gap, no 1px overlap).
        Rectangle2D odd = new Rectangle2D(10, 0, 1921, 100);
        assertEquals(new Rectangle2D(10, 0, 960, 100), EdgePush.halfTile(odd, true));
        assertEquals(new Rectangle2D(970, 0, 961, 100), EdgePush.halfTile(odd, false));
    }

    @Test
    void inAnyBand_coversEachEdgeAndRejectsTheMiddle() {
        assertTrue(EdgePush.inAnyBand(3, 500, SCREEN, EDGE));    // left
        assertTrue(EdgePush.inAnyBand(1917, 500, SCREEN, EDGE)); // right
        assertTrue(EdgePush.inAnyBand(500, 3, SCREEN, EDGE));    // top
        assertTrue(EdgePush.inAnyBand(500, 1077, SCREEN, EDGE)); // bottom
        assertTrue(EdgePush.inAnyBand(6, 500, SCREEN, EDGE));    // exactly on the band edge (inclusive)
        assertFalse(EdgePush.inAnyBand(7, 507, SCREEN, EDGE));   // just inside -> not pinned
        assertFalse(EdgePush.inAnyBand(960, 540, SCREEN, EDGE)); // dead centre
    }

    @Test
    void axes_pushesOutOfEachRealOuterEdge() {
        assertEquals(EdgePush.LEFT, EdgePush.axes(0, 540, SCREEN, ONE, EDGE, true));
        assertEquals(EdgePush.RIGHT, EdgePush.axes(1919, 540, SCREEN, ONE, EDGE, true));
        assertEquals(EdgePush.TOP, EdgePush.axes(960, 0, SCREEN, ONE, EDGE, true));
        assertEquals(EdgePush.BOTTOM, EdgePush.axes(960, 1079, SCREEN, ONE, EDGE, true));
    }

    @Test
    void axes_pushesBothAxesInACorner() {
        assertEquals(EdgePush.LEFT | EdgePush.TOP, EdgePush.axes(0, 0, SCREEN, ONE, EDGE, true));
        assertEquals(EdgePush.RIGHT | EdgePush.BOTTOM, EdgePush.axes(1919, 1079, SCREEN, ONE, EDGE, true));
    }

    @Test
    void axes_isSilentInTheMiddle() {
        assertEquals(0, EdgePush.axes(960, 540, SCREEN, ONE, EDGE, true));
    }

    @Test
    void axes_keepsTheTopEdgeForMaximizeUntilTheCallerEnablesIt() {
        assertEquals(0, EdgePush.axes(960, 0, SCREEN, ONE, EDGE, false)); // dwell not elapsed yet
        assertEquals(EdgePush.TOP, EdgePush.axes(960, 0, SCREEN, ONE, EDGE, true));
    }

    @Test
    void axes_doesNotPushIntoAnAdjacentMonitor() {
        // A second screen sits directly to the right of SCREEN: a pointer on that shared seam must stay
        // silent, so the window can still be slid from one monitor to the other.
        Rectangle2D right = new Rectangle2D(1920, 0, 1920, 1080);
        List<Rectangle2D> two = List.of(SCREEN, right);
        assertEquals(0, EdgePush.axes(1919, 540, SCREEN, two, EDGE, true));
        assertEquals(0, EdgePush.axes(1920, 540, right, two, EDGE, true));
        // The very same pointer IS a real outer edge when there is no screen beyond it.
        assertEquals(EdgePush.RIGHT, EdgePush.axes(1919, 540, SCREEN, ONE, EDGE, true));
    }

    @Test
    void axes_stillPushesAtAFreeEdgeNextToAVerticallyOffsetMonitor() {
        // A monitor to the right that does not cover this y: at this height the edge really is the
        // desktop boundary, so the window may still be pushed off it.
        Rectangle2D upperRight = new Rectangle2D(1920, 0, 1920, 400);
        List<Rectangle2D> two = List.of(SCREEN, upperRight);
        assertEquals(EdgePush.RIGHT, EdgePush.axes(1919, 900, SCREEN, two, EDGE, true));
        assertEquals(0, EdgePush.axes(1919, 300, SCREEN, two, EDGE, true));
    }

    @Test
    void slide_movesOutwardAtTheGivenSpeed() {
        double[] out = new double[2];

        EdgePush.slide(EdgePush.LEFT, SPEED, 0.01, out);
        assertEquals(-7.0, out[0], 1e-9);
        assertEquals(0.0, out[1], 1e-9);

        EdgePush.slide(EdgePush.TOP | EdgePush.RIGHT, SPEED, 0.01, out);
        assertEquals(7.0, out[0], 1e-9);
        assertEquals(-7.0, out[1], 1e-9);
    }

    @Test
    void slide_clampsStalledFramesAndIgnoresNonPositiveTime() {
        double[] out = new double[2];

        EdgePush.slide(EdgePush.BOTTOM, SPEED, 5.0, out); // a 5s stall must not jump the window
        assertEquals(SPEED * EdgePush.MAX_DT, out[1], 1e-9);

        EdgePush.slide(EdgePush.BOTTOM, SPEED, 0, out);
        assertEquals(0.0, out[1], 1e-9);

        EdgePush.slide(0, SPEED, 0.01, out); // no direction -> no movement
        assertEquals(0.0, out[0], 1e-9);
        assertEquals(0.0, out[1], 1e-9);
    }
}
