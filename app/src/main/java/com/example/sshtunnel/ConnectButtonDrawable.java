package com.example.sshtunnel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

/**
 * Amnezia-inspired connection ring. The progress arc is drawn independently
 * from the Button content, so its label remains stationary while connecting.
 */
final class ConnectButtonDrawable extends Drawable implements Animatable, Runnable {
    private static final long FRAME_MS = 16L;
    private static final float DEGREES_PER_MS = 0.36f;

    private static final int MIDNIGHT = 0xFF0E0E11;
    private static final int ONYX = 0xFF1C1D21;
    private static final int PALE = 0xFFD7D8DB;
    private static final int MUTED = 0xFF878B91;
    private static final int ACCENT = 0xFFFBB26A;
    private static final int DARK_PROGRESS = 0xFF261E1A;
    private static final int DEEP_BROWN = 0xFF402102;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ring = new RectF();
    private final float density;
    private boolean connecting;
    private boolean animating;
    private float angle = 245f;
    private long lastFrame;
    private int alpha = 255;
    private ColorFilter colorFilter;

    ConnectButtonDrawable(Context context) {
        density = context.getResources().getDisplayMetrics().density;
    }

    void setConnecting(boolean value) {
        if (connecting == value) {
            if (value && !animating) start();
            return;
        }
        connecting = value;
        if (value) start();
        else stop();
        invalidateSelf();
    }

    @Override public void draw(Canvas canvas) {
        float cx = getBounds().exactCenterX();
        float cy = getBounds().exactCenterY();
        float radius = Math.min(getBounds().width(), getBounds().height()) / 2f;
        boolean pressed = hasState(android.R.attr.state_pressed);
        boolean activated = hasState(android.R.attr.state_activated);

        configure(Paint.Style.FILL, pressed && activated ? DEEP_BROWN
                : pressed ? ONYX : MIDNIGHT, 0f, 1f);
        canvas.drawCircle(cx, cy, Math.max(0f, radius - dp(1)), paint);

        float normalWidth = activated ? dp(4) : dp(3);
        float inset = Math.max(dp(6), normalWidth / 2f + dp(2));
        ring.set(getBounds().left + inset, getBounds().top + inset,
                getBounds().right - inset, getBounds().bottom - inset);

        if (connecting) {
            configure(Paint.Style.STROKE, DARK_PROGRESS, dp(3), 1f);
            canvas.drawOval(ring, paint);
            configure(Paint.Style.STROKE, PALE, dp(3), 1f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawArc(ring, angle, 180f, false, paint);
            return;
        }

        if (activated) {
            configure(Paint.Style.STROKE, ACCENT, dp(10), pressed ? 0.12f : 0.20f);
            canvas.drawOval(ring, paint);
        }
        configure(Paint.Style.STROKE,
                activated ? ACCENT : pressed ? MUTED : PALE,
                normalWidth, 1f);
        canvas.drawOval(ring, paint);
    }

    private void configure(
            Paint.Style style, int color, float strokeWidth, float alphaFactor) {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(style);
        paint.setColor(color);
        paint.setStrokeWidth(strokeWidth);
        paint.setColorFilter(colorFilter);
        paint.setAlpha(Math.round(alpha * alphaFactor));
    }

    private boolean hasState(int expected) {
        for (int state : getState()) {
            if (state == expected) return true;
        }
        return false;
    }

    private float dp(float value) {
        return value * density;
    }

    @Override public boolean isStateful() {
        return true;
    }

    @Override protected boolean onStateChange(int[] state) {
        invalidateSelf();
        return true;
    }

    @Override public void start() {
        if (animating || !connecting) return;
        animating = true;
        lastFrame = SystemClock.uptimeMillis();
        scheduleSelf(this, lastFrame + FRAME_MS);
    }

    @Override public void stop() {
        animating = false;
        unscheduleSelf(this);
        angle = 245f;
    }

    @Override public boolean isRunning() {
        return animating;
    }

    @Override public void run() {
        if (!animating || !connecting) return;
        long now = SystemClock.uptimeMillis();
        angle = (angle + (now - lastFrame) * DEGREES_PER_MS) % 360f;
        lastFrame = now;
        invalidateSelf();
        scheduleSelf(this, now + FRAME_MS);
    }

    @Override public void setAlpha(int value) {
        alpha = value;
        invalidateSelf();
    }

    @Override public void setColorFilter(ColorFilter value) {
        colorFilter = value;
        invalidateSelf();
    }

    @SuppressWarnings("deprecation")
    @Override public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
