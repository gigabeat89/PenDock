package th.pendock;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Looping clean animation showing how to double-tap M-Pencil's side to open PenDock. */
final class PenDoubleTapDemoView extends View {
    private static final long LOOP_MS = 4600L;
    private static final int[] SLOT_COLORS = {
            0xff4f7cff, 0xff2ec4b6, 0xffff9f1c, 0xffe76f51, 0xff9b5de5, 0xff43aa8b
    };

    private final Appearance.Palette colors;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final Path auxPath = new Path();

    private ValueAnimator animator;
    private float progress;
    private boolean thai;

    PenDoubleTapDemoView(Context context, boolean startThai) {
        super(context);
        this.colors = Ui.colors(context);
        this.thai = startThai;
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
    }

    void setThai(boolean useThai) {
        if (thai == useThai) return;
        thai = useThai;
        invalidate();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = resolveSize(dp(340), widthMeasureSpec);
        int h = resolveSize(dp(160), heightMeasureSpec);
        setMeasuredDimension(w, h);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startLoop();
    }

    @Override protected void onDetachedFromWindow() {
        stopLoop();
        super.onDetachedFromWindow();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE && isAttachedToWindow()) startLoop();
        else stopLoop();
    }

    private void startLoop() {
        if (animator != null && animator.isRunning()) return;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(LOOP_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(anim -> {
            progress = (float) anim.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void stopLoop() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        float scale = Math.min(1f, Math.min(w / dp(330f), h / dp(156f)));
        float radius = dp(14);

        // Stage background with subtle gradient and border
        rect.set(dp(1), dp(1), w - dp(1), h - dp(1));
        int topBg = colors.dark ? 0xff1c212c : 0xfff3f6fc;
        int botBg = colors.dark ? 0xff141821 : 0xffe9eff9;
        fill.setAlpha(255);
        fill.setShader(new LinearGradient(0, 0, 0, h, topBg, botBg, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, radius, radius, fill);
        fill.setShader(null);
        stroke.setColor(colors.border);
        stroke.setStrokeWidth(dp(1));
        canvas.drawRoundRect(rect, radius, radius, stroke);

        float tipX = w * 0.30f;
        float tipY = h * 0.54f;

        // Subtle hover glow under the pen tip on the screen plane
        float hoverIn = clamp01(progress / 0.14f);
        float hoverOut = progress > 0.90f ? clamp01((1f - progress) / 0.10f) : 1f;
        float hoverAlpha = hoverIn * hoverOut;

        // Pen vertical approach & gentle breathing float (held ~1.4s so caption 1 is easy to read)
        float approach = 1f - easeOut(clamp01(progress / 0.14f));
        float bob = (float) Math.sin(progress * Math.PI * 4.0) * dp(1.2f) * scale;
        float tap1 = softPulse(progress, 0.35f, 0.42f);
        float tap2 = softPulse(progress, 0.435f, 0.505f);
        float tapNudge = (tap1 + tap2) * dp(0.45f) * scale;

        float nibX = tipX + dp(2.5f) * scale;
        float nibY = tipY - dp(1.5f) * scale - approach * dp(8f) * scale + bob + tapNudge;

        // Ring reveal & dismiss animation right after gentle 2nd tap
        float ringEnter = clamp01((progress - 0.50f) / 0.13f);
        float ringExit = progress > 0.87f ? clamp01((0.97f - progress) / 0.10f) : 1f;
        float ringAlpha = easeOut(ringEnter) * ringExit;
        float ringScale = ringEnter <= 0f ? 0f
                : (0.28f + 0.72f * overshoot(ringEnter)) * (0.78f + 0.22f * ringExit);

        // Hover target dot before ring opens
        if (hoverAlpha > 0.01f) {
            float dotAlpha = hoverAlpha * (1f - ringAlpha * 0.78f);
            fill.setAlpha(255);
            fill.setShader(new RadialGradient(tipX, tipY, dp(16f) * scale,
                    withAlpha(colors.accent, (int) (70 * dotAlpha)),
                    withAlpha(colors.accent, 0), Shader.TileMode.CLAMP));
            canvas.drawCircle(tipX, tipY, dp(16f) * scale, fill);
            fill.setShader(null);
            fill.setColor(withAlpha(colors.accent, (int) (165 * dotAlpha)));
            canvas.drawCircle(tipX, tipY, dp(3.0f) * scale, fill);
        }

        // Miniature PenDock shortcut ring blooming around the pen tip
        if (ringAlpha > 0.01f && ringScale > 0.05f) {
            drawMiniRing(canvas, tipX, tipY, scale, ringScale, ringAlpha);
        }

        // Clean angled M-Pencil stylus with crisp defined tip cone & graphite nib
        float angleDeg = -18f;
        double rad = Math.toRadians(angleDeg);
        float ux = (float) Math.cos(rad);
        float uy = (float) Math.sin(rad);
        float touchDist = dp(76f) * scale;
        float touchX = nibX + ux * touchDist;
        float touchY = nibY + uy * touchDist;

        drawStylus(canvas, nibX, nibY, angleDeg, scale, Math.max(tap1, tap2));

        // Soft expanding tap ripples on the stylus side
        drawTapRipple(canvas, touchX, touchY, progress, 0.38f, 0.51f, scale);
        drawTapRipple(canvas, touchX, touchY, progress, 0.465f, 0.61f, scale);

        // Minimal stylized fingertip tapping down twice rapidly on the side of the stylus
        drawFingerTap(canvas, touchX, touchY, progress, scale);

        drawCaptionPill(canvas, w, h, scale);
    }

    private void drawMiniRing(Canvas canvas, float cx, float cy, float scale, float ringScale, float alpha) {
        canvas.save();
        canvas.translate(cx, cy);
        canvas.scale(ringScale, ringScale);

        float outerR = dp(48f) * scale;
        float innerR = dp(18f) * scale;
        float slotR = (outerR + innerR) * 0.5f;

        fill.setColor(withAlpha(0xff000000, (int) ((colors.dark ? 70 : 26) * alpha)));
        canvas.drawCircle(0, dp(2f) * scale, outerR + dp(2f) * scale, fill);

        float midR = (outerR + innerR) * 0.5f;
        stroke.setStrokeWidth(outerR - innerR);
        stroke.setColor(withAlpha(colors.dark ? 0xff2b3344 : 0xffffffff, (int) (228 * alpha)));
        canvas.drawCircle(0, 0, midR, stroke);

        float hoverSlot = pulse(progress, 0.66f, 0.84f);
        if (hoverSlot > 0.01f) {
            rect.set(-midR, -midR, midR, midR);
            stroke.setStrokeWidth(outerR - innerR - dp(4f) * scale);
            stroke.setColor(withAlpha(colors.accent, (int) (85 * hoverSlot * alpha)));
            canvas.drawArc(rect, -120f, 60f, false, stroke);
        }

        stroke.setStrokeWidth(dp(1.15f) * scale);
        stroke.setColor(withAlpha(colors.dark ? 0xffffffff : colors.accent, (int) ((colors.dark ? 65 : 75) * alpha)));
        canvas.drawCircle(0, 0, outerR, stroke);
        canvas.drawCircle(0, 0, innerR, stroke);

        fill.setColor(withAlpha(colors.dark ? 0xff1e2430 : 0xfff6f9ff, (int) (238 * alpha)));
        canvas.drawCircle(0, 0, innerR - dp(2f) * scale, fill);

        float chipR = dp(6.2f) * scale;
        for (int i = 0; i < SLOT_COLORS.length; i++) {
            double a = Math.toRadians(-90 + i * 60);
            float ix = (float) Math.cos(a) * slotR;
            float iy = (float) Math.sin(a) * slotR;
            float boost = (i == 0 ? 1f + 0.16f * hoverSlot : 1f);
            float r = chipR * boost;
            fill.setColor(withAlpha(SLOT_COLORS[i], (int) (245 * alpha)));
            rect.set(ix - r, iy - r, ix + r, iy + r);
            canvas.drawRoundRect(rect, dp(3.5f) * scale, dp(3.5f) * scale, fill);
            fill.setColor(withAlpha(0xffffffff, (int) (215 * alpha)));
            canvas.drawCircle(ix, iy, r * 0.34f, fill);
        }

        canvas.restore();
    }

    private void drawStylus(Canvas canvas, float nibX, float nibY, float angleDeg, float scale, float tapGlow) {
        canvas.save();
        canvas.translate(nibX, nibY);
        canvas.rotate(angleDeg);

        float penLen = dp(150f) * scale;
        float halfThick = dp(5.8f) * scale;
        float coneLen = dp(24f) * scale;
        float nibLen = dp(8.5f) * scale;
        int penRim = colors.dark ? 0xaab8c7de : 0x994c5b73;

        // Soft diffused drop shadow underneath the stylus
        fill.setColor(withAlpha(0xff000000, colors.dark ? 70 : 26));
        rect.set(coneLen * 0.45f, halfThick + dp(1.5f) * scale, penLen - dp(2f) * scale,
                halfThick + dp(5f) * scale);
        canvas.drawRoundRect(rect, dp(3f) * scale, dp(3f) * scale, fill);

        // Continuous full silhouette path from precision tip -> tapered cone -> barrel -> tail cap
        path.reset();
        path.moveTo(dp(1.1f) * scale, -dp(0.85f) * scale);
        path.lineTo(coneLen, -halfThick);
        path.lineTo(penLen - halfThick, -halfThick);
        path.arcTo(new RectF(penLen - halfThick * 2f, -halfThick, penLen, halfThick), -90f, 180f, false);
        path.lineTo(coneLen, halfThick);
        path.lineTo(dp(1.1f) * scale, dp(0.85f) * scale);
        path.arcTo(new RectF(0f, -dp(0.85f) * scale, dp(2.2f) * scale, dp(0.85f) * scale), 90f, 180f, false);
        path.close();

        // Crisp 3D pearlescent ceramic-white barrel shading (100% opaque over the ring)
        fill.setAlpha(255);
        fill.setShader(new LinearGradient(0, -halfThick, 0, halfThick,
                new int[] {0xffdce3ee, 0xffffffff, 0xfff5f8fc, 0xffc6d0df, 0xffd7dfec},
                new float[] {0f, 0.24f, 0.54f, 0.86f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawPath(path, fill);
        fill.setShader(null);

        // Distinct satin-silver tapered cone so the pen tip never looks faint
        auxPath.reset();
        auxPath.moveTo(nibLen, -halfThick * (nibLen / coneLen));
        auxPath.lineTo(coneLen, -halfThick);
        auxPath.lineTo(coneLen, halfThick);
        auxPath.lineTo(nibLen, halfThick * (nibLen / coneLen));
        auxPath.close();
        fill.setAlpha(255);
        fill.setShader(new LinearGradient(0, -halfThick, 0, halfThick,
                new int[] {0xffd6deea, 0xfff1f5fb, 0xffb8c4d6},
                new float[] {0f, 0.35f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawPath(auxPath, fill);
        fill.setShader(null);

        // Crisp dark-graphite precision nib tip
        auxPath.reset();
        auxPath.moveTo(dp(1.1f) * scale, -dp(0.85f) * scale);
        auxPath.lineTo(nibLen, -halfThick * (nibLen / coneLen));
        auxPath.lineTo(nibLen, halfThick * (nibLen / coneLen));
        auxPath.lineTo(dp(1.1f) * scale, dp(0.85f) * scale);
        auxPath.close();
        fill.setColor(0xff202836);
        canvas.drawPath(auxPath, fill);

        // Touch-sensitive side grip zone highlight (glows brightly on each tap)
        float zoneStart = dp(58f) * scale;
        float zoneEnd = dp(92f) * scale;
        fill.setColor(withAlpha(colors.accent, (int) (45 + 185 * tapGlow)));
        rect.set(zoneStart, -halfThick * 0.78f, zoneEnd, halfThick * 0.22f);
        canvas.drawRoundRect(rect, dp(3f) * scale, dp(3f) * scale, fill);

        // Collar seam line between cone & barrel + continuous outer contour around entire stylus
        stroke.setColor(penRim);
        stroke.setStrokeWidth(dp(1.05f) * scale);
        canvas.drawLine(coneLen, -halfThick, coneLen, halfThick, stroke);
        stroke.setStrokeWidth(dp(1.2f) * scale);
        canvas.drawPath(path, stroke);

        canvas.restore();
    }

    private void drawTapRipple(Canvas canvas, float cx, float cy, float p, float start, float end, float scale) {
        if (p < start || p > end) return;
        float t = (p - start) / (end - start);
        float eased = easeOut(t);
        float r = (dp(4f) + dp(20f) * eased) * scale;
        int alpha = (int) (160 * (1f - t) * (1f - t * 0.3f));
        stroke.setStrokeWidth(dp(1.6f) * scale * (1f - 0.45f * t));
        stroke.setColor(withAlpha(colors.accent, alpha));
        canvas.drawCircle(cx, cy, r, stroke);

        if (t < 0.40f) {
            float dotT = 1f - t / 0.40f;
            fill.setColor(withAlpha(colors.accent, (int) (125 * dotT * dotT)));
            canvas.drawCircle(cx, cy, dp(4.5f) * scale * dotT, fill);
        }
    }

    private void drawFingerTap(Canvas canvas, float touchX, float touchY, float p, float scale) {
        float showIn = clamp01((p - 0.28f) / 0.06f);
        float showOut = p > 0.51f ? clamp01((0.57f - p) / 0.06f) : 1f;
        float alpha = easeOut(showIn) * showOut;
        if (alpha <= 0.01f) return;

        float press1 = softPulse(p, 0.35f, 0.42f);
        float press2 = softPulse(p, 0.435f, 0.505f);
        float press = Math.max(press1, press2);

        float lift = (1f - press) * dp(6.5f) * scale;
        float fx = touchX - dp(4f) * scale;
        float fy = touchY - dp(15.5f) * scale - lift;

        canvas.save();
        canvas.translate(fx, fy);
        canvas.rotate(-14f);

        float fw = dp(13f) * scale;
        float fh = dp(24f) * scale;
        rect.set(-fw * 0.5f, -fh * 0.5f, fw * 0.5f, fh * 0.5f);
        fill.setColor(withAlpha(colors.dark ? 0xffdce4f2 : 0xff3b475c, (int) (215 * alpha)));
        canvas.drawRoundRect(rect, fw * 0.5f, fw * 0.5f, fill);

        fill.setColor(withAlpha(press > 0.4f ? colors.accent : 0xffffffff, (int) (200 * alpha)));
        canvas.drawCircle(0f, fh * 0.5f - fw * 0.42f, fw * 0.28f, fill);

        canvas.restore();
    }

    private void drawCaptionPill(Canvas canvas, float w, float h, float scale) {
        boolean secondPhase = progress >= 0.30f;
        String caption = secondPhase
                ? (thai ? "แตะด้านข้างปากกา 2 ครั้ง" : "Double-tap pen side")
                : (thai ? "จ่อปากกาใกล้จอ" : "Hold pen near screen");
        boolean highlight = secondPhase;
        textPaint.setTextSize(dp(11.5f) * scale);
        float padX = dp(10f) * scale;
        float textW = textPaint.measureText(caption);
        float pillW = textW + padX * 2f;
        float pillH = dp(24f) * scale;
        float right = w - dp(10f) * scale;
        float bottom = h - dp(10f) * scale;
        rect.set(right - pillW, bottom - pillH, right, bottom);

        fill.setColor(highlight ? colors.accentWash : colors.surface);
        canvas.drawRoundRect(rect, pillH * 0.5f, pillH * 0.5f, fill);
        stroke.setStrokeWidth(dp(1f));
        stroke.setColor(highlight ? withAlpha(colors.accent, 110) : colors.border);
        canvas.drawRoundRect(rect, pillH * 0.5f, pillH * 0.5f, stroke);

        textPaint.setColor(highlight ? colors.accent : colors.muted);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = rect.centerY() - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(caption, rect.centerX(), textY, textPaint);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float easeOut(float t) {
        float inv = 1f - clamp01(t);
        return 1f - inv * inv * inv;
    }

    private static float overshoot(float t) {
        float x = clamp01(t) - 1f;
        float s = 1.45f;
        return 1f + (s + 1f) * x * x * x + s * x * x;
    }

    private static float pulse(float p, float start, float end) {
        if (p < start || p > end) return 0f;
        float t = (p - start) / (end - start);
        return (float) Math.sin(t * Math.PI);
    }

    /** Cosine ease-in-out bell curve for a soft, gentle double-tap motion without harsh stops. */
    private static float softPulse(float p, float start, float end) {
        if (p < start || p > end) return 0f;
        float t = (p - start) / (end - start);
        return 0.5f - 0.5f * (float) Math.cos(t * Math.PI * 2.0);
    }

    private static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00ffffff);
    }

    private int dp(float value) {
        return Ui.dp(getContext(), value);
    }
}
