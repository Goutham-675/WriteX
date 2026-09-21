package com.example.handwritingrater;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import androidx.annotation.Nullable;
import com.google.android.material.color.MaterialColors;

/** Circular score ring with an animated progress arc. */
public class ScoreRingView extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private float progress = 0f;
    private ValueAnimator animator;

    public ScoreRingView(Context context) {
        this(context, null);
    }

    public ScoreRingView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ScoreRingView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeCap(Paint.Cap.ROUND);
        int trackColor = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorSurfaceContainerHighest, 0xFF262C50);
        int arcColor = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorPrimary, 0xFFB7A6FF);
        track.setColor(trackColor);
        arc.setColor(arcColor);
        glow.setColor(arcColor);
        glow.setAlpha(60);
    }

    public void setProgress(float p, boolean animate) {
        float target = Math.max(0f, Math.min(1f, p));
        if (animator != null) animator.cancel();
        if (!animate || progress == target) {
            progress = target;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(900);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            progress = (Float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        float pad = 8f * getResources().getDisplayMetrics().density;
        bounds.set(pad, pad, w - pad, h - pad);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float stroke = 14f * getResources().getDisplayMetrics().density;
        track.setStrokeWidth(stroke);
        arc.setStrokeWidth(stroke);
        canvas.drawOval(bounds, track);
        if (progress > 0) {
            float sweep = 360f * Math.max(0.02f, progress);
            glow.setStrokeWidth(stroke * 1.9f);
            canvas.drawArc(bounds, -90, sweep, false, glow);
            canvas.drawArc(bounds, -90, sweep, false, arc);
        }
    }
}