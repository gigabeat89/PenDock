package th.pendock;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Full-screen floating translation overlay supporting 3 stylus-first modes:
 * 1. Box drag translate (drag a region -> floating translation card)
 * 2. Point / Hover translate (hover M-Pencil or tap any word/line -> instant tooltip)
 * 3. Full-screen in-place overlay (translated blocks placed cleanly over original text)
 */
final class TranslateOverlay extends FrameLayout {
    interface Host {
        void onPause();
        void onMoveToolbar(int windowX, int windowY);
        void onRequestResume();
        void onClose();
    }

    private static final String MODE_BOX = "box";
    private static final String MODE_POINT = "point";
    private static final String MODE_FULL = "full";
    private static final float TOOLBAR_ALPHA = 0.95f;

    private static final class FullBlock {
        final ScreenOcrEngine.TextBlock block;
        final String translated;
        final boolean darkBg;

        FullBlock(ScreenOcrEngine.TextBlock block, String translated, boolean darkBg) {
            this.block = block;
            this.translated = translated;
            this.darkBg = darkBg;
        }
    }

    private final Appearance.Palette colors;
    private Bitmap screenshot;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final int[] screenOrigin = new int[2];

    private final StageCanvas stage;
    private final FrameLayout toolbarWindowHost;
    private final int shadowPad;
    private final LinearLayout toolbarContainer;
    private final LinearLayout toolbar;
    private final LinearLayout collapsedPill;
    private final TextView collapsedLabel;
    private final TextView collapsedPauseBtn;
    private final TextView edgeTab;
    private final LinearLayout pointSubBar;
    private final TextView wordSubBtn, lineSubBtn, pointSubHint;
    private final TextView statusSubPill;
    private final TextView boxTab, pointTab, fullTab;
    private final TextView packPill, targetPill, peekBtn, pauseBtn;
    private final LinearLayout resultCard;
    private final android.widget.ScrollView resultScroll;
    private final TextView resultTranslated, resultOriginal, resultCopyBtn, resultSpeakBtn;
    private final FrameLayout modalLayer;

    private String mode;
    private OcrModelManager.Pack activePack;
    private String targetLang;
    private boolean wordUnit = true;
    private boolean toolbarCollapsed;
    private boolean paused;
    private int edgeDock; // -1 = left edge, 0 = free, +1 = right edge
    private float barX, barY;
    private boolean toolbarPositioned;
    private boolean peekingOriginal;
    private boolean closed;

    // Box selection state
    private boolean draggingBox;
    private float dragStartX, dragStartY;
    private final RectF activeBox = new RectF();

    // Cached full-screen scan state for current screenshot + activePack
    private OcrModelManager.Pack scannedPack;
    private List<ScreenOcrEngine.TextLine> scannedLines;
    private List<FullBlock> fullBlocks = Collections.emptyList();
    private boolean scanning;
    private int scanGeneration;
    private Future<?> activeTask;

    // Point / hover state
    private Rect hoveredRect;
    private String hoveredSourceText;
    private Runnable pendingHoverTranslate;

    // Last displayed result (for Copy / TTS)
    private String currentTranslation = "";
    private String currentOriginal = "";
    private Rect currentAnchorRect;

    private TextToSpeech tts;
    private boolean ttsReady;

    TranslateOverlay(Context context, Bitmap screenshot, Host host) {
        super(context);
        this.colors = Ui.colors(context);
        this.screenshot = screenshot;
        this.host = host;
        this.mode = OcrModelManager.lastMode(context);
        this.activePack = OcrModelManager.selectedPack(context);
        this.targetLang = OcrModelManager.targetLang(context);

        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);

        stage = new StageCanvas(context);
        addView(stage, new LayoutParams(-1, -1));

        // Persistent floating toolbar window host with shadow padding so elevation shadows never clip
        shadowPad = dp(14);
        toolbarWindowHost = new FrameLayout(context);
        toolbarWindowHost.setPadding(shadowPad, shadowPad, shadowPad, shadowPad);
        toolbarWindowHost.setClipChildren(false);
        toolbarWindowHost.setClipToPadding(false);

        toolbarContainer = Ui.column(context);
        toolbarContainer.setGravity(Gravity.START);
        toolbarContainer.setClipChildren(false);
        toolbarContainer.setClipToPadding(false);
        toolbarContainer.setAlpha(TOOLBAR_ALPHA);

        toolbar = new LinearLayout(context);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), dp(6), dp(8), dp(6));
        toolbar.setBackground(Ui.border(context, withAlpha(colors.surface, 244), 22, colors.border));
        applyShadow(toolbar, 12);
        toolbar.setClickable(true);

        TextView dragHandle = Ui.text(context, "⋮⋮", 14, colors.muted, true);
        dragHandle.setGravity(Gravity.CENTER);
        dragHandle.setPadding(dp(6), dp(4), dp(8), dp(4));
        dragHandle.setMinHeight(dp(34));
        attachToolbarDrag(dragHandle, false, false);
        toolbar.addView(dragHandle, barItemParams(0));

        boxTab = modeButton(context.getString(R.string.translate_mode_box), () -> setMode(MODE_BOX));
        pointTab = modeButton(context.getString(R.string.translate_mode_point), () -> setMode(MODE_POINT));
        fullTab = modeButton(context.getString(R.string.translate_mode_full), () -> setMode(MODE_FULL));
        toolbar.addView(boxTab, barItemParams(0));
        toolbar.addView(pointTab, barItemParams(4));
        toolbar.addView(fullTab, barItemParams(4));

        View sep = new View(context);
        sep.setBackgroundColor(colors.border);
        LinearLayout.LayoutParams sepP = new LinearLayout.LayoutParams(dp(1), dp(22));
        sepP.setMarginStart(dp(8));
        sepP.setMarginEnd(dp(8));
        toolbar.addView(sep, sepP);

        packPill = chipButton(activePack.badge, this::showPackPicker);
        toolbar.addView(packPill, barItemParams(0));

        TextView swapBtn = Ui.text(context, "⇄", 15, colors.accent, true);
        swapBtn.setGravity(Gravity.CENTER);
        swapBtn.setMinWidth(dp(32));
        swapBtn.setMinHeight(dp(32));
        swapBtn.setPadding(dp(6), dp(2), dp(6), dp(2));
        swapBtn.setBackground(Ui.pressable(context, Color.TRANSPARENT, 10, Color.TRANSPARENT));
        swapBtn.setOnClickListener(v -> swapLanguages());
        toolbar.addView(swapBtn, barItemParams(2));

        targetPill = chipButton(GoogleTranslator.targetBadge(targetLang), this::cycleTargetLanguage);
        toolbar.addView(targetPill, barItemParams(2));

        peekBtn = iconPill("👁", context.getString(R.string.translate_peek));
        peekBtn.setOnTouchListener((v, ev) -> {
            int act = ev.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN) {
                peekingOriginal = true;
                stage.invalidate();
                return true;
            } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                peekingOriginal = false;
                stage.invalidate();
                return true;
            }
            return false;
        });
        toolbar.addView(peekBtn, barItemParams(6));

        pauseBtn = iconPill("⏸", context.getString(R.string.translate_pause));
        pauseBtn.setOnClickListener(v -> togglePause());
        toolbar.addView(pauseBtn, barItemParams(4));

        TextView collapseBtn = iconPill("–", context.getString(R.string.translate_tool));
        collapseBtn.setOnClickListener(v -> setToolbarCollapsed(true));
        toolbar.addView(collapseBtn, barItemParams(4));

        TextView closeBtn = iconPill("✕", context.getString(R.string.close));
        closeBtn.setOnClickListener(v -> closeOverlay());
        toolbar.addView(closeBtn, barItemParams(4));

        toolbarContainer.addView(toolbar, new LinearLayout.LayoutParams(-2, -2));

        // Compact collapsed pill (shows mode, spaced language pair, Pause/Resume, Close; drag to edge to tuck)
        collapsedPill = new LinearLayout(context);
        collapsedPill.setOrientation(LinearLayout.HORIZONTAL);
        collapsedPill.setGravity(Gravity.CENTER_VERTICAL);
        collapsedPill.setPadding(dp(12), dp(7), dp(8), dp(7));
        collapsedPill.setBackground(Ui.border(context, withAlpha(colors.surface, 244), 22, colors.accent));
        applyShadow(collapsedPill, 12);
        collapsedPill.setVisibility(GONE);

        TextView collapsedGrip = Ui.text(context, "⋮⋮", 13, colors.muted, true);
        collapsedGrip.setPadding(0, 0, dp(6), 0);
        collapsedPill.addView(collapsedGrip, new LinearLayout.LayoutParams(-2, -2));

        collapsedLabel = Ui.text(context, "", 13, colors.accent, true);
        collapsedLabel.setPadding(0, 0, dp(8), 0);
        collapsedPill.addView(collapsedLabel, new LinearLayout.LayoutParams(-2, -2));

        collapsedPauseBtn = iconPill("⏸", context.getString(R.string.translate_pause));
        collapsedPauseBtn.setMinWidth(dp(28));
        collapsedPauseBtn.setMinHeight(dp(28));
        collapsedPauseBtn.setPadding(dp(6), dp(2), dp(6), dp(2));
        collapsedPauseBtn.setOnClickListener(v -> togglePause());
        collapsedPill.addView(collapsedPauseBtn, new LinearLayout.LayoutParams(-2, -2));

        TextView collapsedClose = iconPill("✕", context.getString(R.string.close));
        collapsedClose.setMinWidth(dp(28));
        collapsedClose.setMinHeight(dp(28));
        collapsedClose.setPadding(dp(6), dp(2), dp(6), dp(2));
        collapsedClose.setOnClickListener(v -> closeOverlay());
        LinearLayout.LayoutParams closeP = new LinearLayout.LayoutParams(-2, -2);
        closeP.setMarginStart(dp(4));
        collapsedPill.addView(collapsedClose, closeP);

        attachToolbarDrag(collapsedPill, true, false);
        toolbarContainer.addView(collapsedPill, new LinearLayout.LayoutParams(-2, -2));

        // Sleek side-edge handle shown when the collapsed pill is docked against the left or right edge
        edgeTab = Ui.text(context, "›", 18, colors.accent, true);
        edgeTab.setGravity(Gravity.CENTER);
        edgeTab.setMinWidth(dp(26));
        edgeTab.setMinHeight(dp(56));
        edgeTab.setPadding(dp(6), dp(12), dp(6), dp(12));
        edgeTab.setBackground(Ui.border(context, withAlpha(colors.surface, 244), 14, colors.accent));
        applyShadow(edgeTab, 12);
        edgeTab.setVisibility(GONE);
        attachToolbarDrag(edgeTab, false, true);
        toolbarContainer.addView(edgeTab, new LinearLayout.LayoutParams(-2, -2));

        // Sub-button bar hanging neatly below the Point tab (Word / Line + inline hover hint)
        pointSubBar = new LinearLayout(context);
        pointSubBar.setOrientation(LinearLayout.HORIZONTAL);
        pointSubBar.setGravity(Gravity.CENTER_VERTICAL);
        pointSubBar.setPadding(dp(6), dp(4), dp(10), dp(4));
        pointSubBar.setBackground(Ui.border(context, withAlpha(colors.surface, 238), 16, colors.border));
        applyShadow(pointSubBar, 10);
        pointSubBar.setClickable(true);

        wordSubBtn = subModeButton(context.getString(R.string.translate_unit_word), () -> setWordUnit(true));
        lineSubBtn = subModeButton(context.getString(R.string.translate_unit_line), () -> setWordUnit(false));
        pointSubBar.addView(wordSubBtn, barItemParams(0));
        pointSubBar.addView(lineSubBtn, barItemParams(4));

        pointSubHint = Ui.text(context, "", 12, colors.muted, false);
        pointSubHint.setPadding(dp(8), 0, dp(2), 0);
        pointSubBar.addView(pointSubHint, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout.LayoutParams subP = new LinearLayout.LayoutParams(-2, -2);
        subP.topMargin = dp(6);
        subP.setMarginStart(dp(44));
        toolbarContainer.addView(pointSubBar, subP);

        // Compact status badge tucked under toolbar only while actively scanning/translating
        statusSubPill = Ui.text(context, "", 12, colors.accent, true);
        statusSubPill.setPadding(dp(12), dp(5), dp(12), dp(5));
        statusSubPill.setBackground(Ui.border(context, withAlpha(colors.surface, 238), 14, colors.border));
        applyShadow(statusSubPill, 8);
        statusSubPill.setVisibility(GONE);
        LinearLayout.LayoutParams statusSubP = new LinearLayout.LayoutParams(-2, -2);
        statusSubP.topMargin = dp(6);
        toolbarContainer.addView(statusSubPill, statusSubP);

        toolbarWindowHost.addView(toolbarContainer, new LayoutParams(-2, -2));

        // Floating translation result card (scrollable body for long text + pinned Copy/Listen actions)
        resultCard = Ui.column(context);
        int pad = dp(14);
        resultCard.setPadding(pad, dp(12), pad, dp(12));
        resultCard.setBackground(Ui.border(context, withAlpha(colors.surface, 246), 16, colors.border));
        applyShadow(resultCard, 14);
        resultCard.setClickable(true);
        resultCard.setVisibility(GONE);

        resultScroll = new android.widget.ScrollView(context);
        resultScroll.setVerticalScrollBarEnabled(true);
        LinearLayout scrollBody = Ui.column(context);

        resultTranslated = Ui.text(context, "", 16, colors.text, true);
        resultTranslated.setLineSpacing(dp(2), 1.0f);
        scrollBody.addView(resultTranslated, new LinearLayout.LayoutParams(-1, -2));

        resultOriginal = Ui.text(context, "", 12, colors.muted, false);
        resultOriginal.setLineSpacing(dp(1.5f), 1.0f);
        LinearLayout.LayoutParams origP = new LinearLayout.LayoutParams(-1, -2);
        origP.topMargin = dp(8);
        scrollBody.addView(resultOriginal, origP);

        resultScroll.addView(scrollBody, new android.widget.ScrollView.LayoutParams(-1, -2));
        resultCard.addView(resultScroll, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout cardActions = new LinearLayout(context);
        cardActions.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        LinearLayout.LayoutParams actP = new LinearLayout.LayoutParams(-1, -2);
        actP.topMargin = dp(10);
        resultCopyBtn = smallAction(context.getString(R.string.translate_copy), this::copyCurrentTranslation);
        resultSpeakBtn = smallAction(context.getString(R.string.translate_listen), this::speakCurrentTranslation);
        cardActions.addView(resultCopyBtn, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams spkP = new LinearLayout.LayoutParams(-2, -2);
        spkP.setMarginStart(dp(6));
        cardActions.addView(resultSpeakBtn, spkP);
        resultCard.addView(cardActions, actP);

        int cardW = Math.min(dp(440), getResources().getDisplayMetrics().widthPixels - dp(32));
        addView(resultCard, new LayoutParams(cardW, -2, Gravity.TOP | Gravity.START));

        // Modal layer for first-time On-Demand OCR download or switching packs
        modalLayer = new FrameLayout(context);
        modalLayer.setBackgroundColor(0x88000000);
        modalLayer.setClickable(true);
        modalLayer.setVisibility(GONE);
        addView(modalLayer, new LayoutParams(-1, -1));

        updateToolbarUi();

        post(() -> {
            if (closed) return;
            centerToolbarInitial();
            if (!OcrModelManager.anyPackReady(getContext())) {
                showPackDownloadModal(true);
            } else {
                enterActiveMode();
            }
        });
    }

    View toolbarWindowView() {
        return toolbarWindowHost;
    }

    int initialToolbarWindowX() {
        toolbarWindowHost.measure(MeasureSpec.makeMeasureSpec(screenW(), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(screenH(), MeasureSpec.AT_MOST));
        int barW = Math.max(dp(240), toolbarWindowHost.getMeasuredWidth() - shadowPad * 2);
        barX = Math.max(dp(12), (screenW() - barW) * 0.5f);
        barY = dp(42);
        toolbarPositioned = true;
        return Math.round(barX) - shadowPad;
    }

    int initialToolbarWindowY() {
        return Math.round(barY) - shadowPad;
    }

    private void applyShadow(View view, float elevationDp) {
        view.setElevation(dp(elevationDp));
        view.setOutlineAmbientShadowColor(colors.dark ? 0xaa000000 : 0x55000000);
        view.setOutlineSpotShadowColor(colors.dark ? 0xcc000000 : 0x66000000);
    }

    private void centerToolbarInitial() {
        if (toolbarPositioned) return;
        toolbarPositioned = true;
        int w = toolbarContainer.getWidth();
        barX = Math.max(dp(12), (screenW() - w) * 0.5f);
        barY = dp(42);
        applyToolbarPosition();
    }

    private int screenW() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private int screenH() {
        return getResources().getDisplayMetrics().heightPixels;
    }

    private void attachToolbarDrag(View handle, boolean tapToExpand, boolean tapToUntuck) {
        final float[] downRaw = new float[2];
        final float[] startPos = new float[2];
        final boolean[] moved = {false};
        final float slop = dp(6);
        handle.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRaw[0] = ev.getRawX();
                    downRaw[1] = ev.getRawY();
                    startPos[0] = barX;
                    startPos[1] = barY;
                    moved[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = ev.getRawX() - downRaw[0];
                    float dy = ev.getRawY() - downRaw[1];
                    if (!moved[0] && Math.hypot(dx, dy) > slop) {
                        moved[0] = true;
                        if (edgeDock != 0) {
                            edgeDock = 0;
                            updateToolbarUi();
                        }
                    }
                    if (moved[0]) {
                        moveToolbar(startPos[0] + dx, startPos[1] + dy);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved[0]) {
                        if (tapToUntuck) {
                            untuckFromEdge();
                        } else if (tapToExpand) {
                            setToolbarCollapsed(false);
                        }
                    } else {
                        maybeDockToEdge();
                    }
                    return true;
                default:
                    return false;
            }
        });
    }

    private void moveToolbar(float desiredX, float desiredY) {
        int sw = screenW();
        int sh = screenH();
        int barW = Math.max(dp(26), toolbarContainer.getWidth());
        int barH = Math.max(dp(36), toolbarContainer.getHeight());
        int minX = edgeDock != 0 ? 0 : dp(8);
        int maxX = edgeDock != 0 ? Math.max(0, sw - barW) : Math.max(minX, sw - barW - dp(8));
        int minY = dp(28);
        int maxY = Math.max(minY, sh - barH - dp(10));
        barX = Math.max(minX, Math.min(maxX, desiredX));
        barY = Math.max(minY, Math.min(maxY, desiredY));
        applyToolbarPosition();
    }

    private void applyToolbarPosition() {
        if (host != null) {
            host.onMoveToolbar(Math.round(barX) - shadowPad, Math.round(barY) - shadowPad);
        }
    }

    private Rect toolbarScreenBounds() {
        int l = Math.round(barX);
        int t = Math.round(barY);
        int w = Math.max(dp(26), toolbarContainer.getWidth());
        int h = Math.max(dp(36), toolbarContainer.getHeight());
        return new Rect(l - dp(4), t - dp(4), l + w + dp(4), t + h + dp(4));
    }

    private void maybeDockToEdge() {
        int sw = screenW();
        int barW = toolbarContainer.getWidth();
        int snapDist = dp(28);
        if (barX <= snapDist) {
            edgeDock = -1;
            updateToolbarUi();
            post(() -> {
                barX = 0f;
                applyToolbarPosition();
            });
        } else if (barX + barW >= sw - snapDist) {
            edgeDock = 1;
            updateToolbarUi();
            post(() -> {
                int tabW = Math.max(dp(26), edgeTab.getWidth());
                barX = Math.max(0f, sw - tabW);
                applyToolbarPosition();
            });
        }
    }

    private void untuckFromEdge() {
        int side = edgeDock;
        edgeDock = 0;
        updateToolbarUi();
        post(() -> {
            int sw = screenW();
            View activeBar = toolbarCollapsed ? collapsedPill : toolbar;
            int activeW = Math.max(dp(180), activeBar.getWidth());
            float targetX = side > 0 ? Math.max(dp(10), sw - activeW - dp(10)) : dp(10);
            moveToolbar(targetX, barY);
        });
    }

    void pauseIfActive() {
        if (!paused && !closed) togglePause();
    }

    void onDisplayRotated() {
        post(() -> {
            if (closed) return;
            if (edgeDock > 0) {
                int tabW = Math.max(dp(26), edgeTab.getWidth());
                barX = Math.max(0f, screenW() - tabW);
            } else if (edgeDock < 0) {
                barX = 0f;
            }
            moveToolbar(barX, barY);
        });
    }

    private void setToolbarCollapsed(boolean collapsed) {
        if (toolbarCollapsed == collapsed) return;
        int sw = screenW();
        int sh = screenH();
        float oldW = toolbarContainer.getWidth();
        float oldH = toolbarContainer.getHeight();
        float oldRight = barX + oldW;
        float oldBottom = barY + oldH;
        boolean anchorRight = oldW > 0 && (barX + oldW * 0.5f) > sw * 0.5f;
        boolean anchorBottom = oldH > 0 && (barY + oldH * 0.5f) > sh * 0.5f;

        toolbarCollapsed = collapsed;
        if (!collapsed) edgeDock = 0;
        updateToolbarUi();
        post(() -> {
            int curSw = screenW();
            View visibleBar = collapsed ? collapsedPill : toolbar;
            int newW = Math.max(dp(120), visibleBar.getWidth());
            int newH = Math.max(dp(36), toolbarContainer.getHeight());
            float targetX = anchorRight ? (oldRight - newW) : barX;
            float targetY = anchorBottom ? (oldBottom - newH) : barY;
            if (collapsed) {
                if (targetX <= dp(24)) {
                    edgeDock = -1;
                    updateToolbarUi();
                    post(() -> {
                        barX = 0f;
                        barY = targetY;
                        applyToolbarPosition();
                    });
                    return;
                } else if (targetX + newW >= curSw - dp(24)) {
                    edgeDock = 1;
                    updateToolbarUi();
                    post(() -> {
                        int tabW = Math.max(dp(26), edgeTab.getWidth());
                        barX = Math.max(0f, curSw - tabW);
                        barY = targetY;
                        applyToolbarPosition();
                    });
                    return;
                }
            }
            moveToolbar(targetX, targetY);
        });
    }

    private void togglePause() {
        if (paused) {
            if (host != null) host.onRequestResume();
            return;
        }
        paused = true;
        cancelPendingWork();
        hideResultCard();
        modalLayer.setVisibility(GONE);
        stage.setVisibility(GONE);
        activeBox.setEmpty();
        hoveredRect = null;
        hoveredSourceText = null;
        peekingOriginal = false;
        updateToolbarUi();
        if (host != null) host.onPause();
    }

    void resumeWithFrame(Bitmap freshFrame) {
        if (closed) {
            if (freshFrame != null && !freshFrame.isRecycled()) freshFrame.recycle();
            return;
        }
        if (screenshot != null && !screenshot.isRecycled()) screenshot.recycle();
        screenshot = freshFrame;
        scannedPack = null;
        scannedLines = null;
        fullBlocks = Collections.emptyList();
        activeBox.setEmpty();
        hoveredRect = null;
        hoveredSourceText = null;
        peekingOriginal = false;
        paused = false;
        stage.setVisibility(VISIBLE);
        updateToolbarUi();
        post(() -> {
            if (closed) return;
            moveToolbar(barX, barY);
            if (!OcrModelManager.anyPackReady(getContext())) {
                showPackDownloadModal(true);
            } else {
                enterActiveMode();
            }
        });
    }

    boolean isPaused() {
        return paused;
    }

    private TextView subModeButton(String label, Runnable onClick) {
        TextView btn = Ui.text(getContext(), label, 12, colors.muted, true);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(10), dp(5), dp(10), dp(5));
        btn.setMinHeight(dp(28));
        btn.setOnClickListener(v -> onClick.run());
        return btn;
    }

    private TextView modeButton(String label, Runnable onClick) {
        TextView btn = Ui.text(getContext(), label, 13, colors.text, true);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(12), dp(7), dp(12), dp(7));
        btn.setMinHeight(dp(34));
        btn.setOnClickListener(v -> onClick.run());
        return btn;
    }

    private TextView chipButton(String label, Runnable onClick) {
        TextView btn = Ui.text(getContext(), label, 12, colors.accent, true);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(10), dp(6), dp(10), dp(6));
        btn.setMinHeight(dp(32));
        btn.setBackground(Ui.pressable(getContext(), colors.accentWash, 12, withAlpha(colors.accent, 90)));
        btn.setOnClickListener(v -> onClick.run());
        return btn;
    }

    private TextView iconPill(String symbol, String desc) {
        TextView btn = Ui.text(getContext(), symbol, 15, colors.text, true);
        btn.setGravity(Gravity.CENTER);
        btn.setMinWidth(dp(34));
        btn.setMinHeight(dp(34));
        btn.setPadding(dp(8), dp(4), dp(8), dp(4));
        btn.setContentDescription(desc);
        btn.setTooltipText(desc);
        btn.setBackground(Ui.pressable(getContext(), colors.rail, 12, colors.border));
        return btn;
    }

    private TextView smallAction(String label, Runnable onClick) {
        TextView btn = Ui.text(getContext(), label, 12, colors.accent, true);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(12), dp(6), dp(12), dp(6));
        btn.setBackground(Ui.pressable(getContext(), colors.accentWash, 10, Color.TRANSPARENT));
        btn.setOnClickListener(v -> onClick.run());
        return btn;
    }

    private LinearLayout.LayoutParams barItemParams(int startDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        if (startDp > 0) lp.setMarginStart(dp(startDp));
        return lp;
    }

    private void updateToolbarUi() {
        boolean tucked = edgeDock != 0;
        toolbar.setVisibility(!toolbarCollapsed && !tucked ? VISIBLE : GONE);
        collapsedPill.setVisibility(toolbarCollapsed && !tucked ? VISIBLE : GONE);
        edgeTab.setVisibility(tucked ? VISIBLE : GONE);
        if (tucked) {
            edgeTab.setText(edgeDock < 0 ? "›" : "‹");
        }

        String modeLabel = MODE_BOX.equals(mode) ? getContext().getString(R.string.translate_mode_box)
                : MODE_POINT.equals(mode) ? getContext().getString(R.string.translate_mode_point)
                : getContext().getString(R.string.translate_mode_full);
        collapsedLabel.setText(modeLabel + "  ·  " + activePack.badge + "  →  " + GoogleTranslator.targetBadge(targetLang));

        applyPauseButtonStyle(pauseBtn);
        applyPauseButtonStyle(collapsedPauseBtn);

        applyTabStyle(boxTab, MODE_BOX.equals(mode));
        applyTabStyle(pointTab, MODE_POINT.equals(mode));
        applyTabStyle(fullTab, MODE_FULL.equals(mode));
        packPill.setText(activePack.badge);
        targetPill.setText(GoogleTranslator.targetBadge(targetLang));
        peekBtn.setVisibility(!paused && MODE_FULL.equals(mode) ? VISIBLE : GONE);

        boolean showPointSub = !toolbarCollapsed && !tucked && !paused && MODE_POINT.equals(mode);
        pointSubBar.setVisibility(showPointSub ? VISIBLE : GONE);
        applySubStyle(wordSubBtn, wordUnit);
        applySubStyle(lineSubBtn, !wordUnit);
        pointSubHint.setText(scanning ? R.string.translate_scanning : R.string.translate_hint_point);

        if (!toolbarCollapsed && !tucked && !paused && !MODE_POINT.equals(mode) && scanning) {
            statusSubPill.setText(R.string.translate_scanning);
            statusSubPill.setVisibility(VISIBLE);
        } else if (toolbarCollapsed || tucked || paused || !scanning) {
            statusSubPill.setVisibility(GONE);
        }
    }

    private void applyPauseButtonStyle(TextView btn) {
        btn.setText(paused ? "▶" : "⏸");
        String desc = getContext().getString(paused ? R.string.translate_resume : R.string.translate_pause);
        btn.setContentDescription(desc);
        btn.setTooltipText(desc);
        btn.setBackground(Ui.pressable(getContext(),
                paused ? colors.accent : colors.rail, 12,
                paused ? colors.accent : colors.border));
        btn.setTextColor(paused ? 0xffffffff : colors.text);
    }

    private void applySubStyle(TextView btn, boolean active) {
        btn.setBackground(Ui.pressable(getContext(),
                active ? colors.accentWash : Color.TRANSPARENT, 10,
                active ? withAlpha(colors.accent, 110) : Color.TRANSPARENT));
        btn.setTextColor(active ? colors.accent : colors.muted);
    }

    private void applyTabStyle(TextView tab, boolean active) {
        tab.setBackground(Ui.pressable(getContext(),
                active ? colors.accent : Color.TRANSPARENT, 14,
                active ? colors.accent : Color.TRANSPARENT));
        tab.setTextColor(active ? 0xffffffff : colors.muted);
    }

    private void setMode(String newMode) {
        if (newMode.equals(mode) && !scanning) return;
        mode = newMode;
        OcrModelManager.setLastMode(getContext(), mode);
        cancelPendingWork();
        hideResultCard();
        activeBox.setEmpty();
        hoveredRect = null;
        hoveredSourceText = null;
        peekingOriginal = false;
        updateToolbarUi();
        stage.invalidate();
        if (!OcrModelManager.isPackReady(getContext(), activePack)) {
            showPackDownloadModal(false);
            return;
        }
        enterActiveMode();
    }

    private void enterActiveMode() {
        updateToolbarUi();
        if (paused) return;
        stage.invalidate();
        if (MODE_POINT.equals(mode)) {
            ensureFullScan(null);
        } else if (MODE_FULL.equals(mode)) {
            ensureFullScan(this::translateAllScannedBlocks);
        }
    }

    private void setWordUnit(boolean useWord) {
        if (wordUnit == useWord) return;
        wordUnit = useWord;
        hoveredRect = null;
        hoveredSourceText = null;
        hideResultCard();
        updateToolbarUi();
        stage.invalidate();
    }

    private void cycleTargetLanguage() {
        targetLang = GoogleTranslator.nextTarget(targetLang);
        OcrModelManager.setTargetLang(getContext(), targetLang);
        updateToolbarUi();
        if (MODE_FULL.equals(mode) && scannedLines != null && !scannedLines.isEmpty()) {
            translateAllScannedBlocks();
        } else if (!currentOriginal.isEmpty() && resultCard.getVisibility() == VISIBLE) {
            translateSnippet(currentOriginal, currentAnchorRect);
        }
    }

    private void swapLanguages() {
        OcrModelManager.Pack newPack = packForTargetLang(targetLang);
        String newTarget = targetLangForPack(activePack);
        if (newTarget.equals(targetLang)) {
            newTarget = "th".equals(targetLang) ? "en" : "th";
        }
        targetLang = newTarget;
        OcrModelManager.setTargetLang(getContext(), targetLang);

        if (OcrModelManager.isPackReady(getContext(), newPack)) {
            activePack = newPack;
            OcrModelManager.setSelectedPack(getContext(), newPack);
            scannedPack = null;
            scannedLines = null;
            fullBlocks = Collections.emptyList();
            hoveredRect = null;
            hoveredSourceText = null;
            hideResultCard();
            updateToolbarUi();
            enterActiveMode();
        } else {
            // Target OCR pack is not downloaded yet — open the on-demand pack downloader so user can grab it in 1 tap.
            updateToolbarUi();
            showPackDownloadModal(false);
        }
    }

    private static OcrModelManager.Pack packForTargetLang(String code) {
        if ("th".equals(code)) return OcrModelManager.Pack.TH;
        if ("en".equals(code)) return OcrModelManager.Pack.EN;
        if ("ko".equals(code)) return OcrModelManager.Pack.KO;
        if ("ja".equals(code) || "zh-CN".equals(code)) return OcrModelManager.Pack.CJK;
        return OcrModelManager.Pack.EN;
    }

    private static String targetLangForPack(OcrModelManager.Pack pack) {
        if (pack == OcrModelManager.Pack.TH) return "th";
        if (pack == OcrModelManager.Pack.EN) return "en";
        if (pack == OcrModelManager.Pack.KO) return "ko";
        return "zh-CN";
    }

    private void showPackPicker() {
        if (paused) {
            // While paused, stage window is hidden; cycle among installed packs directly.
            OcrModelManager.Pack[] all = OcrModelManager.Pack.values();
            int start = activePack.ordinal();
            for (int step = 1; step < all.length; step++) {
                OcrModelManager.Pack next = all[(start + step) % all.length];
                if (OcrModelManager.isPackReady(getContext(), next)) {
                    activePack = next;
                    OcrModelManager.setSelectedPack(getContext(), next);
                    updateToolbarUi();
                    return;
                }
            }
            return;
        }
        showPackDownloadModal(false);
    }

    private void showPackDownloadModal(boolean requiredFirstTime) {
        modalLayer.removeAllViews();
        LinearLayout card = Ui.column(getContext());
        int pad = dp(20);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(Ui.border(getContext(), colors.surface, 20, colors.border));
        card.setElevation(dp(16));
        card.setClickable(true);

        card.addView(Ui.text(getContext(), getContext().getString(R.string.ocr_download_prompt_title),
                18, colors.text, true));
        TextView subtitle = Ui.text(getContext(), getContext().getString(R.string.ocr_download_prompt_body),
                13, colors.muted, false);
        LinearLayout.LayoutParams subP = new LinearLayout.LayoutParams(-1, -2);
        subP.topMargin = dp(6);
        card.addView(subtitle, subP);

        TextView statusText = Ui.text(getContext(), "", 13, colors.accent, true);
        statusText.setVisibility(GONE);
        LinearLayout.LayoutParams stP = new LinearLayout.LayoutParams(-1, -2);
        stP.topMargin = dp(10);
        card.addView(statusText, stP);

        ProgressBar progressBar = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(GONE);
        LinearLayout.LayoutParams pbP = new LinearLayout.LayoutParams(-1, dp(8));
        pbP.topMargin = dp(6);
        card.addView(progressBar, pbP);

        LinearLayout list = Ui.column(getContext());
        LinearLayout.LayoutParams listP = new LinearLayout.LayoutParams(-1, -2);
        listP.topMargin = dp(12);
        card.addView(list, listP);

        boolean[] busy = {false};
        for (OcrModelManager.Pack pack : OcrModelManager.Pack.values()) {
            boolean ready = OcrModelManager.isPackReady(getContext(), pack);
            boolean selected = ready && pack == activePack;

            LinearLayout row = new LinearLayout(getContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackground(Ui.pressable(getContext(),
                    selected ? colors.accentWash : colors.rail, 12,
                    selected ? colors.accent : colors.border));

            LinearLayout info = Ui.column(getContext());
            info.addView(Ui.text(getContext(), getContext().getString(pack.labelRes), 14, colors.text, true));
            long bytes = ready ? OcrModelManager.packInstalledBytes(getContext(), pack)
                    : OcrModelManager.estimatedDownloadBytes(getContext(), pack);
            info.addView(Ui.text(getContext(),
                    (ready ? getContext().getString(R.string.ocr_installed) + " · " : "")
                            + OcrModelManager.formatMb(bytes),
                    12, colors.muted, false));
            row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

            TextView badge = Ui.text(getContext(),
                    ready ? (selected ? "✓" : getContext().getString(R.string.ocr_installed))
                            : getContext().getString(R.string.ocr_download),
                    12, ready ? colors.accent : 0xffffffff, true);
            badge.setPadding(dp(10), dp(5), dp(10), dp(5));
            badge.setBackground(Ui.shape(getContext(), ready ? colors.accentWash : colors.accent, 10));
            row.addView(badge, new LinearLayout.LayoutParams(-2, -2));

            row.setOnClickListener(v -> {
                if (busy[0]) return;
                if (OcrModelManager.isPackReady(getContext(), pack)) {
                    activePack = pack;
                    OcrModelManager.setSelectedPack(getContext(), pack);
                    scannedPack = null;
                    scannedLines = null;
                    fullBlocks = Collections.emptyList();
                    modalLayer.setVisibility(GONE);
                    enterActiveMode();
                    return;
                }
                busy[0] = true;
                progressBar.setProgress(0);
                progressBar.setVisibility(VISIBLE);
                statusText.setTextColor(colors.accent);
                statusText.setText(getContext().getString(R.string.ocr_downloading, 0));
                statusText.setVisibility(VISIBLE);
                worker.execute(() -> {
                    try {
                        OcrModelManager.downloadPack(getContext(), pack, (pct, done, total) ->
                                main.post(() -> {
                                    if (closed) return;
                                    progressBar.setProgress(pct);
                                    statusText.setText(getContext().getString(R.string.ocr_downloading, pct));
                                }));
                        main.post(() -> {
                            if (closed) return;
                            busy[0] = false;
                            activePack = pack;
                            scannedPack = null;
                            scannedLines = null;
                            fullBlocks = Collections.emptyList();
                            modalLayer.setVisibility(GONE);
                            enterActiveMode();
                        });
                    } catch (Exception err) {
                        android.util.Log.w("PenDock", "OCR model download failed", err);
                        main.post(() -> {
                            if (closed) return;
                            busy[0] = false;
                            progressBar.setVisibility(GONE);
                            statusText.setTextColor(colors.pick(0xffad293c, 0xffff9eaa));
                            statusText.setText(R.string.ocr_download_failed);
                        });
                    }
                });
            });

            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            if (list.getChildCount() > 0) rp.topMargin = dp(8);
            list.addView(row, rp);
        }

        LinearLayout footer = new LinearLayout(getContext());
        footer.setGravity(Gravity.END);
        LinearLayout.LayoutParams footP = new LinearLayout.LayoutParams(-1, -2);
        footP.topMargin = dp(14);
        TextView cancelBtn = Ui.action(getContext(), getContext().getString(R.string.close), false, () -> {
            if (busy[0]) return;
            if (requiredFirstTime && !OcrModelManager.anyPackReady(getContext())) {
                closeOverlay();
            } else {
                modalLayer.setVisibility(GONE);
            }
        });
        footer.addView(cancelBtn);
        card.addView(footer, footP);

        int modalW = Math.min(dp(420), getResources().getDisplayMetrics().widthPixels - dp(36));
        modalLayer.addView(card, new LayoutParams(modalW, -2, Gravity.CENTER));
        modalLayer.setVisibility(VISIBLE);
    }

    private void ensureFullScan(Runnable onReady) {
        if (scannedLines != null && scannedPack == activePack) {
            if (onReady != null) onReady.run();
            return;
        }
        if (screenshot == null || screenshot.isRecycled()) return;
        cancelPendingWork();
        scanning = true;
        int gen = ++scanGeneration;
        updateToolbarUi();
        OcrModelManager.Pack pack = activePack;
        Rect excludeToolbar = toolbarScreenBounds();
        activeTask = worker.submit(() -> {
            try {
                List<ScreenOcrEngine.TextLine> rawLines =
                        ScreenOcrEngine.recognizeFullScreen(getContext(), pack, screenshot);
                List<ScreenOcrEngine.TextLine> lines = new ArrayList<>(rawLines.size());
                for (ScreenOcrEngine.TextLine l : rawLines) {
                    if (!excludeToolbar.contains(l.bounds.centerX(), l.bounds.centerY())) {
                        lines.add(l);
                    }
                }
                main.post(() -> {
                    if (closed || gen != scanGeneration) return;
                    scanning = false;
                    scannedPack = pack;
                    scannedLines = lines;
                    updateToolbarUi();
                    stage.invalidate();
                    if (onReady != null) onReady.run();
                });
            } catch (Throwable err) {
                android.util.Log.e("PenDock", "OCR full scan failed", err);
                String detail = formatError("OCR", err);
                main.post(() -> {
                    if (closed || gen != scanGeneration) return;
                    scanning = false;
                    updateToolbarUi();
                    Toast.makeText(getContext(), detail, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void translateAllScannedBlocks() {
        if (scannedLines == null || scannedLines.isEmpty()) {
            fullBlocks = Collections.emptyList();
            stage.invalidate();
            Toast.makeText(getContext(), R.string.translate_no_text, Toast.LENGTH_SHORT).show();
            return;
        }
        List<ScreenOcrEngine.TextBlock> blocks = ScreenOcrEngine.groupIntoBlocks(scannedLines);
        List<String> texts = new ArrayList<>(blocks.size());
        for (ScreenOcrEngine.TextBlock b : blocks) texts.add(b.text);

        cancelPendingWork();
        int gen = ++scanGeneration;
        if (!toolbarCollapsed) {
            statusSubPill.setText(R.string.translate_translating);
            statusSubPill.setVisibility(VISIBLE);
        }
        String srcHint = "auto";
        String dst = targetLang;
        activeTask = worker.submit(() -> {
            try {
                List<String> translated = GoogleTranslator.translateBatch(texts, srcHint, dst);
                List<FullBlock> built = new ArrayList<>(blocks.size());
                for (int i = 0; i < blocks.size(); i++) {
                    String tr = i < translated.size() ? translated.get(i) : "";
                    if (tr == null || tr.trim().isEmpty()) continue;
                    boolean darkBg = sampleIsDark(blocks.get(i).bounds);
                    built.add(new FullBlock(blocks.get(i), tr.trim(), darkBg));
                }
                main.post(() -> {
                    if (closed || gen != scanGeneration) return;
                    fullBlocks = built;
                    updateToolbarUi();
                    stage.invalidate();
                });
            } catch (Throwable err) {
                android.util.Log.e("PenDock", "Full-screen batch translation failed", err);
                String detail = formatError("Translate", err);
                main.post(() -> {
                    if (closed || gen != scanGeneration) return;
                    updateToolbarUi();
                    Toast.makeText(getContext(), detail, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void translateBox(RectF boxViewRect) {
        if (screenshot == null || screenshot.isRecycled()) return;
        getLocationOnScreen(screenOrigin);
        Rect bmpRect = new Rect(
                Math.round(boxViewRect.left + screenOrigin[0]),
                Math.round(boxViewRect.top + screenOrigin[1]),
                Math.round(boxViewRect.right + screenOrigin[0]),
                Math.round(boxViewRect.bottom + screenOrigin[1]));
        Rect anchor = new Rect(
                Math.round(boxViewRect.left), Math.round(boxViewRect.top),
                Math.round(boxViewRect.right), Math.round(boxViewRect.bottom));

        cancelPendingWork();
        int gen = ++scanGeneration;
        showLoadingCard(anchor);
        OcrModelManager.Pack pack = activePack;
        String dst = targetLang;
        activeTask = worker.submit(() -> {
            try {
                List<ScreenOcrEngine.TextLine> lines =
                        ScreenOcrEngine.recognizeRegion(getContext(), pack, screenshot, bmpRect);
                List<ScreenOcrEngine.TextBlock> blocks = ScreenOcrEngine.groupIntoBlocks(lines);
                List<String> origParagraphs = new ArrayList<>(blocks.size());
                for (ScreenOcrEngine.TextBlock block : blocks) {
                    String t = block.text == null ? "" : block.text.trim();
                    if (!t.isEmpty()) origParagraphs.add(t);
                }
                if (origParagraphs.isEmpty()) {
                    main.post(() -> {
                        if (closed || gen != scanGeneration) return;
                        showResultCard(getContext().getString(R.string.translate_no_text), "", anchor, false);
                    });
                    return;
                }
                List<String> trParagraphs = GoogleTranslator.translateBatch(origParagraphs, "auto", dst);
                String translated = joinParagraphs(trParagraphs);
                String original = joinParagraphs(origParagraphs);
                main.post(() -> {
                    if (closed || gen != scanGeneration) return;
                    showResultCard(translated, original, anchor, true);
                });
            } catch (Throwable err) {
                android.util.Log.e("PenDock", "Box translate failed", err);
                String detail = formatError("Translate", err);
                main.post(() -> {
                    if (closed || gen != scanGeneration) return;
                    showResultCard(detail, "", anchor, false);
                });
            }
        });
    }

    private void translateSnippet(String sourceText, Rect anchorViewRect) {
        if (sourceText == null || sourceText.trim().isEmpty()) return;
        String srcHint = "auto";
        String dst = targetLang;
        String cached = GoogleTranslator.getCached(sourceText, srcHint, dst);
        if (cached != null) {
            showResultCard(cached, sourceText, anchorViewRect, true);
            return;
        }
        cancelPendingWork();
        int gen = ++scanGeneration;
        showLoadingCard(anchorViewRect);
        activeTask = worker.submit(() -> {
            try {
                String translated = GoogleTranslator.translate(sourceText, srcHint, dst);
                main.post(() -> {
                    if (closed || gen != scanGeneration) return;
                    showResultCard(translated, sourceText, anchorViewRect, true);
                });
            } catch (Throwable err) {
                android.util.Log.e("PenDock", "Snippet translate failed", err);
                String detail = formatError("Translate", err);
                main.post(() -> {
                    if (closed || gen != scanGeneration) return;
                    showResultCard(detail, sourceText, anchorViewRect, false);
                });
            }
        });
    }

    private static String joinParagraphs(List<String> paragraphs) {
        StringBuilder sb = new StringBuilder();
        for (String p : paragraphs) {
            if (p == null) continue;
            String trimmed = p.trim();
            if (trimmed.isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(trimmed);
        }
        return sb.toString();
    }

    private String formatError(String stageName, Throwable err) {
        String msg = err.getMessage();
        String base = getContext().getString(R.string.translate_failed);
        if (msg == null || msg.trim().isEmpty()) {
            return base + " (" + stageName + ": " + err.getClass().getSimpleName() + ")";
        }
        if (msg.length() > 70) msg = msg.substring(0, 70) + "…";
        return base + " (" + stageName + ": " + msg + ")";
    }

    private void showLoadingCard(Rect anchor) {
        currentAnchorRect = anchor;
        resultTranslated.setText(R.string.translate_translating);
        resultTranslated.setTextColor(colors.muted);
        resultOriginal.setVisibility(GONE);
        resultCopyBtn.setVisibility(GONE);
        resultSpeakBtn.setVisibility(GONE);
        resultCard.setVisibility(VISIBLE);
        positionResultCard(anchor);
    }

    private void showResultCard(String translated, String original, Rect anchor, boolean hasContent) {
        currentTranslation = hasContent ? translated : "";
        currentOriginal = original == null ? "" : original;
        currentAnchorRect = anchor;
        resultTranslated.setText(translated);
        resultTranslated.setTextColor(hasContent ? colors.text : colors.muted);
        if (!currentOriginal.isEmpty() && !currentOriginal.equals(translated)) {
            resultOriginal.setText(currentOriginal);
            resultOriginal.setVisibility(VISIBLE);
        } else {
            resultOriginal.setVisibility(GONE);
        }
        resultCopyBtn.setVisibility(hasContent ? VISIBLE : GONE);
        resultSpeakBtn.setVisibility(hasContent ? VISIBLE : GONE);
        resultScroll.scrollTo(0, 0);
        resultCard.setVisibility(VISIBLE);
        positionResultCard(anchor);
    }

    private void hideResultCard() {
        resultCard.setVisibility(GONE);
        currentTranslation = "";
        currentOriginal = "";
        currentAnchorRect = null;
    }

    private void positionResultCard(Rect anchor) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        int cardW = Math.min(dp(440), w - dp(32));
        int maxScrollH = Math.max(dp(160), Math.round(h * 0.44f));

        // Measure scroll body natural height so short translations stay compact and long ones scroll cleanly.
        View scrollBody = resultScroll.getChildAt(0);
        int innerW = Math.max(dp(120), cardW - dp(28));
        scrollBody.measure(
                MeasureSpec.makeMeasureSpec(innerW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int bodyH = scrollBody.getMeasuredHeight();
        LinearLayout.LayoutParams scrollLp = (LinearLayout.LayoutParams) resultScroll.getLayoutParams();
        int desiredScrollH = bodyH > maxScrollH ? maxScrollH : LinearLayout.LayoutParams.WRAP_CONTENT;
        if (scrollLp.height != desiredScrollH) {
            scrollLp.height = desiredScrollH;
            resultScroll.setLayoutParams(scrollLp);
        }

        LayoutParams cardLp = (LayoutParams) resultCard.getLayoutParams();
        if (cardLp.width != cardW) {
            cardLp.width = cardW;
            resultCard.setLayoutParams(cardLp);
        }

        resultCard.measure(
                MeasureSpec.makeMeasureSpec(cardW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.round(h * 0.65f), MeasureSpec.AT_MOST));
        int cardH = resultCard.getMeasuredHeight();
        int margin = dp(16);
        int gap = dp(14);
        int topSafe = dp(24);
        int bottomSafe = h - dp(20);

        float x;
        float y;
        if (anchor == null) {
            x = (w - cardW) * 0.5f;
            y = Math.max(topSafe, (h - cardH) * 0.5f);
        } else {
            x = Math.max(margin, Math.min(w - cardW - margin, anchor.centerX() - cardW * 0.5f));
            if (anchor.bottom + gap + cardH <= bottomSafe) {
                y = anchor.bottom + gap;
            } else if (anchor.top - gap - cardH >= topSafe) {
                y = anchor.top - gap - cardH;
            } else {
                y = Math.max(topSafe, Math.min(bottomSafe - cardH, anchor.bottom + gap));
            }
        }
        resultCard.setTranslationX(x);
        resultCard.setTranslationY(y);
    }

    private void copyCurrentTranslation() {
        if (currentTranslation.isEmpty()) return;
        ClipboardManager cm = getContext().getSystemService(ClipboardManager.class);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("PenDock translation", currentTranslation));
            Toast.makeText(getContext(), R.string.translate_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void speakCurrentTranslation() {
        if (currentTranslation.isEmpty()) return;
        String textToSpeak = currentTranslation;
        Locale locale = localeForTarget(targetLang);
        if (tts == null) {
            tts = new TextToSpeech(getContext().getApplicationContext(), status -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (ttsReady && !closed) {
                    tts.setLanguage(locale);
                    tts.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, "pendock_tr");
                }
            });
        } else if (ttsReady) {
            tts.setLanguage(locale);
            tts.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, "pendock_tr");
        }
    }

    private static Locale localeForTarget(String code) {
        if ("en".equals(code)) return Locale.US;
        if ("ja".equals(code)) return Locale.JAPAN;
        if ("zh-CN".equals(code)) return Locale.SIMPLIFIED_CHINESE;
        if ("ko".equals(code)) return Locale.KOREA;
        return new Locale("th", "TH");
    }

    private boolean sampleIsDark(Rect bmpBounds) {
        if (screenshot == null || screenshot.isRecycled()) return colors.dark;
        int w = screenshot.getWidth();
        int h = screenshot.getHeight();
        int[][] pts = {
                {bmpBounds.left + 2, bmpBounds.top + 2},
                {bmpBounds.right - 2, bmpBounds.top + 2},
                {bmpBounds.left + 2, bmpBounds.bottom - 2},
                {bmpBounds.right - 2, bmpBounds.bottom - 2},
                {bmpBounds.centerX(), bmpBounds.top + 2}
        };
        float lumSum = 0f;
        int count = 0;
        for (int[] pt : pts) {
            int px = Math.max(0, Math.min(w - 1, pt[0]));
            int py = Math.max(0, Math.min(h - 1, pt[1]));
            int c = screenshot.getPixel(px, py);
            float lum = 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c);
            lumSum += lum;
            count++;
        }
        return count > 0 && (lumSum / count) < 135f;
    }

    private void handlePointTarget(float viewX, float viewY, boolean immediate) {
        if (scannedLines == null || scannedLines.isEmpty()) return;
        getLocationOnScreen(screenOrigin);
        int bx = Math.round(viewX + screenOrigin[0]);
        int by = Math.round(viewY + screenOrigin[1]);

        Rect hitBmp = null;
        String hitText = null;
        int pad = dp(6);
        for (ScreenOcrEngine.TextLine line : scannedLines) {
            Rect r = line.bounds;
            if (bx >= r.left - pad && bx <= r.right + pad && by >= r.top - pad && by <= r.bottom + pad) {
                if (wordUnit && line.words.size() > 1) {
                    for (ScreenOcrEngine.WordSpan word : line.words) {
                        if (bx >= word.bounds.left - pad && bx <= word.bounds.right + pad) {
                            hitBmp = word.bounds;
                            hitText = word.text;
                            break;
                        }
                    }
                }
                if (hitText == null) {
                    hitBmp = line.bounds;
                    hitText = line.text;
                }
                break;
            }
        }

        if (hitText == null) {
            if (immediate) {
                hoveredRect = null;
                hoveredSourceText = null;
                hideResultCard();
                stage.invalidate();
            }
            return;
        }
        if (hitText.equals(hoveredSourceText) && hoveredRect != null) {
            if (immediate && resultCard.getVisibility() != VISIBLE) {
                translateSnippet(hitText, hoveredRect);
            }
            return;
        }

        Rect viewRect = new Rect(
                hitBmp.left - screenOrigin[0], hitBmp.top - screenOrigin[1],
                hitBmp.right - screenOrigin[0], hitBmp.bottom - screenOrigin[1]);
        hoveredRect = viewRect;
        hoveredSourceText = hitText;
        stage.invalidate();

        if (pendingHoverTranslate != null) main.removeCallbacks(pendingHoverTranslate);
        final String targetText = hitText;
        final Rect targetRect = new Rect(viewRect);
        if (immediate || GoogleTranslator.getCached(targetText, "auto", targetLang) != null) {
            translateSnippet(targetText, targetRect);
        } else {
            pendingHoverTranslate = () -> {
                if (!closed && MODE_POINT.equals(mode) && targetText.equals(hoveredSourceText)) {
                    translateSnippet(targetText, targetRect);
                }
            };
            main.postDelayed(pendingHoverTranslate, 180L);
        }
    }

    private void handleFullBlockTap(float viewX, float viewY) {
        if (fullBlocks.isEmpty()) return;
        getLocationOnScreen(screenOrigin);
        int bx = Math.round(viewX + screenOrigin[0]);
        int by = Math.round(viewY + screenOrigin[1]);
        for (FullBlock fb : fullBlocks) {
            Rect r = fb.block.bounds;
            if (bx >= r.left - dp(6) && bx <= r.right + dp(6)
                    && by >= r.top - dp(6) && by <= r.bottom + dp(6)) {
                Rect viewRect = new Rect(
                        r.left - screenOrigin[0], r.top - screenOrigin[1],
                        r.right - screenOrigin[0], r.bottom - screenOrigin[1]);
                showResultCard(fb.translated, fb.block.text, viewRect, true);
                return;
            }
        }
        hideResultCard();
    }

    private void cancelPendingWork() {
        if (pendingHoverTranslate != null) {
            main.removeCallbacks(pendingHoverTranslate);
            pendingHoverTranslate = null;
        }
        if (activeTask != null) {
            activeTask.cancel(true);
            activeTask = null;
        }
    }

    void closeOverlay() {
        if (closed) return;
        closed = true;
        cancelPendingWork();
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) { }
            tts = null;
        }
        worker.shutdownNow();
        if (host != null) host.onClose();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                if (modalLayer.getVisibility() == VISIBLE && OcrModelManager.anyPackReady(getContext())) {
                    modalLayer.setVisibility(GONE);
                } else {
                    closeOverlay();
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean dispatchHoverEvent(MotionEvent event) {
        if (modalLayer.getVisibility() != VISIBLE && MODE_POINT.equals(mode)
                && !isOverChild(resultCard, event.getX(), event.getY())) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_HOVER_ENTER) {
                handlePointTarget(event.getX(), event.getY(), false);
            }
        }
        return super.dispatchHoverEvent(event);
    }

    private static boolean isOverChild(View child, float x, float y) {
        if (child == null || child.getVisibility() != VISIBLE) return false;
        float l = child.getX();
        float t = child.getY();
        return x >= l && x <= l + child.getWidth() && y >= t && y <= t + child.getHeight();
    }

    @Override protected void onDetachedFromWindow() {
        cancelPendingWork();
        if (tts != null) {
            try { tts.shutdown(); } catch (Exception ignored) { }
            tts = null;
        }
        worker.shutdownNow();
        if (screenshot != null && !screenshot.isRecycled()) screenshot.recycle();
        super.onDetachedFromWindow();
    }

    private int dp(float value) { return Ui.dp(getContext(), value); }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00ffffff) | ((Math.max(0, Math.min(255, alpha))) << 24);
    }

    /** Full-screen drawing & gesture surface for the captured frame, boxes, and in-place translations. */
    private final class StageCanvas extends View {
        private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint blockTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Path clipPath = new Path();
        private final RectF drawRect = new RectF();

        StageCanvas(Context context) {
            super(context);
            strokePaint.setStyle(Paint.Style.STROKE);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (modalLayer.getVisibility() == VISIBLE) return false;
            float x = event.getX();
            float y = event.getY();
            int action = event.getActionMasked();

            if (MODE_BOX.equals(mode)) {
                if (action == MotionEvent.ACTION_DOWN) {
                    hideResultCard();
                    draggingBox = true;
                    dragStartX = x;
                    dragStartY = y;
                    activeBox.set(x, y, x, y);
                    invalidate();
                    return true;
                } else if (action == MotionEvent.ACTION_MOVE && draggingBox) {
                    activeBox.set(Math.min(dragStartX, x), Math.min(dragStartY, y),
                            Math.max(dragStartX, x), Math.max(dragStartY, y));
                    invalidate();
                    return true;
                } else if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && draggingBox) {
                    draggingBox = false;
                    activeBox.set(Math.min(dragStartX, x), Math.min(dragStartY, y),
                            Math.max(dragStartX, x), Math.max(dragStartY, y));
                    if (activeBox.width() >= dp(14) && activeBox.height() >= dp(12)) {
                        translateBox(new RectF(activeBox));
                    } else {
                        activeBox.setEmpty();
                    }
                    invalidate();
                    return true;
                }
            } else if (MODE_POINT.equals(mode)) {
                if (action == MotionEvent.ACTION_UP) {
                    handlePointTarget(x, y, true);
                    return true;
                }
                return true;
            } else if (MODE_FULL.equals(mode)) {
                if (action == MotionEvent.ACTION_UP) {
                    handleFullBlockTap(x, y);
                    return true;
                }
                return true;
            }
            return super.onTouchEvent(event);
        }

        @Override protected void onDraw(Canvas canvas) {
            getLocationOnScreen(screenOrigin);
            if (screenshot != null && !screenshot.isRecycled()) {
                canvas.drawBitmap(screenshot, -screenOrigin[0], -screenOrigin[1], bmpPaint);
            }

            if (MODE_BOX.equals(mode)) {
                if (!activeBox.isEmpty()) {
                    float r = dp(12);
                    clipPath.reset();
                    clipPath.addRoundRect(activeBox, r, r, Path.Direction.CW);
                    canvas.save();
                    canvas.clipOutPath(clipPath);
                    canvas.drawColor(0x52000000);
                    canvas.restore();

                    strokePaint.setStrokeWidth(dp(2.2f));
                    strokePaint.setColor(colors.accent);
                    canvas.drawRoundRect(activeBox, r, r, strokePaint);
                } else {
                    canvas.drawColor(0x36000000);
                }
            } else if (MODE_POINT.equals(mode)) {
                // Subtle non-intrusive underline hints for detected lines
                if (scannedLines != null) {
                    strokePaint.setStrokeWidth(dp(1.2f));
                    strokePaint.setColor(withAlpha(colors.accent, 65));
                    for (ScreenOcrEngine.TextLine line : scannedLines) {
                        float l = line.bounds.left - screenOrigin[0];
                        float r = line.bounds.right - screenOrigin[0];
                        float b = line.bounds.bottom - screenOrigin[1] + dp(1);
                        canvas.drawLine(l, b, r, b, strokePaint);
                    }
                }
                if (hoveredRect != null) {
                    drawRect.set(hoveredRect.left - dp(3), hoveredRect.top - dp(2),
                            hoveredRect.right + dp(3), hoveredRect.bottom + dp(2));
                    fillPaint.setColor(withAlpha(colors.accent, 48));
                    canvas.drawRoundRect(drawRect, dp(6), dp(6), fillPaint);
                    strokePaint.setStrokeWidth(dp(1.8f));
                    strokePaint.setColor(colors.accent);
                    canvas.drawRoundRect(drawRect, dp(6), dp(6), strokePaint);
                }
            } else if (MODE_FULL.equals(mode) && !peekingOriginal) {
                for (FullBlock fb : fullBlocks) {
                    drawInPlaceBlock(canvas, fb);
                }
            }
        }

        private void drawInPlaceBlock(Canvas canvas, FullBlock fb) {
            Rect b = fb.block.bounds;
            float l = b.left - screenOrigin[0] - dp(4);
            float t = b.top - screenOrigin[1] - dp(3);
            float r = b.right - screenOrigin[0] + dp(4);
            float bt = b.bottom - screenOrigin[1] + dp(3);
            float boxW = Math.max(dp(44), r - l);
            float boxH = Math.max(dp(22), bt - t);

            int bg = fb.darkBg ? 0xe81a202c : 0xeef8faff;
            int fg = fb.darkBg ? 0xfff5f8ff : 0xff161b26;
            int border = fb.darkBg ? 0x44ffffff : 0x261b2440;

            blockTextPaint.setColor(fg);
            // Pick a crisp, readable font size that fits the block neatly without overflowing wildly.
            int innerW = Math.max(dp(36), Math.round(boxW - dp(10)));
            int linesCount = Math.max(1, fb.block.lines.size());
            float estSize = Math.max(dp(11), Math.min(dp(18), (boxH / linesCount) * 0.72f));
            StaticLayout layout = buildLayout(fb.translated, innerW, estSize);
            while (layout.getHeight() > boxH * 1.28f && estSize > dp(9.5f)) {
                estSize -= dp(1.2f);
                layout = buildLayout(fb.translated, innerW, estSize);
            }
            float finalH = Math.max(boxH, layout.getHeight() + dp(8));
            drawRect.set(l, t, l + boxW, t + finalH);

            fillPaint.setColor(bg);
            canvas.drawRoundRect(drawRect, dp(8), dp(8), fillPaint);
            strokePaint.setStrokeWidth(dp(0.9f));
            strokePaint.setColor(border);
            canvas.drawRoundRect(drawRect, dp(8), dp(8), strokePaint);

            canvas.save();
            float textY = t + (finalH - layout.getHeight()) * 0.5f;
            canvas.translate(l + dp(5), textY);
            layout.draw(canvas);
            canvas.restore();
        }

        private StaticLayout buildLayout(String text, int widthPx, float textSizePx) {
            blockTextPaint.setTextSize(textSizePx);
            return StaticLayout.Builder.obtain(text, 0, text.length(), blockTextPaint, widthPx)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .setLineSpacing(dp(1), 1.0f)
                    .build();
        }
    }
}
