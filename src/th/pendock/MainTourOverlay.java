package th.pendock;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/** Interactive GUI spotlight walkthrough that highlights real UI controls step by step. */
final class MainTourOverlay extends FrameLayout {
    static final class Step {
        final View target;
        final String titleTh;
        final String bodyTh;
        final String titleEn;
        final String bodyEn;
        final boolean showDoubleTapDemo;

        Step(View target, String titleTh, String bodyTh, String titleEn, String bodyEn) {
            this(target, titleTh, bodyTh, titleEn, bodyEn, false);
        }

        Step(View target, String titleTh, String bodyTh, String titleEn, String bodyEn,
                boolean showDoubleTapDemo) {
            this.target = target;
            this.titleTh = titleTh;
            this.bodyTh = bodyTh;
            this.titleEn = titleEn;
            this.bodyEn = bodyEn;
            this.showDoubleTapDemo = showDoubleTapDemo;
        }
    }

    private static final PathInterpolator EASE = new PathInterpolator(0.22f, 1f, 0.36f, 1f);
    private final Appearance.Palette colors;
    private final ScrollView scroll;
    private final List<Step> steps;
    private final Runnable onFinish;
    private final Runnable onOpenFullGuide;

    private final SpotlightCanvas backdrop;
    private final LinearLayout card;
    private final TextView stepCounter;
    private final TextView thPill;
    private final TextView enPill;
    private final TextView skipButton;
    private final TextView titleView;
    private final PenDoubleTapDemoView doubleTapDemo;
    private final TextView bodyView;
    private final LinearLayout dotsRow;
    private final TextView fullGuideButton;
    private final TextView backButton;
    private final TextView nextButton;

    private final int[] myLoc = new int[2];
    private final int[] targetLoc = new int[2];
    private final RectF currentHole = new RectF();
    private final RectF targetHole = new RectF();
    private ValueAnimator morph;
    private boolean thai;
    private int index;
    private boolean closed;

    MainTourOverlay(Context context, ScrollView scroll, List<Step> steps, boolean startThai,
            int startIndex, Runnable onOpenFullGuide, Runnable onFinish) {
        super(context);
        this.colors = Ui.colors(context);
        this.scroll = scroll;
        this.steps = steps;
        this.thai = startThai;
        this.index = Math.max(0, Math.min(steps.size() - 1, startIndex));
        this.onOpenFullGuide = onOpenFullGuide;
        this.onFinish = onFinish;
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);

        backdrop = new SpotlightCanvas(context);
        backdrop.setOnClickListener(view -> advance());
        addView(backdrop, new LayoutParams(-1, -1));

        card = Ui.column(context);
        int pad = dp(18);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(Ui.border(context, colors.surface, 20, colors.border));
        card.setElevation(dp(14));
        card.setClickable(true);

        // Top bar: step counter + Thai/EN switcher + Skip
        LinearLayout topBar = new LinearLayout(context);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        stepCounter = Ui.text(context, "", 12, colors.accent, true);
        stepCounter.setPadding(dp(10), dp(4), dp(10), dp(4));
        stepCounter.setBackground(Ui.shape(context, colors.accentWash, 10));
        topBar.addView(stepCounter, new LinearLayout.LayoutParams(-2, -2));

        View spacer = new View(context);
        topBar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));

        LinearLayout langBox = new LinearLayout(context);
        langBox.setGravity(Gravity.CENTER_VERTICAL);
        langBox.setPadding(dp(2), dp(2), dp(2), dp(2));
        langBox.setBackground(Ui.border(context, colors.rail, 10, colors.border));
        thPill = langButton("ไทย", () -> setLanguage(true));
        enPill = langButton("EN", () -> setLanguage(false));
        langBox.addView(thPill, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams enP = new LinearLayout.LayoutParams(-2, -2);
        enP.setMarginStart(dp(2));
        langBox.addView(enPill, enP);
        topBar.addView(langBox, new LinearLayout.LayoutParams(-2, -2));

        skipButton = Ui.text(context, "", 13, colors.muted, true);
        skipButton.setPadding(dp(12), dp(8), dp(4), dp(8));
        skipButton.setFocusable(true);
        skipButton.setOnClickListener(view -> dismiss());
        topBar.addView(skipButton, new LinearLayout.LayoutParams(-2, -2));
        card.addView(topBar, new LinearLayout.LayoutParams(-1, -2));

        titleView = Ui.text(context, "", 18, colors.text, true);
        LinearLayout.LayoutParams titleP = new LinearLayout.LayoutParams(-1, -2);
        titleP.topMargin = dp(12);
        card.addView(titleView, titleP);

        doubleTapDemo = new PenDoubleTapDemoView(context, startThai);
        doubleTapDemo.setVisibility(GONE);
        LinearLayout.LayoutParams demoP = new LinearLayout.LayoutParams(-1, dp(160));
        demoP.topMargin = dp(10);
        card.addView(doubleTapDemo, demoP);

        bodyView = Ui.text(context, "", 14, colors.muted, false);
        bodyView.setLineSpacing(dp(2), 1.0f);
        LinearLayout.LayoutParams bodyP = new LinearLayout.LayoutParams(-1, -2);
        bodyP.topMargin = dp(6);
        card.addView(bodyView, bodyP);

        dotsRow = new LinearLayout(context);
        dotsRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams dotsP = new LinearLayout.LayoutParams(-1, -2);
        dotsP.topMargin = dp(14);
        card.addView(dotsRow, dotsP);

        LinearLayout actions = new LinearLayout(context);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsP = new LinearLayout.LayoutParams(-1, -2);
        actionsP.topMargin = dp(14);

        fullGuideButton = Ui.text(context, "", 13, colors.accent, true);
        fullGuideButton.setPadding(dp(4), dp(8), dp(8), dp(8));
        fullGuideButton.setFocusable(true);
        fullGuideButton.setOnClickListener(view -> {
            dismiss();
            if (onOpenFullGuide != null) onOpenFullGuide.run();
        });
        actions.addView(fullGuideButton, new LinearLayout.LayoutParams(0, -2, 1));

        backButton = Ui.action(context, "", false, this::retreat);
        actions.addView(backButton, new LinearLayout.LayoutParams(-2, -2));

        nextButton = Ui.action(context, "", true, this::advance);
        LinearLayout.LayoutParams nextP = new LinearLayout.LayoutParams(-2, -2);
        nextP.setMarginStart(dp(8));
        actions.addView(nextButton, nextP);
        card.addView(actions, actionsP);

        int cardW = Math.min(dp(420), getResources().getDisplayMetrics().widthPixels - dp(32));
        LayoutParams cardParams = new LayoutParams(cardW, -2, Gravity.TOP | Gravity.START);
        addView(card, cardParams);

        setAlpha(0f);
        post(() -> {
            if (closed) return;
            showStep(index, false);
            animate().alpha(1f).setDuration(200).setInterpolator(EASE).start();
        });
    }

    int currentStep() {
        return index;
    }

    boolean isThai() {
        return thai;
    }

    private TextView langButton(String label, Runnable onClick) {
        TextView btn = Ui.text(getContext(), label, 12, colors.muted, true);
        btn.setGravity(Gravity.CENTER);
        btn.setMinWidth(dp(38));
        btn.setMinHeight(dp(28));
        btn.setPadding(dp(8), dp(4), dp(8), dp(4));
        btn.setFocusable(true);
        btn.setOnClickListener(view -> onClick.run());
        return btn;
    }

    private void setLanguage(boolean useThai) {
        if (thai == useThai) return;
        thai = useThai;
        renderStepText();
        post(() -> positionCard(true));
    }

    private void advance() {
        if (index + 1 >= steps.size()) {
            dismiss();
            return;
        }
        showStep(index + 1, true);
    }

    private void retreat() {
        if (index <= 0) return;
        showStep(index - 1, true);
    }

    void dismiss() {
        if (closed) return;
        closed = true;
        if (morph != null) morph.cancel();
        animate().alpha(0f).setDuration(150).withEndAction(() -> {
            if (onFinish != null) onFinish.run();
        }).start();
    }

    private void showStep(int newIndex, boolean animate) {
        if (steps.isEmpty()) {
            dismiss();
            return;
        }
        index = Math.max(0, Math.min(steps.size() - 1, newIndex));
        final int expectedIndex = index;
        renderStepText();
        Step step = steps.get(index);
        boolean scrolled = scrollToTarget(step.target);
        long delay = scrolled ? 280 : (animate ? 40 : 20);
        postDelayed(() -> {
            if (closed || index != expectedIndex) return;
            measureTargetHole(step.target, targetHole);
            if (!animate || currentHole.isEmpty()) {
                currentHole.set(targetHole);
                backdrop.invalidate();
                positionCard(false);
            } else {
                animateHoleTo(targetHole);
            }
            if (scrolled) {
                // Re-sync once more after long smooth scrolls finish settling.
                postDelayed(() -> {
                    if (closed || index != expectedIndex) return;
                    RectF settled = new RectF();
                    measureTargetHole(step.target, settled);
                    if (Math.abs(settled.top - targetHole.top) > dp(2)
                            || Math.abs(settled.bottom - targetHole.bottom) > dp(2)) {
                        targetHole.set(settled);
                        animateHoleTo(targetHole);
                    }
                }, 220);
            }
        }, delay);
    }

    private void renderStepText() {
        Step step = steps.get(index);
        stepCounter.setText((index + 1) + " / " + steps.size());
        applyLangPill(thPill, thai);
        applyLangPill(enPill, !thai);
        skipButton.setText(thai ? "ข้าม" : "Skip");
        titleView.setText(thai ? step.titleTh : step.titleEn);
        doubleTapDemo.setThai(thai);
        doubleTapDemo.setVisibility(step.showDoubleTapDemo ? VISIBLE : GONE);
        bodyView.setText(thai ? step.bodyTh : step.bodyEn);
        fullGuideButton.setText(thai ? "คู่มือฉบับเต็ม" : "Full guide");
        backButton.setText(thai ? "ย้อนกลับ" : "Back");
        backButton.setVisibility(index > 0 ? VISIBLE : GONE);
        boolean last = index + 1 >= steps.size();
        nextButton.setText(last ? (thai ? "เริ่มใช้งาน" : "Done") : (thai ? "ถัดไป" : "Next"));

        dotsRow.removeAllViews();
        for (int i = 0; i < steps.size(); i++) {
            View dot = new View(getContext());
            boolean active = i == index;
            dot.setBackground(Ui.shape(getContext(), active ? colors.accent : colors.border, 4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(active ? 18 : 6), dp(6));
            if (i > 0) lp.setMarginStart(dp(5));
            dotsRow.addView(dot, lp);
        }
    }

    private void applyLangPill(TextView pill, boolean active) {
        pill.setBackground(Ui.pressable(getContext(),
                active ? colors.accentWash : Color.TRANSPARENT, 8,
                active ? colors.accent : Color.TRANSPARENT));
        pill.setTextColor(active ? colors.accent : colors.muted);
    }

    private boolean scrollToTarget(View target) {
        if (scroll == null || target == null) return false;
        Rect rect = new Rect();
        target.getDrawingRect(rect);
        try {
            scroll.offsetDescendantRectToMyCoords(target, rect);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        int viewH = scroll.getHeight();
        if (viewH <= 0) return false;
        int desiredTop = Math.max(0, rect.top - dp(88));
        int curY = scroll.getScrollY();
        if (rect.top < curY + dp(36) || rect.bottom > curY + viewH - dp(56)) {
            if (Math.abs(desiredTop - curY) > dp(4)) {
                scroll.smoothScrollTo(0, desiredTop);
                return true;
            }
        }
        return false;
    }

    private void measureTargetHole(View target, RectF out) {
        if (target == null || !target.isAttachedToWindow() || target.getWidth() <= 0 || target.getHeight() <= 0) {
            float cx = getWidth() * 0.5f;
            float cy = getHeight() * 0.35f;
            out.set(cx - dp(40), cy - dp(24), cx + dp(40), cy + dp(24));
            return;
        }
        getLocationOnScreen(myLoc);
        target.getLocationOnScreen(targetLoc);
        float pad = dp(8);
        float l = Math.max(dp(6), targetLoc[0] - myLoc[0] - pad);
        float t = Math.max(dp(6), targetLoc[1] - myLoc[1] - pad);
        float r = Math.min(getWidth() - dp(6), targetLoc[0] - myLoc[0] + target.getWidth() + pad);
        float b = Math.min(getHeight() - dp(6), targetLoc[1] - myLoc[1] + target.getHeight() + pad);
        out.set(l, t, Math.max(l + dp(20), r), Math.max(t + dp(20), b));
    }

    private void animateHoleTo(RectF destination) {
        if (morph != null) morph.cancel();
        RectF start = new RectF(currentHole);
        RectF end = new RectF(destination);
        morph = ValueAnimator.ofFloat(0f, 1f);
        morph.setDuration(260);
        morph.setInterpolator(EASE);
        morph.addUpdateListener(anim -> {
            float f = (float) anim.getAnimatedValue();
            currentHole.set(
                    start.left + (end.left - start.left) * f,
                    start.top + (end.top - start.top) * f,
                    start.right + (end.right - start.right) * f,
                    start.bottom + (end.bottom - start.bottom) * f);
            backdrop.invalidate();
        });
        morph.start();
        positionCard(true);
    }

    private void positionCard(boolean animate) {
        int rootW = getWidth();
        int rootH = getHeight();
        if (rootW <= 0 || rootH <= 0) return;
        int cardW = Math.min(dp(420), rootW - dp(32));
        card.measure(
                MeasureSpec.makeMeasureSpec(cardW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(rootH - dp(32), MeasureSpec.AT_MOST));
        int cardH = card.getMeasuredHeight();
        int margin = dp(16);
        int gap = dp(16);

        float spaceBelow = rootH - targetHole.bottom - margin;
        float spaceAbove = targetHole.top - margin;
        float spaceLeft = targetHole.left - margin;
        float spaceRight = rootW - targetHole.right - margin;
        boolean preferSide = targetHole.height() > rootH * 0.40f;

        float x;
        float y;
        if (preferSide && spaceRight >= cardW + gap) {
            x = targetHole.right + gap;
            y = clamp(targetHole.centerY() - cardH * 0.5f, margin, rootH - cardH - margin);
        } else if (preferSide && spaceLeft >= cardW + gap) {
            x = targetHole.left - gap - cardW;
            y = clamp(targetHole.centerY() - cardH * 0.5f, margin, rootH - cardH - margin);
        } else if (spaceBelow >= cardH + gap) {
            x = clamp(targetHole.centerX() - cardW * 0.5f, margin, rootW - cardW - margin);
            y = targetHole.bottom + gap;
        } else if (spaceAbove >= cardH + gap) {
            x = clamp(targetHole.centerX() - cardW * 0.5f, margin, rootW - cardW - margin);
            y = targetHole.top - gap - cardH;
        } else if (spaceLeft >= cardW + gap) {
            x = targetHole.left - gap - cardW;
            y = clamp(targetHole.centerY() - cardH * 0.5f, margin, rootH - cardH - margin);
        } else if (spaceRight >= cardW + gap) {
            x = targetHole.right + gap;
            y = clamp(targetHole.centerY() - cardH * 0.5f, margin, rootH - cardH - margin);
        } else {
            x = (rootW - cardW) * 0.5f;
            y = rootH - cardH - margin;
        }
        if (animate) {
            card.animate().translationX(x).translationY(y).setDuration(220).setInterpolator(EASE).start();
        } else {
            card.animate().cancel();
            card.setTranslationX(x);
            card.setTranslationY(y);
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private int dp(float value) {
        return Ui.dp(getContext(), value);
    }

    private final class SpotlightCanvas extends View {
        private final Path holePath = new Path();
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        SpotlightCanvas(Context context) {
            super(context);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setColor(0xe6ffffff);
            borderPaint.setStrokeWidth(dp(2));
            glowPaint.setStyle(Paint.Style.STROKE);
            glowPaint.setColor(0x4dffffff);
            glowPaint.setStrokeWidth(dp(6));
        }

        @Override protected void onDraw(Canvas canvas) {
            if (currentHole.isEmpty()) {
                canvas.drawColor(0x96000000);
                return;
            }
            float radius = dp(18);
            holePath.reset();
            holePath.addRoundRect(currentHole, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipOutPath(holePath);
            canvas.drawColor(0x96000000);
            canvas.restore();
            canvas.drawRoundRect(currentHole, radius, radius, glowPaint);
            canvas.drawRoundRect(currentHole, radius, radius, borderPaint);
        }
    }
}
