package th.pendock;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Draws one gentle heartbeat glow pulse with a soft background spotlight dim around a floating window. */
final class FloatingPulse extends View {
    private static final long DURATION_MS = 860L;
    private static final int GLOW_COLOR = 0xffffffff;
    private static final int CORE_COLOR = 0xffffffff;
    private static final int MAX_DIM_ALPHA = 76; // ~30% gentle spotlight dim outside the floating window

    private final RectF base = new RectF();
    private final RectF drawRect = new RectF();
    private final Path cutout = new Path();
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float cornerRadius;
    private final float strokeUnit;
    private final float maxExpand;
    private final Runnable onFinished;
    private ValueAnimator animator;
    private float progress;

    FloatingPulse(Context context, Rect target, float cornerRadiusPx, Runnable onFinished) {
        super(context);
        this.base.set(target);
        this.onFinished = onFinished;
        this.cornerRadius = Math.max(Ui.dp(context, 10), cornerRadiusPx);
        this.strokeUnit = Ui.dp(context, 1);
        this.maxExpand = Ui.dp(context, 10);
        cutout.addRoundRect(this.base, this.cornerRadius, this.cornerRadius, Path.Direction.CW);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(GLOW_COLOR);
        strokePaint.setStyle(Paint.Style.STROKE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void start() {
        stop();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(DURATION_MS);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(anim -> {
            progress = (float) anim.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override public void onAnimationEnd(Animator animation) {
                if (!cancelled && onFinished != null) onFinished.run();
            }
        });
        animator.start();
    }

    void stop() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        if (base.isEmpty()) return;
        float t = progress;
        float dim = spotlightEnvelope(t);
        if (dim > 0.002f) {
            int dimAlpha = Math.round(MAX_DIM_ALPHA * dim);
            if (dimAlpha > 0) {
                canvas.save();
                canvas.clipOutPath(cutout);
                canvas.drawColor(Color.argb(dimAlpha, 0, 0, 0));
                canvas.restore();
            }
        }

        float beat = heartbeatEnvelope(t);
        if (beat <= 0.002f) return;

        // Barely-there inner breath of light so it feels calm and unobtrusive.
        fillPaint.setAlpha(Math.round(10f * beat));
        canvas.drawRoundRect(base, cornerRadius, cornerRadius, fillPaint);

        // Soft layered GPU strokes for a delicate bloom without software blur overhead.
        float breathe = strokeUnit * (1.2f * beat);
        drawGlowBorder(canvas, base, cornerRadius, beat, breathe);

        // Gentle expanding heartbeat ripples (lub at t=0.05..0.52, dub at t=0.38..1.0).
        drawRipple(canvas, t, 0.05f, 0.52f, 0.55f, 0.72f);
        drawRipple(canvas, t, 0.38f, 1.00f, 0.38f, 1.00f);
    }

    private void drawGlowBorder(Canvas canvas, RectF rect, float radius, float intensity, float breathe) {
        drawRect.set(rect);
        drawRect.inset(-breathe, -breathe);
        float r = radius + breathe;

        strokePaint.setColor(GLOW_COLOR);
        strokePaint.setStrokeWidth(strokeUnit * 9f);
        strokePaint.setAlpha(Math.round(14f * intensity));
        canvas.drawRoundRect(drawRect, r, r, strokePaint);

        strokePaint.setStrokeWidth(strokeUnit * 4.5f);
        strokePaint.setAlpha(Math.round(32f * intensity));
        canvas.drawRoundRect(drawRect, r, r, strokePaint);

        strokePaint.setStrokeWidth(strokeUnit * 2.0f);
        strokePaint.setAlpha(Math.round(78f * intensity));
        canvas.drawRoundRect(drawRect, r, r, strokePaint);

        strokePaint.setColor(CORE_COLOR);
        strokePaint.setStrokeWidth(strokeUnit * 1.0f);
        strokePaint.setAlpha(Math.round(95f * intensity));
        canvas.drawRoundRect(drawRect, r, r, strokePaint);
    }

    private void drawRipple(Canvas canvas, float t, float startT, float endT, float peakAlpha, float reach) {
        if (t <= startT || t >= endT) return;
        float u = (t - startT) / (endT - startT);
        float easeOut = 1f - (1f - u) * (1f - u);
        float expand = maxExpand * reach * easeOut;
        float fade = (float) Math.sin(Math.min(1f, u * 3.2f) * (Math.PI * 0.5)) * (1f - easeOut);
        if (fade <= 0.01f) return;

        drawRect.set(base);
        drawRect.inset(-expand, -expand);
        float r = cornerRadius + expand;

        strokePaint.setColor(GLOW_COLOR);
        strokePaint.setStrokeWidth(strokeUnit * (2.0f - 1.2f * easeOut));
        strokePaint.setAlpha(Math.round(78f * peakAlpha * fade));
        canvas.drawRoundRect(drawRect, r, r, strokePaint);
    }

    /** Smooth spotlight dim envelope: rises softly, holds calm across the heartbeat, then fades out. */
    private static float spotlightEnvelope(float t) {
        if (t < 0.16f) {
            return smoothstep(t / 0.16f);
        }
        if (t < 0.54f) {
            return 1.0f;
        }
        float u = (t - 0.54f) / 0.46f;
        float decay = 1f - smoothstep(u);
        return decay * decay;
    }

    /** Single gentle lub-dub heartbeat cycle: soft beat 1, warm dip, softer beat 2, long fade. */
    private static float heartbeatEnvelope(float t) {
        if (t < 0.14f) {
            return smoothstep(t / 0.14f);
        }
        if (t < 0.36f) {
            float u = (t - 0.14f) / 0.22f;
            return 1.0f - 0.82f * smoothstep(u);
        }
        if (t < 0.50f) {
            float u = (t - 0.36f) / 0.14f;
            return 0.18f + 0.44f * smoothstep(u);
        }
        float u = (t - 0.50f) / 0.50f;
        float decay = 1f - smoothstep(u);
        return 0.62f * decay * decay;
    }

    private static float smoothstep(float x) {
        float c = Math.max(0f, Math.min(1f, x));
        return c * c * (3f - 2f * c);
    }
}
