package com.example.sshtunnel;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Rotating connection ring that leaves the button label stationary. */
public final class ConnectionRingView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private ValueAnimator animator;
    private float phase;

    public ConnectionRingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(dp(2));
        track.setColor(0x665D606A);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setStrokeWidth(dp(4.5f));
        arc.setColor(0xFFFFAA5B);
    }

    public void setConnecting(boolean connecting) {
        if (connecting) {
            if (animator == null) {
                animator = ValueAnimator.ofFloat(0f, 1f);
                animator.setDuration(1050);
                animator.setRepeatCount(ValueAnimator.INFINITE);
                animator.setInterpolator(new LinearInterpolator());
                animator.addUpdateListener(value -> {
                    phase = (float) value.getAnimatedValue();
                    invalidate();
                });
            }
            if (!animator.isStarted()) animator.start();
            setVisibility(VISIBLE);
        } else {
            if (animator != null) animator.cancel();
            phase = 0f;
            setVisibility(INVISIBLE);
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float radius = Math.min(getWidth(), getHeight()) / 2f - dp(5);
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        bounds.set(centerX - radius, centerY - radius,
                centerX + radius, centerY + radius);
        canvas.drawCircle(centerX, centerY, radius, track);
        arc.setAlpha(255);
        arc.setShadowLayer(dp(5), 0, 0, 0x99FF9B4A);
        float start = phase * 360f - 90f;
        canvas.drawArc(bounds, start, 105f, false, arc);
    }

    @Override protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
