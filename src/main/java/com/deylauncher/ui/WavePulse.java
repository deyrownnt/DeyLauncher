package com.deylauncher.ui;

import javafx.animation.AnimationTimer;
import javafx.scene.Node;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Drives a soft "wave"/breathing pulse on the online-status dots in the Friends list and
 * Friends-Playing-Now rows. JavaFX 21 has no Web-style CSS {@code @keyframes}, so the pulse is a
 * small code-driven engine:
 *
 * <ul>
 *   <li>Dots {@linkplain #register(Node) register} themselves when an online friend is rendered
 *       and {@linkplain #unregister(Node) unregister} on re-render.</li>
 *   <li>One shared {@link AnimationTimer} smoothly oscillates each live dot's opacity between
 *       ~0.45 and 1.0 on a sine curve. A per-dot phase offset makes a group of online dots ripple
 *       in a wave instead of blinking in perfect unison.</li>
 *   <li>It auto-starts the timer when the first online dot appears and auto-stops it when the last
 *       one disappears or the app closes -- so it costs nothing when nobody is online.</li>
 * </ul>
 *
 * Only friend status dots pulse (per product decision), NOT the account-button presence dot.
 */
public final class WavePulse {

    private static final double MIN_OPACITY = 0.45;
    private static final double MAX_OPACITY = 1.0;
    private static final double SPEED = 2.0;            // rad/s
    private static final double PHASE_STEP = 0.7;       // neighbour dots ripple slightly out of sync

    private static final WavePulse INSTANCE = new WavePulse();

    private final Set<Node> live = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ConcurrentHashMap<Node, Double> phase = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile AnimationTimer timer;

    private WavePulse() {}

    public static WavePulse instance() {
        return INSTANCE;
    }

    /** Starts animating {@code dot} as an online indicator. Must be called on the JavaFX thread. */
    public synchronized void register(Node dot) {
        live.add(dot);
        phase.put(dot, (live.size() - 1) * PHASE_STEP);
        ensureRunning();
    }

    /** Stops animating {@code dot} (e.g. it was re-rendered or became offline). FX thread. */
    public synchronized void unregister(Node dot) {
        live.remove(dot);
        phase.remove(dot);
        if (live.isEmpty()) stop();
    }

    /** Permanently stops the pulse (e.g. app closing). Safe to call repeatedly. */
    public synchronized void stop() {
        if (timer != null) {
            try {
                timer.stop();
            } catch (IllegalStateException ignored) {
                // Already stopped / toolkit shutting down -- nothing to do.
            }
            timer = null;
        }
        running.set(false);
    }

    private synchronized void ensureRunning() {
        if (running.get() || live.isEmpty()) return;
        running.set(true);
        AnimationTimer t = new AnimationTimer() {
            @Override
            public void handle(long nowNanos) {
                double tSec = nowNanos / 1_000_000_000.0;
                for (Node n : live) {
                    Double p = phase.get(n);
                    double ph = p == null ? 0.0 : p;
                    double o = MIN_OPACITY + (MAX_OPACITY - MIN_OPACITY)
                            * (0.5 + 0.5 * Math.sin(tSec * SPEED + ph));
                    n.setOpacity(o);
                }
            }
        };
        this.timer = t;
        t.start();
    }
}