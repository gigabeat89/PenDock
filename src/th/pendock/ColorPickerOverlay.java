package th.pendock;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Full-screen eyedropper / color picker with 11x11 pixel-grid loupe,
 * draggable & edge-tuckable floating toolbar, Pause/Play, integrated 10-row dropdown history,
 * and in-overlay Copied popup.
 */
final class ColorPickerOverlay extends FrameLayout {
    interface Host {
        void onPause();
        void onRequestResume();
        void onMoveToolbar(int windowX, int windowY);
        void onCopiedPopupPosition(boolean showAtTop);
        void onClose();
    }

    private static final String PREF_HISTORY = "color_picker_history";
    private static final String PREF_PINNED = "color_picker_pinned";
    private static final int MAX_HISTORY = 10;
    private static final float PRESSURE_PIN_THRESHOLD = 0.46f;

    private Bitmap screenshot;
    private final Appearance.Palette colors;
    private final Host host;
    private final LoupeCanvas canvas;

    // Floating toolbar window views
    private final LinearLayout toolbarContainer;
    private final LinearLayout toolBoxCard;
    private final View swatchView;
    private final TextView hexLabel;
    private final TextView rgbLabel;
    private final ImageView pauseBtn;
    private final View historyDivider;
    private final LinearLayout historyTopRow;
    private final LinearLayout historyTopItem;
    private final View historyTopBox;
    private final TextView historyTopCode;
    private final TextView historyTopRgb;
    private final ImageView historyTopPin;
    private final TextView historyToggleBtn;
    private final LinearLayout historyDropdown;
    private final FrameLayout edgeTab;
    private final View edgeColorDot;
    private final ImageView edgeStatusIcon;
    private final LinearLayout copiedPopup;
    private final View copiedSwatch;
    private final TextView copiedText;

    private final List<Integer> history = new ArrayList<>();
    private final Set<Integer> pinned = new LinkedHashSet<>();
    private final Runnable hideCopiedPopup;

    private float sampleX = -1f;
    private float sampleY = -1f;
    private int currentColor = 0xff2863da;
    private boolean hasSample;
    private boolean paused;
    private boolean historyExpanded;
    private boolean edgeTucked;
    private boolean edgeOnRight;
    private int barX;
    private int barY;
    private int savedYBeforeExpand = -1;
    private boolean barPositioned;
    private int screenW;
    private int screenH;

    ColorPickerOverlay(Context context, Bitmap screenshot, Host host) {
        super(context);
        this.screenshot = screenshot;
        this.colors = Ui.colors(context);
        this.host = host;
        setFocusableInTouchMode(true);
        refreshDisplayMetrics();

        canvas = new LoupeCanvas(context);
        addView(canvas, new LayoutParams(-1, -1));

        // Floating toolbar container (lives in its own persistent WindowManager window)
        toolbarContainer = Ui.column(context);
        toolbarContainer.setGravity(Gravity.START);
        toolbarContainer.setClipChildren(false);
        toolbarContainer.setClipToPadding(false);

        // Unified draggable tool box card (Main HUD + integrated History row + dropdown)
        toolBoxCard = Ui.column(context);
        toolBoxCard.setClipChildren(false);
        toolBoxCard.setClipToPadding(false);
        toolBoxCard.setPadding(dp(10), dp(8), dp(10), dp(8));
        toolBoxCard.setBackground(Ui.border(context, withAlpha(colors.surface, 246), 20, colors.border));
        toolBoxCard.setElevation(dp(12));
        toolBoxCard.setClickable(true);

        // Row 1: Drag handle + swatch + HEX/RGB + HEX btn + RGB btn + Pause/Play + Close
        LinearLayout mainRow = new LinearLayout(context);
        mainRow.setOrientation(LinearLayout.HORIZONTAL);
        mainRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView dragHandle = Ui.text(context, "⋮⋮", 14, colors.muted, true);
        dragHandle.setGravity(Gravity.CENTER);
        dragHandle.setPadding(dp(4), dp(4), dp(8), dp(4));
        dragHandle.setMinHeight(dp(34));
        attachToolbarDrag(dragHandle, false);
        mainRow.addView(dragHandle, new LinearLayout.LayoutParams(-2, -2));

        swatchView = new View(context);
        swatchView.setBackground(Ui.border(context, currentColor, 9, colors.border));
        attachToolbarDrag(swatchView, false);
        mainRow.addView(swatchView, new LinearLayout.LayoutParams(dp(26), dp(26)));

        LinearLayout info = Ui.column(context);
        info.setPadding(dp(9), 0, dp(10), 0);
        hexLabel = Ui.text(context, "#2863DA", 14, colors.text, true);
        hexLabel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        rgbLabel = Ui.text(context, context.getString(R.string.color_picker_hint), 11, colors.muted, false);
        info.addView(hexLabel);
        info.addView(rgbLabel);
        attachToolbarDrag(info, false);
        mainRow.addView(info, new LinearLayout.LayoutParams(-2, -2));

        TextView copyHexBtn = pillAction("HEX", () -> copyColor(currentColor, formatHex(currentColor), true));
        mainRow.addView(copyHexBtn, new LinearLayout.LayoutParams(-2, -2));

        TextView copyRgbBtn = pillAction("RGB", () -> copyColor(currentColor, formatRgb(currentColor), true));
        LinearLayout.LayoutParams rgbP = new LinearLayout.LayoutParams(-2, -2);
        rgbP.setMarginStart(dp(6));
        mainRow.addView(copyRgbBtn, rgbP);

        pauseBtn = new ImageView(context);
        pauseBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        pauseBtn.setPadding(dp(8), dp(8), dp(8), dp(8));
        pauseBtn.setImageResource(R.drawable.ic_translate_pause);
        pauseBtn.setImageTintList(ColorStateList.valueOf(colors.text));
        pauseBtn.setBackground(Ui.pressable(context, colors.rail, 11, colors.border));
        pauseBtn.setContentDescription(context.getString(R.string.translate_pause));
        pauseBtn.setOnClickListener(v -> togglePause());
        LinearLayout.LayoutParams pauseP = new LinearLayout.LayoutParams(dp(34), dp(34));
        pauseP.setMarginStart(dp(6));
        mainRow.addView(pauseBtn, pauseP);

        TextView closeBtn = Ui.text(context, "✕", 15, colors.text, true);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setMinWidth(dp(34));
        closeBtn.setMinHeight(dp(34));
        closeBtn.setBackground(Ui.pressable(context, colors.rail, 11, colors.border));
        closeBtn.setContentDescription(context.getString(R.string.close));
        closeBtn.setOnClickListener(v -> host.onClose());
        LinearLayout.LayoutParams closeP = new LinearLayout.LayoutParams(-2, -2);
        closeP.setMarginStart(dp(6));
        mainRow.addView(closeBtn, closeP);

        toolBoxCard.addView(mainRow, new LinearLayout.LayoutParams(-2, -2));

        // Row 2: Integrated History (shows #1 by default + dropdown button for #2..#10)
        historyDivider = new View(context);
        historyDivider.setBackgroundColor(colors.border);
        LinearLayout.LayoutParams divP = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
        divP.topMargin = dp(8);
        divP.bottomMargin = dp(6);
        toolBoxCard.addView(historyDivider, divP);

        historyTopRow = new LinearLayout(context);
        historyTopRow.setClipChildren(false);
        historyTopRow.setClipToPadding(false);
        historyTopRow.setOrientation(LinearLayout.HORIZONTAL);
        historyTopRow.setGravity(Gravity.CENTER_VERTICAL);

        historyTopItem = new LinearLayout(context);
        historyTopItem.setOrientation(LinearLayout.HORIZONTAL);
        historyTopItem.setGravity(Gravity.CENTER_VERTICAL);
        historyTopItem.setPadding(dp(6), dp(5), dp(10), dp(5));
        historyTopItem.setBackground(Ui.pressable(context, colors.rail, 10, 0x00000000));

        historyTopBox = new View(context);
        historyTopBox.setBackground(Ui.border(context, currentColor, 6, colors.border));
        historyTopItem.addView(historyTopBox, new LinearLayout.LayoutParams(dp(18), dp(18)));

        historyTopCode = Ui.text(context, "#2863DA", 12, colors.text, true);
        historyTopCode.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        LinearLayout.LayoutParams htcP = new LinearLayout.LayoutParams(-2, -2);
        htcP.setMarginStart(dp(8));
        historyTopItem.addView(historyTopCode, htcP);

        historyTopRgb = Ui.text(context, "", 11, colors.muted, false);
        historyTopRgb.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams htrP = new LinearLayout.LayoutParams(0, -2, 1f);
        htrP.setMarginStart(dp(8));
        historyTopItem.addView(historyTopRgb, htrP);

        historyTopPin = new ImageView(context);
        historyTopPin.setImageResource(R.drawable.ic_pin);
        historyTopPin.setImageTintList(ColorStateList.valueOf(colors.accent));
        historyTopPin.setVisibility(GONE);
        LinearLayout.LayoutParams htpP = new LinearLayout.LayoutParams(dp(14), dp(14));
        htpP.setMarginStart(dp(6));
        historyTopItem.addView(historyTopPin, htpP);

        historyTopRow.addView(historyTopItem, new LinearLayout.LayoutParams(0, -2, 1f));

        historyToggleBtn = Ui.text(context, "▾", 12, colors.muted, true);
        historyToggleBtn.setGravity(Gravity.CENTER);
        historyToggleBtn.setPadding(dp(10), dp(5), dp(10), dp(5));
        historyToggleBtn.setMinHeight(dp(28));
        historyToggleBtn.setBackground(Ui.pressable(context, colors.rail, 10, colors.border));
        historyToggleBtn.setOnClickListener(v -> {
            if (!historyExpanded) {
                savedYBeforeExpand = barY;
                historyExpanded = true;
                renderHistory();
                clampToolbarToScreen();
            } else {
                historyExpanded = false;
                renderHistory();
                if (savedYBeforeExpand >= 0) {
                    barY = savedYBeforeExpand;
                    savedYBeforeExpand = -1;
                    applyToolbarPosition();
                    clampToolbarToScreen();
                }
            }
        });
        LinearLayout.LayoutParams togP = new LinearLayout.LayoutParams(-2, -2);
        togP.setMarginStart(dp(6));
        historyTopRow.addView(historyToggleBtn, togP);

        toolBoxCard.addView(historyTopRow, new LinearLayout.LayoutParams(-1, -2));

        // Dropdown container for history rows #2..#10
        historyDropdown = Ui.column(context);
        historyDropdown.setClipChildren(false);
        historyDropdown.setClipToPadding(false);
        historyDropdown.setVisibility(GONE);
        LinearLayout.LayoutParams dropP = new LinearLayout.LayoutParams(-1, -2);
        dropP.topMargin = dp(4);
        toolBoxCard.addView(historyDropdown, dropP);

        toolbarContainer.addView(toolBoxCard, new LinearLayout.LayoutParams(-2, -2));

        // Sleek side-edge handle shown when tucked against left or right screen edge
        edgeTab = new FrameLayout(context);
        edgeTab.setBackground(Ui.border(context, withAlpha(colors.surface, 246), 14, colors.accent));
        edgeTab.setElevation(dp(12));
        LinearLayout edgeCol = Ui.column(context);
        edgeCol.setGravity(Gravity.CENTER);
        edgeColorDot = new View(context);
        edgeColorDot.setBackground(Ui.border(context, currentColor, 9, colors.border));
        edgeCol.addView(edgeColorDot, new LinearLayout.LayoutParams(dp(18), dp(18)));
        edgeStatusIcon = new ImageView(context);
        edgeStatusIcon.setImageResource(R.drawable.ic_translate_pause);
        edgeStatusIcon.setImageTintList(ColorStateList.valueOf(colors.accent));
        LinearLayout.LayoutParams esiP = new LinearLayout.LayoutParams(dp(12), dp(12));
        esiP.topMargin = dp(5);
        edgeCol.addView(edgeStatusIcon, esiP);
        edgeTab.addView(edgeCol, new LayoutParams(-2, -2, Gravity.CENTER));
        edgeTab.setContentDescription(context.getString(R.string.color_picker_tool));
        edgeTab.setVisibility(GONE);
        attachToolbarDrag(edgeTab, true);
        toolbarContainer.addView(edgeTab, new LinearLayout.LayoutParams(dp(38), dp(56)));

        // Standalone Copied popup pill (mounted in its own centered non-touchable overlay window
        // so it never clips at left/right edge-tuck or bottom of screen, and works both Active & Paused)
        copiedPopup = new LinearLayout(context);
        copiedPopup.setOrientation(LinearLayout.HORIZONTAL);
        copiedPopup.setGravity(Gravity.CENTER_VERTICAL);
        copiedPopup.setPadding(dp(14), dp(9), dp(16), dp(9));
        copiedPopup.setBackground(Ui.border(context, 0xee161b26, 18, 0x44ffffff));
        copiedPopup.setElevation(dp(14));
        copiedPopup.setVisibility(GONE);
        copiedPopup.setAlpha(0f);

        copiedSwatch = new View(context);
        copiedSwatch.setBackground(Ui.border(context, currentColor, 6, 0x88ffffff));
        copiedPopup.addView(copiedSwatch, new LinearLayout.LayoutParams(dp(16), dp(16)));

        copiedText = Ui.text(context, "", 12, 0xffffffff, true);
        copiedText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        LinearLayout.LayoutParams ctP = new LinearLayout.LayoutParams(-2, -2);
        ctP.setMarginStart(dp(8));
        copiedPopup.addView(copiedText, ctP);

        hideCopiedPopup = () -> copiedPopup.animate()
                .alpha(0f)
                .setDuration(180)
                .withEndAction(() -> copiedPopup.setVisibility(GONE))
                .start();

        loadHistory();
        if (!history.isEmpty()) {
            applyHudColor(history.get(0));
        }
        renderHistory();
        updatePauseVisuals();

        // Initial position: top-center
        toolBoxCard.measure(
                MeasureSpec.makeMeasureSpec(screenW, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(screenH, MeasureSpec.AT_MOST));
        int initW = toolBoxCard.getMeasuredWidth();
        barX = Math.max(dp(12), (screenW - initW) / 2);
        barY = dp(34);
        barPositioned = true;
    }

    View toolbarView() { return toolbarContainer; }
    View copiedPopupView() { return copiedPopup; }
    int toolbarX() { return barX; }
    int toolbarY() { return barY; }
    boolean isPaused() { return paused; }

    void pauseIfActive() {
        if (!paused) togglePause();
    }

    void resumeWithFrame(Bitmap fresh) {
        if (fresh == null) return;
        Bitmap old = screenshot;
        screenshot = fresh;
        if (old != null && !old.isRecycled()) old.recycle();
        paused = false;
        hasSample = false;
        updatePauseVisuals();
        canvas.invalidate();
    }

    void onDisplayRotated() {
        refreshDisplayMetrics();
        if (edgeTucked) {
            barX = edgeOnRight ? (screenW - dp(38)) : 0;
        } else {
            int w = Math.max(dp(220), toolbarContainer.getWidth());
            barX = Math.max(dp(8), Math.min(screenW - w - dp(8), barX));
        }
        int h = Math.max(dp(56), toolbarContainer.getHeight());
        barY = Math.max(dp(20), Math.min(screenH - h - dp(12), barY));
        applyToolbarPosition();
    }

    void destroy() {
        copiedPopup.removeCallbacks(hideCopiedPopup);
        Bitmap old = screenshot;
        screenshot = null;
        if (old != null && !old.isRecycled()) old.recycle();
    }

    private void togglePause() {
        if (!paused) {
            paused = true;
            hasSample = false;
            updatePauseVisuals();
            host.onPause();
        } else {
            host.onRequestResume();
        }
    }

    private void updatePauseVisuals() {
        pauseBtn.setImageResource(paused ? R.drawable.ic_translate_play : R.drawable.ic_translate_pause);
        pauseBtn.setImageTintList(ColorStateList.valueOf(paused ? 0xffffffff : colors.text));
        pauseBtn.setBackground(Ui.pressable(getContext(),
                paused ? colors.accent : colors.rail, 11,
                paused ? colors.accent : colors.border));
        pauseBtn.setContentDescription(getContext().getString(
                paused ? R.string.translate_resume : R.string.translate_pause));
        edgeStatusIcon.setImageResource(paused ? R.drawable.ic_translate_play : R.drawable.ic_translate_pause);
        edgeStatusIcon.setImageTintList(ColorStateList.valueOf(colors.accent));
    }

    private void setEdgeTucked(boolean tuck, boolean onRight) {
        if (edgeTucked == tuck && (!tuck || edgeOnRight == onRight)) return;
        edgeTucked = tuck;
        edgeOnRight = onRight;
        if (tuck) {
            historyExpanded = false;
            historyDropdown.setVisibility(GONE);
            toolBoxCard.setVisibility(GONE);
            edgeTab.setVisibility(VISIBLE);
            barX = onRight ? (screenW - dp(38)) : 0;
            barY = Math.max(dp(28), Math.min(screenH - dp(84), barY));
            applyToolbarPosition();
        } else {
            edgeTab.setVisibility(GONE);
            toolBoxCard.setVisibility(VISIBLE);
            toolBoxCard.measure(
                    MeasureSpec.makeMeasureSpec(screenW, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(screenH, MeasureSpec.AT_MOST));
            int w = Math.max(dp(260), toolBoxCard.getMeasuredWidth());
            int h = Math.max(dp(56), toolBoxCard.getMeasuredHeight());
            barX = onRight ? Math.max(dp(10), screenW - w - dp(12)) : dp(12);
            barY = Math.max(dp(24), Math.min(screenH - h - dp(12), barY));
            applyToolbarPosition();
        }
    }

    private void applyToolbarPosition() {
        if (!barPositioned) return;
        host.onMoveToolbar(barX, barY);
    }

    private void clampToolbarToScreen() {
        if (!barPositioned || edgeTucked) return;
        toolBoxCard.measure(
                MeasureSpec.makeMeasureSpec(screenW, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(screenH, MeasureSpec.AT_MOST));
        int w = Math.max(dp(220), toolBoxCard.getMeasuredWidth());
        int h = Math.max(dp(56), toolBoxCard.getMeasuredHeight());
        int clampedX = Math.max(dp(8), Math.min(screenW - w - dp(8), barX));
        int clampedY = Math.max(dp(16), Math.min(screenH - h - dp(12), barY));
        if (clampedX != barX || clampedY != barY) {
            barX = clampedX;
            barY = clampedY;
            applyToolbarPosition();
        }
    }

    private void attachToolbarDrag(View target, boolean isEdgeHandle) {
        target.setOnTouchListener(new OnTouchListener() {
            private float downRawX, downRawY;
            private int startX, startY;
            private boolean moved;

            @Override public boolean onTouch(View v, MotionEvent ev) {
                int act = ev.getActionMasked();
                if (act == MotionEvent.ACTION_DOWN) {
                    downRawX = ev.getRawX();
                    downRawY = ev.getRawY();
                    startX = barX;
                    startY = barY;
                    moved = false;
                    return true;
                } else if (act == MotionEvent.ACTION_MOVE) {
                    float dx = ev.getRawX() - downRawX;
                    float dy = ev.getRawY() - downRawY;
                    if (!moved && (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6))) {
                        moved = true;
                    }
                    if (!moved) return true;
                    if (isEdgeHandle) {
                        // Drag inward to untuck, or vertically along the edge
                        boolean pullInward = edgeOnRight ? (dx < -dp(36)) : (dx > dp(36));
                        if (pullInward) {
                            setEdgeTucked(false, edgeOnRight);
                            downRawX = ev.getRawX();
                            downRawY = ev.getRawY();
                            startX = barX;
                            startY = barY;
                            return true;
                        }
                        barY = Math.max(dp(20), Math.min(screenH - dp(76), Math.round(startY + dy)));
                        applyToolbarPosition();
                        return true;
                    }
                    int w = Math.max(dp(240), toolbarContainer.getWidth());
                    int h = Math.max(dp(56), toolbarContainer.getHeight());
                    int nx = Math.round(startX + dx);
                    int ny = Math.max(dp(16), Math.min(screenH - h - dp(12), Math.round(startY + dy)));
                    barX = Math.max(-w / 2, Math.min(screenW - w / 2, nx));
                    barY = ny;
                    savedYBeforeExpand = -1;
                    applyToolbarPosition();
                    return true;
                } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                    if (isEdgeHandle) {
                        if (!moved && act == MotionEvent.ACTION_UP) {
                            setEdgeTucked(false, edgeOnRight);
                        }
                        return true;
                    }
                    if (moved) {
                        int w = Math.max(dp(240), toolbarContainer.getWidth());
                        int snapEdge = dp(36);
                        if (barX < -snapEdge) {
                            setEdgeTucked(true, false);
                        } else if (barX + w > screenW + snapEdge) {
                            setEdgeTucked(true, true);
                        } else {
                            barX = Math.max(dp(8), Math.min(screenW - w - dp(8), barX));
                            applyToolbarPosition();
                        }
                    }
                    return true;
                }
                return false;
            }
        });
    }

    private TextView pillAction(String label, Runnable onClick) {
        TextView btn = Ui.text(getContext(), label, 12, colors.accent, true);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(10), dp(6), dp(10), dp(6));
        btn.setMinHeight(dp(32));
        btn.setBackground(Ui.pressable(getContext(), colors.accentWash, 10, withAlpha(colors.accent, 110)));
        btn.setOnClickListener(v -> onClick.run());
        return btn;
    }

    private void updateSample(float vx, float vy) {
        int vw = canvas.getWidth();
        int vh = canvas.getHeight();
        if (vw <= 0 || vh <= 0 || screenshot == null || screenshot.isRecycled()) return;
        sampleX = Math.max(0f, Math.min(vw - 1f, vx));
        sampleY = Math.max(0f, Math.min(vh - 1f, vy));
        int bx = Math.max(0, Math.min(screenshot.getWidth() - 1,
                Math.round(sampleX * screenshot.getWidth() / (float) vw)));
        int by = Math.max(0, Math.min(screenshot.getHeight() - 1,
                Math.round(sampleY * screenshot.getHeight() / (float) vh)));
        applyHudColor(screenshot.getPixel(bx, by) | 0xff000000);
        hasSample = true;
        canvas.invalidate();
    }

    private void applyHudColor(int color) {
        currentColor = color | 0xff000000;
        swatchView.setBackground(Ui.border(getContext(), currentColor, 9, colors.border));
        edgeColorDot.setBackground(Ui.border(getContext(), currentColor, 9, colors.border));
        hexLabel.setText(formatHex(currentColor));
        rgbLabel.setText(formatRgb(currentColor));
    }

    private void copyColor(int color, String text, boolean recordNew) {
        int opaque = color | 0xff000000;
        applyHudColor(opaque);
        ClipboardManager cm = getContext().getSystemService(ClipboardManager.class);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("PenDock Color", text));
            toolbarContainer.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        }
        if (recordNew) recordHistory(opaque);
        showCopiedPopup(opaque, text);
    }

    private void showCopiedPopup(int color, String copiedValue) {
        copiedPopup.removeCallbacks(hideCopiedPopup);
        copiedPopup.animate().cancel();
        int toolbarBottom = barY + Math.max(dp(56), toolbarContainer.getHeight());
        boolean showAtTop = !edgeTucked && toolbarBottom > screenH * 0.72f;
        host.onCopiedPopupPosition(showAtTop);
        copiedSwatch.setBackground(Ui.border(getContext(), color, 6, 0x88ffffff));
        copiedText.setText(getContext().getString(R.string.color_copied, copiedValue));
        copiedPopup.setVisibility(VISIBLE);
        copiedPopup.setAlpha(0f);
        copiedPopup.animate()
                .alpha(1f)
                .setDuration(140)
                .start();
        copiedPopup.postDelayed(hideCopiedPopup, 1350);
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences("tools", Context.MODE_PRIVATE);
    }

    private void loadHistory() {
        history.clear();
        pinned.clear();
        parseColorList(prefs().getString(PREF_PINNED, ""), pinned);
        parseColorList(prefs().getString(PREF_HISTORY, ""), history);
        orderDropdownWithPinnedFirst();
        trimHistory();
    }

    private static void parseColorList(String raw, java.util.Collection<Integer> out) {
        if (raw == null || raw.isEmpty()) return;
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            try {
                int c = Color.parseColor(trimmed) | 0xff000000;
                if (!out.contains(c)) out.add(c);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    private void saveHistoryPrefs() {
        prefs().edit()
                .putString(PREF_HISTORY, joinHex(history))
                .putString(PREF_PINNED, joinHex(pinned))
                .apply();
    }

    private static String joinHex(java.util.Collection<Integer> items) {
        StringBuilder sb = new StringBuilder();
        for (int c : items) {
            if (sb.length() > 0) sb.append(',');
            sb.append(formatHex(c));
        }
        return sb.toString();
    }

    /** Keeps index 0 as the latest picked color; inside dropdown (index 1..), pinned items come first. */
    private void orderDropdownWithPinnedFirst() {
        if (history.size() <= 2) return;
        int latest = history.get(0);
        List<Integer> pinnedRest = new ArrayList<>();
        List<Integer> unpinnedRest = new ArrayList<>();
        for (int c : pinned) {
            if (c != latest && history.contains(c)) pinnedRest.add(c);
        }
        for (int i = 1; i < history.size(); i++) {
            int c = history.get(i);
            if (!pinned.contains(c)) unpinnedRest.add(c);
        }
        history.clear();
        history.add(latest);
        history.addAll(pinnedRest);
        history.addAll(unpinnedRest);
    }

    private void recordHistory(int color) {
        int opaque = color | 0xff000000;
        history.remove(Integer.valueOf(opaque));
        history.add(0, opaque);
        orderDropdownWithPinnedFirst();
        trimHistory();
        saveHistoryPrefs();
        renderHistory();
        clampToolbarToScreen();
    }

    private void togglePin(int color) {
        int opaque = color | 0xff000000;
        // Record old Y positions of dropdown rows for smooth FLIP reorder animation
        Map<Integer, Integer> oldTops = new HashMap<>();
        if (historyExpanded) {
            for (int i = 0; i < historyDropdown.getChildCount(); i++) {
                View child = historyDropdown.getChildAt(i);
                Object tag = child.getTag();
                if (tag instanceof Integer) {
                    oldTops.put((Integer) tag, child.getTop());
                }
            }
        }
        if (pinned.contains(opaque)) {
            pinned.remove(opaque);
        } else {
            List<Integer> prevPinned = new ArrayList<>(pinned);
            pinned.clear();
            pinned.add(opaque);
            for (int c : prevPinned) {
                if (pinned.size() < MAX_HISTORY) pinned.add(c);
            }
        }
        orderDropdownWithPinnedFirst();
        trimHistory();
        saveHistoryPrefs();
        renderHistory();

        if (!oldTops.isEmpty() && historyExpanded) {
            PathInterpolator ease = new PathInterpolator(0.22f, 1f, 0.36f, 1f);
            historyDropdown.getViewTreeObserver().addOnPreDrawListener(
                    new android.view.ViewTreeObserver.OnPreDrawListener() {
                        @Override public boolean onPreDraw() {
                            historyDropdown.getViewTreeObserver().removeOnPreDrawListener(this);
                            for (int i = 0; i < historyDropdown.getChildCount(); i++) {
                                View child = historyDropdown.getChildAt(i);
                                Object tag = child.getTag();
                                if (!(tag instanceof Integer)) continue;
                                Integer prevTop = oldTops.get(tag);
                                if (prevTop == null) continue;
                                int dy = prevTop - child.getTop();
                                if (dy != 0) {
                                    child.setTranslationY(dy);
                                    child.animate().cancel();
                                    child.animate()
                                            .translationY(0f)
                                            .setDuration(240)
                                            .setInterpolator(ease)
                                            .start();
                                } else if ((Integer) tag == opaque) {
                                    // Subtle pop settle on the pinned row if it stayed in place
                                    child.setScaleX(0.96f);
                                    child.setScaleY(0.96f);
                                    child.animate().cancel();
                                    child.animate()
                                            .scaleX(1f).scaleY(1f)
                                            .setDuration(180)
                                            .setInterpolator(ease)
                                            .start();
                                }
                            }
                            return true;
                        }
                    });
        }
    }

    private void trimHistory() {
        while (history.size() > MAX_HISTORY) {
            int removeIdx = -1;
            for (int i = history.size() - 1; i >= 1; i--) {
                if (!pinned.contains(history.get(i))) {
                    removeIdx = i;
                    break;
                }
            }
            if (removeIdx < 0) removeIdx = history.size() - 1;
            int removed = history.remove(removeIdx);
            pinned.remove(removed);
        }
    }

    private void attachHistoryForcePin(View row, int color, String hex) {
        final long fingerHoldMs = 400L;
        final float slop = dp(10);
        final float[] down = new float[2];
        final float[] smoothed = {0f};
        final boolean[] isStylus = {false};
        final boolean[] fired = {false};
        final boolean[] cancelled = {false};
        final PathInterpolator smoothEase = new PathInterpolator(0.22f, 1f, 0.36f, 1f);
        final Runnable triggerPin = () -> {
            if (fired[0] || cancelled[0]) return;
            fired[0] = true;
            row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            row.animate().cancel();
            row.animate().scaleX(0.96f).scaleY(0.96f).translationZ(0f).setDuration(90)
                    .setInterpolator(smoothEase)
                    .withEndAction(() -> togglePin(color))
                    .start();
        };
        row.setOnTouchListener((v, ev) -> {
            int act = ev.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN) {
                down[0] = ev.getRawX();
                down[1] = ev.getRawY();
                smoothed[0] = 0f;
                isStylus[0] = PenInput.isPen(ev, 0);
                fired[0] = false;
                cancelled[0] = false;
                row.setPivotX(row.getWidth() * 0.5f);
                row.setPivotY(row.getHeight() * 0.5f);
                row.removeCallbacks(triggerPin);
                row.animate().cancel();
                if (isStylus[0]) {
                    float p = ev.getPressure();
                    if (p >= PRESSURE_PIN_THRESHOLD) {
                        DiagLog.log("ColorPicker", "Stylus force-pin down pressure=" + p);
                        triggerPin.run();
                    } else {
                        float target = Math.max(0f, Math.min(1f, (p - 0.15f) / (PRESSURE_PIN_THRESHOLD - 0.15f)));
                        smoothed[0] = target * 0.35f;
                        float s = 1f + 0.055f * smoothed[0];
                        row.setTranslationZ(dp(5) * smoothed[0]);
                        row.setScaleX(s);
                        row.setScaleY(s);
                    }
                } else {
                    row.postDelayed(triggerPin, fingerHoldMs);
                    row.setTranslationZ(dp(5));
                    row.animate().scaleX(1.055f).scaleY(1.055f).setDuration(fingerHoldMs)
                            .setInterpolator(new DecelerateInterpolator(1.6f))
                            .start();
                }
                return true;
            } else if (act == MotionEvent.ACTION_MOVE) {
                if (fired[0] || cancelled[0]) return true;
                if (Math.hypot(ev.getRawX() - down[0], ev.getRawY() - down[1]) > slop) {
                    cancelled[0] = true;
                    row.removeCallbacks(triggerPin);
                    row.animate().cancel();
                    row.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(140)
                            .setInterpolator(smoothEase).start();
                    return true;
                }
                if (isStylus[0]) {
                    float p = ev.getPressure();
                    if (p >= PRESSURE_PIN_THRESHOLD) {
                        DiagLog.log("ColorPicker", "Stylus force-pin move pressure=" + p);
                        triggerPin.run();
                    } else {
                        float target = Math.max(0f, Math.min(1f, (p - 0.15f) / (PRESSURE_PIN_THRESHOLD - 0.15f)));
                        // Exponential smoothing removes raw stylus pressure micro-jitter
                        smoothed[0] += (target - smoothed[0]) * 0.26f;
                        float s = 1f + 0.055f * smoothed[0];
                        row.setTranslationZ(dp(5) * smoothed[0]);
                        row.setScaleX(s);
                        row.setScaleY(s);
                    }
                }
                return true;
            } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                row.removeCallbacks(triggerPin);
                if (!fired[0]) {
                    row.animate().cancel();
                    row.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(140)
                            .setInterpolator(smoothEase).start();
                    if (act == MotionEvent.ACTION_UP && !cancelled[0]) {
                        copyColor(color, hex, false);
                    }
                }
                return true;
            }
            return false;
        });
    }

    private void renderHistory() {
        if (history.isEmpty()) {
            historyDivider.setVisibility(GONE);
            historyTopRow.setVisibility(GONE);
            historyDropdown.setVisibility(GONE);
            return;
        }
        historyDivider.setVisibility(VISIBLE);
        historyTopRow.setVisibility(VISIBLE);

        final int firstColor = history.get(0);
        final String firstHex = formatHex(firstColor);
        boolean firstPinned = pinned.contains(firstColor);
        historyTopBox.setBackground(Ui.border(getContext(), firstColor, 6, colors.border));
        historyTopCode.setText(firstHex);
        historyTopRgb.setText(formatRgb(firstColor));
        historyTopPin.setVisibility(firstPinned ? VISIBLE : GONE);
        historyTopItem.setBackground(Ui.pressable(getContext(),
                firstPinned ? colors.accentWash : colors.rail, 10,
                firstPinned ? withAlpha(colors.accent, 110) : 0x00000000));
        attachHistoryForcePin(historyTopItem, firstColor, firstHex);

        int rest = history.size() - 1;
        if (rest <= 0) {
            historyExpanded = false;
            historyToggleBtn.setVisibility(GONE);
            historyDropdown.setVisibility(GONE);
            return;
        }
        historyToggleBtn.setVisibility(VISIBLE);
        historyToggleBtn.setText(historyExpanded ? "▴" : ("▾ " + history.size()));

        historyDropdown.removeAllViews();
        if (!historyExpanded) {
            historyDropdown.setVisibility(GONE);
            return;
        }
        historyDropdown.setVisibility(VISIBLE);
        Context ctx = getContext();
        for (int i = 1; i < history.size(); i++) {
            final int color = history.get(i);
            final String hex = formatHex(color);
            final boolean isPinned = pinned.contains(color);

            LinearLayout row = new LinearLayout(ctx);
            row.setTag(color);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(6), dp(5), dp(10), dp(5));
            row.setBackground(Ui.pressable(ctx,
                    isPinned ? colors.accentWash : colors.rail, 10,
                    isPinned ? withAlpha(colors.accent, 110) : 0x00000000));
            attachHistoryForcePin(row, color, hex);

            View box = new View(ctx);
            box.setBackground(Ui.border(ctx, color, 6, colors.border));
            row.addView(box, new LinearLayout.LayoutParams(dp(18), dp(18)));

            TextView code = Ui.text(ctx, hex, 12, colors.text, true);
            code.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
            cp.setMarginStart(dp(8));
            row.addView(code, cp);

            TextView rgb = Ui.text(ctx, formatRgb(color), 11, colors.muted, false);
            rgb.setTypeface(Typeface.MONOSPACE);
            LinearLayout.LayoutParams rpText = new LinearLayout.LayoutParams(0, -2, 1f);
            rpText.setMarginStart(dp(8));
            row.addView(rgb, rpText);

            if (isPinned) {
                ImageView pinIcon = new ImageView(ctx);
                pinIcon.setImageResource(R.drawable.ic_pin);
                pinIcon.setImageTintList(ColorStateList.valueOf(colors.accent));
                LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(14), dp(14));
                pp.setMarginStart(dp(6));
                row.addView(pinIcon, pp);
            }

            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            if (i > 1) rp.topMargin = dp(4);
            historyDropdown.addView(row, rp);
        }
    }

    private void refreshDisplayMetrics() {
        WindowManager wm = getContext().getSystemService(WindowManager.class);
        if (wm != null && Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getCurrentWindowMetrics().getBounds();
            screenW = Math.max(1, b.width());
            screenH = Math.max(1, b.height());
        } else {
            screenW = Math.max(1, getResources().getDisplayMetrics().widthPixels);
            screenH = Math.max(1, getResources().getDisplayMetrics().heightPixels);
        }
    }

    private static String formatHex(int color) {
        return String.format(Locale.US, "#%06X", color & 0xffffff);
    }

    private static String formatRgb(int color) {
        return "rgb(" + Color.red(color) + ", " + Color.green(color) + ", " + Color.blue(color) + ")";
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) host.onClose();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private final class LoupeCanvas extends View {
        private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint pixelPaint = new Paint();
        private final Paint gridPaint = new Paint();
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect srcRect = new Rect();
        private final RectF dstRect = new RectF();
        private final RectF fullScreen = new RectF();
        private final Path clipCircle = new Path();

        LoupeCanvas(Context context) {
            super(context);
            pixelPaint.setFilterBitmap(false);
            pixelPaint.setAntiAlias(false);
            gridPaint.setStyle(Paint.Style.STROKE);
            gridPaint.setStrokeWidth(1f);
            gridPaint.setColor(0x33000000);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(dp(8));
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(dp(2));
            labelBgPaint.setColor(0xd9101522);
            labelTextPaint.setColor(0xffffffff);
            labelTextPaint.setTextSize(dp(12));
            labelTextPaint.setTypeface(Typeface.MONOSPACE);
            labelTextPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            fullScreen.set(0, 0, w, h);
        }

        @Override public boolean onHoverEvent(MotionEvent event) {
            if (paused) return false;
            int act = event.getActionMasked();
            if (act == MotionEvent.ACTION_HOVER_ENTER || act == MotionEvent.ACTION_HOVER_MOVE) {
                updateSample(event.getX(), event.getY());
                return true;
            }
            return super.onHoverEvent(event);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (paused) return false;
            int act = event.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_MOVE) {
                updateSample(event.getX(), event.getY());
                return true;
            } else if (act == MotionEvent.ACTION_UP) {
                updateSample(event.getX(), event.getY());
                copyColor(currentColor, formatHex(currentColor), true);
                return true;
            }
            return super.onTouchEvent(event);
        }

        @Override protected void onDraw(Canvas c) {
            if (paused || screenshot == null || screenshot.isRecycled()) return;
            c.drawBitmap(screenshot, null, fullScreen, bmpPaint);
            if (!hasSample) return;

            int vw = getWidth();
            int vh = getHeight();
            int bw = screenshot.getWidth();
            int bh = screenshot.getHeight();
            int bx = Math.max(0, Math.min(bw - 1, Math.round(sampleX * bw / (float) vw)));
            int by = Math.max(0, Math.min(bh - 1, Math.round(sampleY * bh / (float) vh)));

            float radius = dp(62);
            float offset = dp(86);
            float cx = Math.max(radius + dp(12), Math.min(vw - radius - dp(12), sampleX));
            float cy = (sampleY - offset - radius > dp(88)) ? (sampleY - offset) : (sampleY + offset);
            cy = Math.max(radius + dp(12), Math.min(vh - radius - dp(12), cy));

            // Crosshair at exact sample point
            borderPaint.setColor(0xcc000000);
            c.drawCircle(sampleX, sampleY, dp(5), borderPaint);
            borderPaint.setColor(0xffffffff);
            c.drawCircle(sampleX, sampleY, dp(3.5f), borderPaint);

            // 11x11 zoomed pixel grid inside circular loupe
            int halfCells = 5;
            int cells = halfCells * 2 + 1;
            srcRect.set(
                    Math.max(0, bx - halfCells),
                    Math.max(0, by - halfCells),
                    Math.min(bw, bx + halfCells + 1),
                    Math.min(bh, by + halfCells + 1));
            dstRect.set(cx - radius, cy - radius, cx + radius, cy + radius);

            int save = c.save();
            clipCircle.reset();
            clipCircle.addCircle(cx, cy, radius, Path.Direction.CW);
            c.clipPath(clipCircle);
            c.drawBitmap(screenshot, srcRect, dstRect, pixelPaint);

            float cellSize = (radius * 2f) / cells;
            for (int i = 1; i < cells; i++) {
                float gx = dstRect.left + i * cellSize;
                float gy = dstRect.top + i * cellSize;
                c.drawLine(gx, dstRect.top, gx, dstRect.bottom, gridPaint);
                c.drawLine(dstRect.left, gy, dstRect.right, gy, gridPaint);
            }
            float cellL = cx - cellSize * 0.5f;
            float cellT = cy - cellSize * 0.5f;
            borderPaint.setColor(0xff000000);
            c.drawRect(cellL - 1, cellT - 1, cellL + cellSize + 1, cellT + cellSize + 1, borderPaint);
            borderPaint.setColor(0xffffffff);
            c.drawRect(cellL, cellT, cellL + cellSize, cellT + cellSize, borderPaint);
            c.restoreToCount(save);

            // Outer sampled-color ring + crisp rim
            ringPaint.setColor(currentColor);
            c.drawCircle(cx, cy, radius + dp(4), ringPaint);
            borderPaint.setColor(0xffffffff);
            c.drawCircle(cx, cy, radius + dp(8), borderPaint);
            borderPaint.setColor(0x55000000);
            c.drawCircle(cx, cy, radius + dp(9), borderPaint);

            // Compact HEX pill under the loupe
            String hex = formatHex(currentColor);
            float pillW = dp(76);
            float pillH = dp(24);
            float pillTop = cy + radius + dp(14);
            if (pillTop + pillH > vh - dp(8)) pillTop = cy - radius - dp(14) - pillH;
            RectF pill = new RectF(cx - pillW * 0.5f, pillTop, cx + pillW * 0.5f, pillTop + pillH);
            c.drawRoundRect(pill, dp(10), dp(10), labelBgPaint);
            c.drawText(hex, cx, pill.centerY() + dp(4), labelTextPaint);
        }
    }

    private int dp(float v) { return Ui.dp(getContext(), v); }
    private static int withAlpha(int color, int alpha) {
        return (color & 0x00ffffff) | ((alpha & 255) << 24);
    }
}
