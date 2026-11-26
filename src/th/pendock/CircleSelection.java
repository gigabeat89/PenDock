package th.pendock;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

final class CircleSelection extends FrameLayout {
    enum Action { LENS, SHARE, COPY, SAVE, PIN }

    interface Listener {
        void onAction(Action action, Bitmap crop, Rect screenBounds);
    }

    private final Bitmap screenshot;
    private final Path outline = new Path();
    private final RectF bounds = new RectF();
    private final RectF screen = new RectF();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final boolean instantLens;
    private final boolean instantPin;
    private final Runnable cancel;
    private final Listener onAction;
    private final View canvas;
    private final TextView prompt;
    private final LinearLayout actionBar;
    private int pointerId = -1;
    private float boxStartX, boxStartY;
    private boolean hasSelection;
    private Bitmap pendingCrop;

    CircleSelection(Context context, Bitmap screenshot, boolean lensAvailable,
            boolean showShare, boolean showCopy, boolean showSave, boolean instantPin,
            Runnable cancel, Listener onAction) {
        super(context);
        this.screenshot = screenshot;
        this.instantPin = instantPin;
        this.instantLens = !instantPin && !showShare && !showCopy && !showSave;
        Appearance.Palette colors = Ui.colors(context);
        this.cancel = cancel;
        this.onAction = onAction;
        setFocusableInTouchMode(true);

        canvas = new View(context) {
            @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
                screen.set(0, 0, width, height);
            }

            @Override protected void onDraw(Canvas display) {
                paint.setColor(Color.WHITE);
                paint.setStyle(Paint.Style.FILL);
                display.drawBitmap(screenshot, null, screen, paint);
                display.drawColor(0x3308111e);
                if (pointerId == -1 && !hasSelection) return;

                int layer = display.save();
                if (instantPin) {
                    display.clipRect(bounds);
                } else {
                    display.clipPath(outline);
                }
                display.drawBitmap(screenshot, null, screen, paint);
                display.restoreToCount(layer);
                paint.setColor(colors.accent);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setStrokeWidth(Ui.dp(context, 3));
                if (instantPin) {
                    float r = Ui.dp(context, 10);
                    display.drawRoundRect(bounds, r, r, paint);
                } else {
                    display.drawPath(outline, paint);
                }
            }
        };
        canvas.setContentDescription(context.getString(R.string.selection_area));
        canvas.setOnTouchListener((view, event) -> trace(event));
        addView(canvas, new LayoutParams(-1, -1));

        LinearLayout tools = new LinearLayout(context);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        int padding = Ui.dp(context, 10);
        tools.setPadding(padding * 2, padding, padding, padding);
        tools.setBackground(Ui.border(context, colors.background, 20, colors.border));
        prompt = Ui.text(context, context.getString(instantPin ? R.string.pin_prompt : R.string.circle_prompt),
                16, colors.text, true);
        prompt.setPadding(0, 0, padding, 0);
        tools.addView(prompt);
        tools.addView(Ui.action(context, context.getString(R.string.cancel), false, cancel));
        LayoutParams bar = new LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        bar.topMargin = Ui.dp(context, 36);
        addView(tools, bar);

        actionBar = new LinearLayout(context);
        actionBar.setGravity(Gravity.CENTER_VERTICAL);
        actionBar.setPadding(padding, padding, padding, padding);
        actionBar.setBackground(Ui.border(context, colors.background, 20, colors.border));
        actionBar.setVisibility(GONE);
        if (!instantLens && !instantPin) {
            if (lensAvailable) addActionButton(context, colors, R.drawable.ic_lens, R.string.circle_action_lens, Action.LENS);
            if (showShare) addActionButton(context, colors, R.drawable.ic_share, R.string.circle_action_share, Action.SHARE);
            if (showCopy) addActionButton(context, colors, R.drawable.ic_copy, R.string.circle_action_copy, Action.COPY);
            if (showSave) addActionButton(context, colors, R.drawable.ic_save, R.string.circle_action_save, Action.SAVE);
            addActionButton(context, colors, R.drawable.ic_pin, R.string.circle_action_pin, Action.PIN);
        }
        LayoutParams bottom = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bottom.bottomMargin = Ui.dp(context, 42);
        addView(actionBar, bottom);
    }

    private void addActionButton(Context context, Appearance.Palette colors,
            int iconRes, int label, Action action) {
        boolean primary = actionBar.getChildCount() == 0;
        int tint = primary ? colors.accent : colors.text;
        LinearLayout button = new LinearLayout(context);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setMinimumHeight(Ui.dp(context, 48));
        button.setPadding(Ui.dp(context, 14), Ui.dp(context, 8), Ui.dp(context, 16), Ui.dp(context, 8));
        button.setBackground(Ui.pressable(context,
                primary ? colors.accentWash : colors.surface, 12,
                primary ? colors.accent : colors.border));
        button.setFocusable(true);
        button.setOnClickListener(view -> chooseAction(action));

        ImageView icon = new ImageView(context);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(tint));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(Ui.dp(context, 19), Ui.dp(context, 19));
        iconParams.setMarginEnd(Ui.dp(context, 7));
        button.addView(icon, iconParams);
        button.addView(Ui.text(context, context.getString(label), 14, tint, true));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        if (actionBar.getChildCount() > 0) params.setMarginStart(Ui.dp(context, 8));
        actionBar.addView(button, params);
    }

    private void chooseAction(Action action) {
        if (action == Action.PIN) {
            Bitmap rectCrop = crop(true);
            clearPendingCrop();
            if (rectCrop != null) onAction.onAction(Action.PIN, rectCrop, screenBoundsRect());
            return;
        }
        Bitmap crop = pendingCrop;
        pendingCrop = null;
        if (crop != null) onAction.onAction(action, crop, screenBoundsRect());
    }

    private Rect screenBoundsRect() {
        return new Rect(Math.round(bounds.left), Math.round(bounds.top),
                Math.round(bounds.right), Math.round(bounds.bottom));
    }

    private boolean trace(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL) {
            reset();
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            if (PenInput.isPenOnlyMenu(getContext()) && !PenInput.isPen(event, 0)) return true;
            clearPendingCrop();
            hasSelection = false;
            actionBar.setVisibility(GONE);
            prompt.setText(instantPin ? R.string.pin_prompt : R.string.circle_prompt);
            pointerId = event.getPointerId(0);
            if (instantPin) {
                boxStartX = event.getX();
                boxStartY = event.getY();
                bounds.set(boxStartX, boxStartY, boxStartX, boxStartY);
            } else {
                outline.reset();
                outline.moveTo(event.getX(), event.getY());
            }
            canvas.invalidate();
            return true;
        }
        int pointer = event.findPointerIndex(pointerId);
        if (pointer < 0) return true;
        if (action == MotionEvent.ACTION_MOVE) {
            if (instantPin) {
                float x = event.getX(pointer);
                float y = event.getY(pointer);
                bounds.set(Math.min(boxStartX, x), Math.min(boxStartY, y),
                        Math.max(boxStartX, x), Math.max(boxStartY, y));
            } else {
                for (int i = 0; i < event.getHistorySize(); i++) {
                    outline.lineTo(event.getHistoricalX(pointer, i), event.getHistoricalY(pointer, i));
                }
                outline.lineTo(event.getX(pointer), event.getY(pointer));
            }
            canvas.invalidate();
        } else if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
                && event.getActionIndex() == pointer) {
            if (instantPin) {
                float x = event.getX(pointer);
                float y = event.getY(pointer);
                bounds.set(Math.min(boxStartX, x), Math.min(boxStartY, y),
                        Math.max(boxStartX, x), Math.max(boxStartY, y));
            } else {
                outline.lineTo(event.getX(pointer), event.getY(pointer));
                outline.close();
                outline.computeBounds(bounds, true);
            }
            pointerId = -1;
            Bitmap crop = crop(instantPin);
            if (crop != null) {
                if (instantPin) {
                    onAction.onAction(Action.PIN, crop, screenBoundsRect());
                } else if (instantLens) {
                    onAction.onAction(Action.LENS, crop, screenBoundsRect());
                } else {
                    clearPendingCrop();
                    pendingCrop = crop;
                    hasSelection = true;
                    prompt.setText(R.string.circle_ready_prompt);
                    actionBar.setVisibility(VISIBLE);
                    canvas.invalidate();
                }
            } else {
                reset();
            }
        }
        return true;
    }

    private Bitmap crop(boolean rectangular) {
        int minimum = Ui.dp(getContext(), 14);
        if (bounds.width() < minimum || bounds.height() < minimum
                || canvas.getWidth() <= 0 || canvas.getHeight() <= 0) return null;
        float scaleX = screenshot.getWidth() / (float) canvas.getWidth();
        float scaleY = screenshot.getHeight() / (float) canvas.getHeight();
        int left = Math.max(0, (int) Math.floor(bounds.left * scaleX));
        int top = Math.max(0, (int) Math.floor(bounds.top * scaleY));
        int right = Math.min(screenshot.getWidth(), (int) Math.ceil(bounds.right * scaleX));
        int bottom = Math.min(screenshot.getHeight(), (int) Math.ceil(bounds.bottom * scaleY));
        if (right <= left || bottom <= top) return null;

        if (rectangular) {
            return Bitmap.createBitmap(screenshot, left, top, right - left, bottom - top);
        }
        Bitmap result = Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888);
        Canvas output = new Canvas(result);
        output.translate(-left, -top);
        output.scale(scaleX, scaleY);
        output.clipPath(outline);
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.FILL);
        output.drawBitmap(screenshot, null, screen, paint);
        return result;
    }

    private void clearPendingCrop() {
        if (pendingCrop != null) {
            pendingCrop.recycle();
            pendingCrop = null;
        }
    }

    private void reset() {
        clearPendingCrop();
        pointerId = -1;
        hasSelection = false;
        actionBar.setVisibility(GONE);
        prompt.setText(R.string.circle_prompt);
        outline.reset();
        canvas.invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        clearPendingCrop();
        super.onDetachedFromWindow();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        return PenInput.isTouchpad(event) || super.dispatchTouchEvent(event);
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return PenInput.isTouchpad(event) || super.dispatchGenericMotionEvent(event);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) cancel.run();
        return true;
    }
}
