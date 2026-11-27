package th.pendock;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Offline on-device OCR text extractor (Apple Live Text style) powered by PP-OCRv5. */
final class ExtractTextOverlay extends FrameLayout {
    private final Bitmap screenshot;
    private final Appearance.Palette colors;
    private final Runnable onClose;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private final StageView stage;
    private final TextView packPill;
    private final LinearLayout resultCard;
    private final ScrollView resultScroll;
    private final SelectableTextView resultText;
    private final TextView copyBtn;
    private final TextView shareBtn;

    private OcrModelManager.Pack activePack;
    private final RectF box = new RectF();
    private float downX, downY;
    private boolean dragging;
    private int generation;
    private Future<?> activeTask;
    private String extracted = "";
    private boolean closed;

    ExtractTextOverlay(Context context, Bitmap screenshot, Runnable onClose) {
        super(context);
        this.screenshot = screenshot;
        this.colors = Ui.colors(context);
        this.onClose = onClose;
        this.activePack = OcrModelManager.selectedPack(context);
        if (!OcrModelManager.isPackReady(context, activePack)) {
            for (OcrModelManager.Pack p : OcrModelManager.Pack.values()) {
                if (OcrModelManager.isPackReady(context, p)) {
                    activePack = p;
                    break;
                }
            }
        }
        setFocusableInTouchMode(true);

        stage = new StageView(context);
        addView(stage, new LayoutParams(-1, -1));

        // Top floating bar: prompt + OCR pack cycle pill + Close
        LinearLayout bar = new LinearLayout(context);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(9), dp(10), dp(9));
        bar.setBackground(Ui.border(context, withAlpha(colors.surface, 244), 22, colors.border));
        bar.setElevation(dp(10));
        bar.setClickable(true);

        TextView prompt = Ui.text(context, context.getString(R.string.extract_prompt), 14, colors.text, true);
        bar.addView(prompt, new LinearLayout.LayoutParams(-2, -2));

        packPill = Ui.text(context, activePack.badge, 12, colors.accent, true);
        packPill.setGravity(Gravity.CENTER);
        packPill.setPadding(dp(10), dp(6), dp(10), dp(6));
        packPill.setMinHeight(dp(32));
        packPill.setBackground(Ui.pressable(context, colors.accentWash, 10, withAlpha(colors.accent, 110)));
        packPill.setOnClickListener(v -> cycleInstalledPack());
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -2);
        pp.setMarginStart(dp(10));
        bar.addView(packPill, pp);

        TextView closeBtn = Ui.text(context, "✕", 15, colors.text, true);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setMinWidth(dp(34));
        closeBtn.setMinHeight(dp(34));
        closeBtn.setBackground(Ui.pressable(context, colors.rail, 12, colors.border));
        closeBtn.setOnClickListener(v -> close());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
        cp.setMarginStart(dp(8));
        bar.addView(closeBtn, cp);

        LayoutParams barP = new LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        barP.topMargin = dp(34);
        addView(bar, barP);

        // Scrollable, selectable floating result card
        resultCard = Ui.column(context);
        resultCard.setPadding(dp(16), dp(14), dp(16), dp(12));
        resultCard.setBackground(Ui.border(context, withAlpha(colors.surface, 248), 18, colors.border));
        resultCard.setElevation(dp(14));
        resultCard.setVisibility(GONE);
        resultCard.setClickable(true);

        resultScroll = new ScrollView(context);
        resultScroll.setVerticalScrollBarEnabled(true);
        resultText = new SelectableTextView(context);
        resultText.setTextSize(15);
        resultText.setTextColor(colors.text);
        resultText.setLineSpacing(dp(2), 1.08f);
        resultScroll.addView(resultText, new ScrollView.LayoutParams(-1, -2));
        resultCard.addView(resultScroll, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = new LinearLayout(context);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
        ap.topMargin = dp(12);

        shareBtn = actionChip(context.getString(R.string.circle_action_share), false, this::shareExtracted);
        actions.addView(shareBtn, new LinearLayout.LayoutParams(-2, -2));

        copyBtn = actionChip(context.getString(R.string.circle_action_copy), true, this::copyExtracted);
        LinearLayout.LayoutParams copyP = new LinearLayout.LayoutParams(-2, -2);
        copyP.setMarginStart(dp(8));
        actions.addView(copyBtn, copyP);

        resultCard.addView(actions, ap);
        addView(resultCard, new LayoutParams(dp(420), -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
    }

    private TextView actionChip(String label, boolean primary, Runnable onClick) {
        TextView btn = Ui.text(getContext(), label, 13, primary ? 0xffffffff : colors.accent, true);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(14), dp(8), dp(14), dp(8));
        btn.setBackground(Ui.pressable(getContext(),
                primary ? colors.accent : colors.accentWash, 11,
                primary ? colors.accent : withAlpha(colors.accent, 100)));
        btn.setOnClickListener(v -> onClick.run());
        return btn;
    }

    private void cycleInstalledPack() {
        OcrModelManager.Pack[] all = OcrModelManager.Pack.values();
        int start = activePack.ordinal();
        for (int step = 1; step <= all.length; step++) {
            OcrModelManager.Pack next = all[(start + step) % all.length];
            if (OcrModelManager.isPackReady(getContext(), next)) {
                activePack = next;
                OcrModelManager.setSelectedPack(getContext(), next);
                packPill.setText(activePack.badge);
                if (!box.isEmpty()) runOcrOnBox();
                return;
            }
        }
    }

    private void runOcrOnBox() {
        if (screenshot == null || screenshot.isRecycled()) return;
        if (!OcrModelManager.isPackReady(getContext(), activePack)) {
            Toast.makeText(getContext(), R.string.extract_need_ocr_pack, Toast.LENGTH_LONG).show();
            return;
        }
        int vw = stage.getWidth();
        int vh = stage.getHeight();
        if (vw <= 0 || vh <= 0) return;

        float sx = screenshot.getWidth() / (float) vw;
        float sy = screenshot.getHeight() / (float) vh;
        Rect region = new Rect(
                Math.max(0, Math.round(box.left * sx)),
                Math.max(0, Math.round(box.top * sy)),
                Math.min(screenshot.getWidth(), Math.round(box.right * sx)),
                Math.min(screenshot.getHeight(), Math.round(box.bottom * sy)));
        if (region.width() < 12 || region.height() < 12) return;

        cancelTask();
        int gen = ++generation;
        showResult(getContext().getString(R.string.translate_scanning), false);
        OcrModelManager.Pack pack = activePack;
        activeTask = worker.submit(() -> {
            try {
                List<ScreenOcrEngine.TextLine> lines =
                        ScreenOcrEngine.recognizeRegion(getContext(), pack, screenshot, region);
                List<ScreenOcrEngine.TextBlock> blocks = ScreenOcrEngine.groupIntoBlocks(lines);
                StringBuilder sb = new StringBuilder();
                for (ScreenOcrEngine.TextBlock b : blocks) {
                    String t = b.text == null ? "" : b.text.trim();
                    if (t.isEmpty()) continue;
                    if (sb.length() > 0) sb.append("\n\n");
                    sb.append(t);
                }
                String out = sb.toString();
                main.post(() -> {
                    if (closed || gen != generation) return;
                    if (out.isEmpty()) {
                        showResult(getContext().getString(R.string.translate_no_text), false);
                    } else {
                        showResult(out, true);
                    }
                });
            } catch (Throwable err) {
                android.util.Log.e("PenDock", "Extract text OCR failed", err);
                main.post(() -> {
                    if (closed || gen != generation) return;
                    showResult(getContext().getString(R.string.translate_no_text), false);
                });
            }
        });
    }

    private void showResult(String text, boolean hasContent) {
        extracted = hasContent ? text : "";
        resultText.setPlainText(text, hasContent);
        resultText.setTextColor(hasContent ? colors.text : colors.muted);
        updateCopyButtonLabel();
        copyBtn.setVisibility(hasContent ? VISIBLE : GONE);
        shareBtn.setVisibility(hasContent ? VISIBLE : GONE);
        resultScroll.scrollTo(0, 0);

        int w = getWidth();
        int h = getHeight();
        int cardW = Math.min(dp(460), Math.max(dp(260), w - dp(32)));
        int maxScrollH = Math.max(dp(120), Math.round(h * 0.42f));
        resultText.measure(
                MeasureSpec.makeMeasureSpec(cardW - dp(32), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        LinearLayout.LayoutParams sp = (LinearLayout.LayoutParams) resultScroll.getLayoutParams();
        sp.height = Math.min(maxScrollH, Math.max(dp(32), resultText.getMeasuredHeight() + dp(4)));
        resultScroll.setLayoutParams(sp);

        LayoutParams lp = (LayoutParams) resultCard.getLayoutParams();
        lp.width = cardW;
        lp.bottomMargin = dp(34);
        resultCard.setLayoutParams(lp);
        resultCard.setVisibility(VISIBLE);
    }

    private void updateCopyButtonLabel() {
        boolean partial = resultText.hasCustomSelection();
        copyBtn.setText(getContext().getString(
                partial ? R.string.extract_copy_selected : R.string.circle_action_copy));
    }

    private void copyExtracted() {
        String selected = resultText.selectedText();
        boolean partial = !selected.isEmpty();
        String toCopy = partial ? selected : extracted;
        if (toCopy.isEmpty()) return;
        ClipboardManager cm = getContext().getSystemService(ClipboardManager.class);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("PenDock Text", toCopy));
            Toast.makeText(getContext(), R.string.extract_copied, Toast.LENGTH_SHORT).show();
        }
        if (partial) {
            resultText.clearSelection();
        } else {
            close();
        }
    }

    private void shareExtracted() {
        String selected = resultText.selectedText();
        String toShare = !selected.isEmpty() ? selected : extracted;
        if (toShare.isEmpty()) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, toShare);
        send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(Intent.createChooser(send, getContext().getString(R.string.circle_action_share))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ignored) { }
        close();
    }

    private void cancelTask() {
        generation++;
        if (activeTask != null) {
            activeTask.cancel(true);
            activeTask = null;
        }
    }

    private void close() {
        if (closed) return;
        closed = true;
        cancelTask();
        worker.shutdownNow();
        if (onClose != null) onClose.run();
    }

    @Override protected void onDetachedFromWindow() {
        closed = true;
        cancelTask();
        worker.shutdownNow();
        super.onDetachedFromWindow();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) close();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private final class StageView extends View {
        private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF screenRect = new RectF();

        StageView(Context context) {
            super(context);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(dp(2.5f));
            strokePaint.setColor(colors.accent);
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            screenRect.set(0, 0, w, h);
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            int act = ev.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN) {
                dragging = true;
                downX = ev.getX();
                downY = ev.getY();
                box.set(downX, downY, downX, downY);
                resultCard.setVisibility(GONE);
                invalidate();
                return true;
            } else if (act == MotionEvent.ACTION_MOVE && dragging) {
                box.set(Math.min(downX, ev.getX()), Math.min(downY, ev.getY()),
                        Math.max(downX, ev.getX()), Math.max(downY, ev.getY()));
                invalidate();
                return true;
            } else if ((act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) && dragging) {
                dragging = false;
                if (act == MotionEvent.ACTION_UP && box.width() >= dp(18) && box.height() >= dp(14)) {
                    runOcrOnBox();
                } else {
                    box.setEmpty();
                    invalidate();
                }
                return true;
            }
            return super.onTouchEvent(ev);
        }

        @Override protected void onDraw(Canvas c) {
            if (screenshot == null || screenshot.isRecycled()) return;
            c.drawBitmap(screenshot, null, screenRect, bmpPaint);
            c.drawColor(0x3808111e);
            if (!box.isEmpty()) {
                int save = c.save();
                c.clipRect(box);
                c.drawBitmap(screenshot, null, screenRect, bmpPaint);
                c.restoreToCount(save);
                float r = dp(10);
                c.drawRoundRect(box, r, r, strokePaint);
            }
        }
    }

    /**
     * Stylus- and touch-friendly drag-to-select TextView that never activates Android's Editor,
     * IME, or floating ActionMode PopupWindow (preventing HarmonyOS FreeScript interference in overlays).
     */
    private final class SelectableTextView extends TextView {
        private String rawText = "";
        private boolean selectableEnabled;
        private int selAnchor = -1;
        private int selStart = -1;
        private int selEnd = -1;
        private float touchDownX, touchDownY;
        private boolean selecting;
        private boolean moved;
        private boolean isStylus;
        private final Runnable holdToSelect = () -> {
            if (!selectableEnabled || moved || selecting) return;
            int off = offsetAt(touchDownX, touchDownY);
            if (off < 0) return;
            selecting = true;
            selAnchor = off;
            setSelectionRange(off, Math.min(rawText.length(), off + 1));
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        };

        SelectableTextView(Context context) {
            super(context);
        }

        void setPlainText(String text, boolean allowSelection) {
            rawText = text == null ? "" : text;
            selectableEnabled = allowSelection;
            selAnchor = -1;
            selStart = -1;
            selEnd = -1;
            selecting = false;
            removeCallbacks(holdToSelect);
            setText(rawText);
            updateCopyButtonLabel();
        }

        boolean hasCustomSelection() {
            return selStart >= 0 && selEnd > selStart && selEnd <= rawText.length();
        }

        String selectedText() {
            if (!hasCustomSelection()) return "";
            return rawText.substring(selStart, selEnd).trim();
        }

        void clearSelection() {
            if (selStart == -1 && selEnd == -1) return;
            selAnchor = -1;
            selStart = -1;
            selEnd = -1;
            setText(rawText);
            updateCopyButtonLabel();
        }

        private void setSelectionRange(int a, int b) {
            int len = rawText.length();
            int lo = Math.max(0, Math.min(len, Math.min(a, b)));
            int hi = Math.max(0, Math.min(len, Math.max(a, b)));
            if (lo == selStart && hi == selEnd) return;
            selStart = lo;
            selEnd = hi;
            if (lo < hi) {
                SpannableStringBuilder ssb = new SpannableStringBuilder(rawText);
                ssb.setSpan(new BackgroundColorSpan(withAlpha(colors.accent, 88)),
                        lo, hi, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                setText(ssb);
            } else {
                setText(rawText);
            }
            updateCopyButtonLabel();
        }

        private int offsetAt(float x, float y) {
            Layout layout = getLayout();
            if (layout == null || rawText.isEmpty()) return -1;
            int line = layout.getLineForVertical(Math.max(0, (int) (y - getTotalPaddingTop() + getScrollY())));
            float horiz = Math.max(0f, x - getTotalPaddingLeft() + getScrollX());
            return Math.max(0, Math.min(rawText.length(), layout.getOffsetForHorizontal(line, horiz)));
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            if (!selectableEnabled || rawText.isEmpty()) return super.onTouchEvent(ev);
            int act = ev.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN) {
                touchDownX = ev.getX();
                touchDownY = ev.getY();
                moved = false;
                selecting = false;
                isStylus = PenInput.isPen(ev, 0);
                removeCallbacks(holdToSelect);
                if (isStylus) {
                    int off = offsetAt(touchDownX, touchDownY);
                    if (off >= 0) {
                        selAnchor = off;
                        selecting = true;
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    }
                } else {
                    postDelayed(holdToSelect, 200);
                }
                return true;
            } else if (act == MotionEvent.ACTION_MOVE) {
                float dx = Math.abs(ev.getX() - touchDownX);
                float dy = Math.abs(ev.getY() - touchDownY);
                float slop = dp(6);
                if (!moved && (dx > slop || dy > slop)) {
                    moved = true;
                    removeCallbacks(holdToSelect);
                    if (!selecting && dx > dy * 1.1f) {
                        int off = offsetAt(touchDownX, touchDownY);
                        if (off >= 0) {
                            selAnchor = off;
                            selecting = true;
                            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                        }
                    }
                }
                if (selecting && selAnchor >= 0) {
                    int cur = offsetAt(ev.getX(), ev.getY());
                    if (cur >= 0) setSelectionRange(selAnchor, cur);
                    return true;
                }
                return false;
            } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                removeCallbacks(holdToSelect);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                if (act == MotionEvent.ACTION_UP && !moved) {
                    clearSelection();
                } else if (selecting && selStart == selEnd) {
                    clearSelection();
                }
                selecting = false;
                return true;
            }
            return super.onTouchEvent(ev);
        }
    }

    private int dp(float v) { return Ui.dp(getContext(), v); }
    private static int withAlpha(int color, int alpha) {
        return (color & 0x00ffffff) | ((alpha & 255) << 24);
    }
}
