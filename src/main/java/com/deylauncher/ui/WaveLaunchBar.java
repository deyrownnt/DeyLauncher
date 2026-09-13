package com.deylauncher.ui;

import javafx.animation.AnimationTimer;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.PathElement;
import javafx.scene.shape.Rectangle;

/**
 * The launcher's launch-progress indicator: just a compact orange "dilated sine" line that draws itself
 * left -&gt; right as the real 0..100% launch progress climbs, undulating gently. The percentage label
 * (0.00% -&gt; 100.00%) sits right at the advancing tip of the line, following its end as it grows.
 *
 * <p>No background fill/bar -- only the thin curve (per the spec), with the % text tracking the wave tip.
 * Built on a raw {@link Pane} with manual layout. A single {@link AnimationTimer} glides the wave and only
 * runs while {@link #start()} is active, so it costs nothing when idle; the tip label + reveal width move
 * with the animation and with real {@link #progressProperty()} changes.
 */
public class WaveLaunchBar extends Pane {

    private static final double LAMBDA = 120;   // horizontal wavelength (px) -> "dilated" wide sine
    private static final double SPEED = 40;     // how fast the crests glide (px/s)
    private static final double STEP = 5.0;     // x-spacing between samples when building the polyline
    private static final Color WAVE_COLOR = Color.web("#ff7a1f");

    private final DoubleProperty progress = new SimpleDoubleProperty(this, "progress", 0.0);

    private final Rectangle reveal = new Rectangle();     // left->right reveal clip for the line
    private final Path wave = new Path();
    private final Label pct = new Label("0.00%");

    private AnimationTimer animator;
    private double phaseX;      // px offset driving the sine glide (wraps every LAMBDA for a seamless loop)
    private long lastNanos = -1;
    private double lastW = -1;

    public WaveLaunchBar() {
        getStyleClass().add("launch-progress");
        setPrefHeight(22);
        setMinHeight(22);

        wave.setStroke(WAVE_COLOR);
        wave.setStrokeWidth(2.0);
        wave.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        wave.setFill(Color.TRANSPARENT);
        wave.setSmooth(true);
        wave.setClip(reveal);

        pct.setTextFill(WAVE_COLOR);
        pct.setEffect(new DropShadow(3, 1, 1, Color.rgb(0, 0, 0)));
        pct.getStyleClass().add("launch-pct");

        getChildren().addAll(wave, pct);
        progress.addListener((obs, ov, nv) -> layoutBar(true));
    }
/** The 0..1 progress property; bind the launch task's progress into it (same pattern as the update overlay). */
    public DoubleProperty progressProperty() {
        return progress;
    }

    /** Directly sets progress (0..1). Usually you bind the property instead and don't need this. */
    public void setProgress(double value) {
        progress.set(value < 0 ? 0 : (value > 1 ? 1 : value));
    }

    /** Starts the wave animation. Safe to call repeatedly. */
    public synchronized void start() {
        if (animator != null) return;
        AnimationTimer t = new AnimationTimer() {
            @Override
            public void handle(long nowNanos) {
                tick(nowNanos);
            }
        };
        animator = t;
        t.start();
    }

    /** Stops the wave animation. Safe to call repeatedly. */
    public synchronized void stop() {
        if (animator != null) {
            try {
                animator.stop();
            } catch (IllegalStateException ignored) {
            }
            animator = null;
        }
        lastNanos = -1;
    }

    /** Hides the bar once the game has actually opened (launch reached 100%). */
    public void finishLaunch() {
        stop();
        setVisible(false);
        setManaged(false);
    }

    private void tick(long nowNanos) {
        double w = getWidth();
        double h = getHeight();
        if (w != lastW) {
            lastW = w;
            layoutBar(true);
        }
        if (lastNanos < 0) lastNanos = nowNanos;
        double dt = (nowNanos - lastNanos) / 1_000_000_000.0;
        lastNanos = nowNanos;

        phaseX += SPEED * dt;
        if (phaseX >= LAMBDA) phaseX -= LAMBDA;

        double frac = clamp01(progress.get());
        double revealW = w * frac;
        reveal.setWidth(revealW);
        reveal.setHeight(h);
        wave.relocate(0, 0);
        rebuildWave(w, h);
        positionPct(w, h, frac);
    }

    private void layoutBar(boolean relabel) {
        double w = getWidth();
        double h = getHeight();
        if (w <= 0) w = prefWidth(-1);
        if (h <= 0) h = 22;

        double frac = clamp01(progress.get());
        double revealW = w * frac;
        reveal.setWidth(revealW);
        reveal.setHeight(h);
        wave.relocate(0, 0);

        if (relabel) {
            pct.setText(String.format("%.2f%%", frac * 100.0));
        }
        rebuildWave(w, h);
        positionPct(w, h, frac);
    }

    /** Places the % label just past the advancing tip of the curve so it visibly follows the end of the line. */
    private void positionPct(double w, double h, double frac) {
        double mid = h / 2.0;
        double amp = Math.min(3.5, h * 0.18);
        double tipX = w * frac;
        double tipY = waveY(tipX, mid, amp);

        double lw = pct.getWidth();
        if (lw < 0) lw = pct.prefWidth(-1);
        double lh = pct.getHeight();
        if (lh < 0) lh = pct.prefHeight(-1);

        double tx = tipX + 6;
        if (tx + lw > w) tx = Math.max(0, w - lw);  // keep on-screen when the line is near the right edge
        double ty = tipY - lh / 2.0;
        if (ty < 0) ty = 0;
        if (ty + lh > h) ty = h - lh;
        pct.relocate(tx, ty);
    }

    private void rebuildWave(double w, double h) {
        if (w <= 0 || h <= 0) return;
        double mid = h / 2.0;
        double amp = Math.min(3.5, h * 0.18);

        java.util.List<PathElement> el = wave.getElements();
        el.clear();
        el.add(new MoveTo(0, waveY(0, mid, amp)));
        for (double x = STEP; x <= w + STEP; x += STEP) {
            el.add(new LineTo(x, waveY(x, mid, amp)));
        }
    }

    private double waveY(double x, double mid, double amp) {
        return mid + amp * Math.sin(2.0 * Math.PI * (x + phaseX) / LAMBDA);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
    }