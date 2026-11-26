package th.pendock;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Floating resizable pinned image overlay with edge-tucking, opacity toggle, and long-press Save/Copy/Share. */
final class PinnedImageOverlay extends FrameLayout {
    interface Host {
        void onMove(PinnedImageOverlay pin, int x, int y);
        void onSave(Bitmap copy);
        void onCopy(Bitmap copy);
        void onShare(Bitmap copy);
        void onClose(PinnedImageOverlay pin);
    }

    private static final long LONG_PRESS_MS = 420L;
    private static final long QUICK_HIDE_MS = 3200L;

    private final Appearance.Palette colors;
    private final Host host;
    private final Bitmap bitmap;
    private final float aspectRatio;
    private final int shadowPad;
    private final int touchSlop;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final FrameLayout card;
    private final ImageView imageView;
    private final LinearLayout quickControls;
    private final LinearLayout actionMenu;
    private final FrameLayout edgePill;
    private final ImageView edgePinIcon;
    private final View resizeGrip;

    private int cardW;
    private int cardH;
    private int winX;
    private int winY;
    private int edgeDock; // -1 left, 0 free, +1 right
    private int opacityStep; // 0 = 1.0, 1 = 0.70, 2 = 0.40
    private boolean closed;

    // Drag / long-press / pinch state
    private boolean dragging;
    private boolean pinching;
    private boolean longPressFired;
    private float downRawX, downRawY;
    private int startWinX, startWinY;
    private float pinchStartDist;
    private int pinchStartW;

    // Resize grip drag state
    private float gripDownRawX, gripDownRawY;
    private int gripStartW;

    private final Runnable longPressTask = () -> {
        if (closed || dragging || pinching) return;
        longPressFired = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        showActionMenu();
    };

    private final Runnable hideQuickTask = this::hideQuickControls;

    private void hideQuickControls() {
        if (!closed && quickControls != null && quickControls.getVisibility() == VISIBLE) {
            quickControls.animate().alpha(0f).setDuration(160)
                    .withEndAction(() -> quickControls.setVisibility(GONE)).start();
        }
    }

    PinnedImageOverlay(Context context, Bitmap bitmap, Rect initialScreenRect, Host host) {
        super(context);
        this.colors = Ui.colors(context);
        this.bitmap = bitmap;
        this.host = host;
        this.aspectRatio = Math.max(0.2f, Math.min(5f,
                bitmap.getWidth() / (float) Math.max(1, bitmap.getHeight())));
        this.shadowPad = dp(12);
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        setClipChildren(false);
        setClipToPadding(false);
        setPadding(shadowPad, shadowPad, shadowPad, shadowPad);

        int sw = screenW();
        int sh = screenH();
        int minW = dp(130);
        int maxW = Math.max(dp(220), Math.round(sw * 0.65f));
        int initW = initialScreenRect != null && initialScreenRect.width() > 0
                ? initialScreenRect.width() : dp(260);
        cardW = clamp(initW, minW, maxW);
        cardH = Math.max(dp(80), Math.round(cardW / aspectRatio));
        int maxH = Math.round(sh * 0.65f);
        if (cardH > maxH) {
            cardH = maxH;
            cardW = Math.max(minW, Math.round(cardH * aspectRatio));
        }

        winX = initialScreenRect != null ? (initialScreenRect.left - shadowPad) : dp(36);
        winY = initialScreenRect != null ? (initialScreenRect.top - shadowPad) : dp(96);
        clampPosition();

        // Main rounded card
        card = new FrameLayout(context);
        card.setElevation(dp(10));
        card.setBackground(Ui.shape(context, colors.surface, 14));
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(14));
            }
        });
        card.setClipToOutline(true);

        imageView = new ImageView(context);
        imageView.setImageBitmap(bitmap);
        imageView.setScaleType(ImageView.ScaleType.FIT_XY);
        card.addView(imageView, new LayoutParams(-1, -1));

        // Subtle crisp inner frame border
        View frameBorder = new View(context);
        frameBorder.setBackground(Ui.border(context, Color.TRANSPARENT, 14, withAlpha(colors.accent, 140)));
        card.addView(frameBorder, new LayoutParams(-1, -1));

        // Bottom-right resize grip
        resizeGrip = new ResizeGripView(context);
        resizeGrip.setContentDescription(context.getString(R.string.pin_resize));
        resizeGrip.setOnTouchListener((v, ev) -> handleGripTouch(ev));
        LayoutParams gripP = new LayoutParams(dp(28), dp(28), Gravity.BOTTOM | Gravity.END);
        card.addView(resizeGrip, gripP);

        // Quick tap controls (top-right: opacity + close)
        quickControls = new LinearLayout(context);
        quickControls.setGravity(Gravity.CENTER_VERTICAL);
        quickControls.setPadding(dp(4), dp(4), dp(4), dp(4));
        quickControls.setBackground(Ui.border(context, withAlpha(colors.surface, 238), 12, colors.border));
        quickControls.setElevation(dp(4));
        quickControls.setVisibility(GONE);

        TextView opacityBtn = miniPill("◐", context.getString(R.string.pin_opacity));
        opacityBtn.setOnClickListener(v -> {
            cycleOpacity();
            scheduleQuickHide();
        });
        quickControls.addView(opacityBtn);

        TextView closeBtn = miniPill("✕", context.getString(R.string.close));
        closeBtn.setOnClickListener(v -> closePin());
        LinearLayout.LayoutParams closeP = new LinearLayout.LayoutParams(-2, -2);
        closeP.setMarginStart(dp(4));
        quickControls.addView(closeBtn, closeP);

        LayoutParams quickP = new LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        quickP.topMargin = dp(8);
        quickP.setMarginEnd(dp(8));
        card.addView(quickControls, quickP);

        // Long-press action menu (Save | Copy | Share | Close)
        actionMenu = new LinearLayout(context);
        actionMenu.setGravity(Gravity.CENTER_VERTICAL);
        actionMenu.setPadding(dp(6), dp(6), dp(6), dp(6));
        actionMenu.setBackground(Ui.border(context, withAlpha(colors.surface, 245), 14, colors.border));
        actionMenu.setElevation(dp(8));
        actionMenu.setVisibility(GONE);
        actionMenu.setClickable(true);

        addMenuAction(R.drawable.ic_save, R.string.circle_action_save, () -> {
            hideActionMenu();
            Bitmap copy = safeCopy();
            if (copy != null && host != null) host.onSave(copy);
        });
        addMenuAction(R.drawable.ic_copy, R.string.circle_action_copy, () -> {
            hideActionMenu();
            Bitmap copy = safeCopy();
            if (copy != null && host != null) host.onCopy(copy);
        });
        addMenuAction(R.drawable.ic_share, R.string.circle_action_share, () -> {
            hideActionMenu();
            Bitmap copy = safeCopy();
            if (copy != null && host != null) host.onShare(copy);
        });
        TextView menuClose = miniPill("✕", context.getString(R.string.close));
        menuClose.setOnClickListener(v -> closePin());
        LinearLayout.LayoutParams mcP = new LinearLayout.LayoutParams(-2, -2);
        mcP.setMarginStart(dp(4));
        actionMenu.addView(menuClose, mcP);

        LayoutParams menuP = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        menuP.bottomMargin = dp(10);
        card.addView(actionMenu, menuP);

        card.setOnTouchListener((v, ev) -> handleCardTouch(ev));
        addView(card, new LayoutParams(cardW, cardH));

        // Edge-tucked pill
        edgePill = new FrameLayout(context);
        edgePill.setBackground(Ui.border(context, withAlpha(colors.surface, 244), 14, colors.accent));
        edgePill.setElevation(dp(8));
        edgePill.setVisibility(GONE);
        edgePinIcon = new ImageView(context);
        edgePinIcon.setImageResource(R.drawable.ic_pin);
        edgePinIcon.setImageTintList(ColorStateList.valueOf(colors.accent));
        edgePill.addView(edgePinIcon, new LayoutParams(dp(18), dp(18), Gravity.CENTER));
        edgePill.setOnClickListener(v -> untuckFromEdge());
        addView(edgePill, new LayoutParams(dp(36), dp(50)));
    }

    int windowX() { return winX; }
    int windowY() { return winY; }

    private void addMenuAction(int iconRes, int labelRes, Runnable onClick) {
        LinearLayout btn = new LinearLayout(getContext());
        btn.setGravity(Gravity.CENTER_VERTICAL);
        btn.setPadding(dp(9), dp(6), dp(10), dp(6));
        btn.setMinimumHeight(dp(34));
        btn.setBackground(Ui.pressable(getContext(), colors.rail, 10, colors.border));
        btn.setOnClickListener(v -> onClick.run());

        ImageView iv = new ImageView(getContext());
        iv.setImageResource(iconRes);
        iv.setImageTintList(ColorStateList.valueOf(colors.text));
        btn.addView(iv, new LinearLayout.LayoutParams(dp(16), dp(16)));

        TextView tv = Ui.text(getContext(), getContext().getString(labelRes), 12, colors.text, true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.setMarginStart(dp(5));
        btn.addView(tv, tp);

        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        if (actionMenu.getChildCount() > 0) bp.setMarginStart(dp(5));
        actionMenu.addView(btn, bp);
    }

    private TextView miniPill(String symbol, String desc) {
        TextView btn = Ui.text(getContext(), symbol, 13, colors.text, true);
        btn.setGravity(Gravity.CENTER);
        btn.setMinWidth(dp(28));
        btn.setMinHeight(dp(28));
        btn.setPadding(dp(6), dp(2), dp(6), dp(2));
        btn.setContentDescription(desc);
        btn.setBackground(Ui.pressable(getContext(), colors.rail, 9, colors.border));
        return btn;
    }

    private boolean handleCardTouch(MotionEvent ev) {
        int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                dragging = false;
                pinching = false;
                longPressFired = false;
                downRawX = ev.getRawX();
                downRawY = ev.getRawY();
                startWinX = winX;
                startWinY = winY;
                main.removeCallbacks(longPressTask);
                main.postDelayed(longPressTask, LONG_PRESS_MS);
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (ev.getPointerCount() >= 2) {
                    main.removeCallbacks(longPressTask);
                    pinching = true;
                    dragging = false;
                    pinchStartDist = pointerDistance(ev);
                    pinchStartW = cardW;
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (pinching && ev.getPointerCount() >= 2 && pinchStartDist > dp(20)) {
                    float dist = pointerDistance(ev);
                    int targetW = Math.round(pinchStartW * (dist / pinchStartDist));
                    applyCardWidth(targetW);
                    return true;
                }
                if (longPressFired) return true;
                float dx = ev.getRawX() - downRawX;
                float dy = ev.getRawY() - downRawY;
                if (!dragging && Math.hypot(dx, dy) > touchSlop) {
                    dragging = true;
                    main.removeCallbacks(longPressTask);
                    hideActionMenu();
                }
                if (dragging) {
                    winX = Math.round(startWinX + dx);
                    winY = Math.round(startWinY + dy);
                    clampPosition();
                    notifyMove();
                }
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                if (ev.getPointerCount() <= 2) {
                    pinching = false;
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                main.removeCallbacks(longPressTask);
                if (dragging) {
                    dragging = false;
                    maybeSnapToEdge();
                } else if (!pinching && !longPressFired && action == MotionEvent.ACTION_UP) {
                    if (actionMenu.getVisibility() == VISIBLE) {
                        hideActionMenu();
                    } else {
                        toggleQuickControls();
                    }
                }
                pinching = false;
                return true;
        }
        return false;
    }

    private boolean handleGripTouch(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            main.removeCallbacks(longPressTask);
            hideActionMenu();
            gripDownRawX = ev.getRawX();
            gripDownRawY = ev.getRawY();
            gripStartW = cardW;
            return true;
        } else if (action == MotionEvent.ACTION_MOVE) {
            float dx = ev.getRawX() - gripDownRawX;
            float dy = ev.getRawY() - gripDownRawY;
            float delta = Math.abs(dx) >= Math.abs(dy) ? dx : (dy * aspectRatio);
            applyCardWidth(Math.round(gripStartW + delta));
            return true;
        }
        return action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
    }

    private void applyCardWidth(int requestedW) {
        int sw = screenW();
        int sh = screenH();
        int minW = dp(120);
        int maxW = Math.max(dp(220), Math.round(sw * 0.85f));
        int newW = clamp(requestedW, minW, maxW);
        int newH = Math.max(dp(76), Math.round(newW / aspectRatio));
        int maxH = Math.round(sh * 0.82f);
        if (newH > maxH) {
            newH = maxH;
            newW = Math.max(minW, Math.round(newH * aspectRatio));
        }
        if (newW == cardW && newH == cardH) return;
        cardW = newW;
        cardH = newH;
        LayoutParams lp = (LayoutParams) card.getLayoutParams();
        lp.width = cardW;
        lp.height = cardH;
        card.setLayoutParams(lp);
        clampPosition();
        notifyMove();
    }

    private void toggleQuickControls() {
        if (quickControls.getVisibility() == VISIBLE) {
            main.removeCallbacks(hideQuickTask);
            quickControls.setVisibility(GONE);
        } else {
            quickControls.setAlpha(0f);
            quickControls.setVisibility(VISIBLE);
            quickControls.animate().alpha(1f).setDuration(140).start();
            scheduleQuickHide();
        }
    }

    private void scheduleQuickHide() {
        main.removeCallbacks(hideQuickTask);
        main.postDelayed(hideQuickTask, QUICK_HIDE_MS);
    }

    private void showActionMenu() {
        main.removeCallbacks(hideQuickTask);
        quickControls.setVisibility(GONE);
        // Ensure card is wide enough to show the compact action menu cleanly
        if (cardW < dp(245)) {
            applyCardWidth(dp(255));
        }
        actionMenu.setAlpha(0f);
        actionMenu.setScaleX(0.92f);
        actionMenu.setScaleY(0.92f);
        actionMenu.setVisibility(VISIBLE);
        actionMenu.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start();
    }

    private void hideActionMenu() {
        if (actionMenu.getVisibility() == VISIBLE) {
            actionMenu.setVisibility(GONE);
        }
    }

    private void cycleOpacity() {
        opacityStep = (opacityStep + 1) % 3;
        float alpha = opacityStep == 0 ? 1.0f : (opacityStep == 1 ? 0.70f : 0.40f);
        imageView.setAlpha(alpha);
    }

    private void maybeSnapToEdge() {
        int sw = screenW();
        int snapDist = dp(18);
        int visibleLeft = winX + shadowPad;
        int visibleRight = visibleLeft + cardW;
        if (visibleLeft <= snapDist) {
            edgeDock = -1;
            showEdgePill();
            winX = -shadowPad;
            notifyMove();
        } else if (visibleRight >= sw - snapDist) {
            edgeDock = 1;
            showEdgePill();
            winX = sw - dp(36) - shadowPad;
            notifyMove();
        }
    }

    private void showEdgePill() {
        main.removeCallbacks(hideQuickTask);
        quickControls.setVisibility(GONE);
        actionMenu.setVisibility(GONE);
        card.setVisibility(GONE);
        edgePill.setVisibility(VISIBLE);
    }

    private void untuckFromEdge() {
        int side = edgeDock;
        edgeDock = 0;
        edgePill.setVisibility(GONE);
        card.setVisibility(VISIBLE);
        int sw = screenW();
        winX = side > 0 ? Math.max(-shadowPad, sw - cardW - dp(12) - shadowPad) : (dp(12) - shadowPad);
        clampPosition();
        notifyMove();
    }

    private void clampPosition() {
        int sw = screenW();
        int sh = screenH();
        int w = edgeDock != 0 ? dp(36) : cardW;
        int h = edgeDock != 0 ? dp(50) : cardH;
        winX = clamp(winX, -shadowPad, Math.max(-shadowPad, sw - w - shadowPad));
        winY = clamp(winY, dp(12) - shadowPad, Math.max(dp(12) - shadowPad, sh - h - dp(12) - shadowPad));
    }

    private void notifyMove() {
        if (host != null && !closed) host.onMove(this, winX, winY);
    }

    private Bitmap safeCopy() {
        if (bitmap == null || bitmap.isRecycled()) return null;
        try {
            return bitmap.copy(Bitmap.Config.ARGB_8888, false);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void closePin() {
        if (closed) return;
        closed = true;
        main.removeCallbacksAndMessages(null);
        if (host != null) host.onClose(this);
    }

    void destroy() {
        closed = true;
        main.removeCallbacksAndMessages(null);
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private static float pointerDistance(MotionEvent ev) {
        float dx = ev.getX(0) - ev.getX(1);
        float dy = ev.getY(0) - ev.getY(1);
        return (float) Math.hypot(dx, dy);
    }

    private int screenW() { return getResources().getDisplayMetrics().widthPixels; }
    private int screenH() { return getResources().getDisplayMetrics().heightPixels; }
    private int dp(float v) { return Ui.dp(getContext(), v); }
    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private static int withAlpha(int color, int alpha) {
        return (color & 0x00ffffff) | ((alpha & 255) << 24);
    }

    /** Minimalist corner resize handle drawn cleanly at the bottom-right of the pinned card. */
    private final class ResizeGripView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        ResizeGripView(Context context) {
            super(context);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(dp(2.2f));
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth();
            int h = getHeight();
            p.setColor(0x88000000);
            drawLines(c, w, h, dp(1));
            p.setColor(0xe6ffffff);
            drawLines(c, w, h, 0);
        }

        private void drawLines(Canvas c, int w, int h, int offset) {
            int pad = dp(6);
            c.drawLine(w - pad - dp(9) + offset, h - pad + offset, w - pad + offset, h - pad - dp(9) + offset, p);
            c.drawLine(w - pad - dp(4) + offset, h - pad + offset, w - pad + offset, h - pad - dp(4) + offset, p);
        }
    }
}
