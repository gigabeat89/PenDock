package th.pendock;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.util.function.Consumer;

final class CircleSelection extends FrameLayout {
    private final Bitmap screenshot;
    private final Path outline = new Path();
    private final RectF bounds = new RectF();
    private final RectF screen = new RectF();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Runnable cancel;
    private final Consumer<Bitmap> select;
    private final View canvas;
    private int pointerId = -1;

    CircleSelection(Context context, Bitmap screenshot, Runnable cancel, Consumer<Bitmap> select) {
        super(context);
        this.screenshot = screenshot;
        Appearance.Palette colors = Ui.colors(context);
        this.cancel = cancel;
        this.select = select;
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
                if (pointerId == -1) return;

                int layer = display.save();
                display.clipPath(outline);
                display.drawBitmap(screenshot, null, screen, paint);
                display.restoreToCount(layer);
                paint.setColor(colors.accent);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setStrokeWidth(Ui.dp(context, 3));
                display.drawPath(outline, paint);
            }
        };
        canvas.setContentDescription(context.getString(R.string.selection_area));
        canvas.setOnTouchListener((view, event) -> trace(event));
        addView(canvas, new LayoutParams(-1, -1));

        LinearLayout tools = new LinearLayout(context);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        int padding = Ui.dp(context, 10);
        tools.setPadding(padding * 2, padding, padding, padding);
        tools.setBackground(Ui.shape(context, colors.background, 20));
        tools.addView(Ui.text(context, context.getString(R.string.circle_prompt), 16, colors.text, true));
        tools.addView(Ui.action(context, context.getString(R.string.cancel), false, cancel));
        LayoutParams bar = new LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        bar.topMargin = Ui.dp(context, 36);
        addView(tools, bar);
    }

    private boolean trace(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL) {
            reset();
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            if (PenInput.isPenOnlyMenu(getContext()) && !PenInput.isPen(event, 0)) return true;
            pointerId = event.getPointerId(0);
            outline.reset();
            outline.moveTo(event.getX(), event.getY());
            canvas.invalidate();
            return true;
        }
        int pointer = event.findPointerIndex(pointerId);
        if (pointer < 0) return true;
        if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < event.getHistorySize(); i++) {
                outline.lineTo(event.getHistoricalX(pointer, i), event.getHistoricalY(pointer, i));
            }
            outline.lineTo(event.getX(pointer), event.getY(pointer));
            canvas.invalidate();
        } else if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
                && event.getActionIndex() == pointer) {
            outline.lineTo(event.getX(pointer), event.getY(pointer));
            outline.close();
            outline.computeBounds(bounds, true);
            Bitmap crop = crop();
            reset();
            if (crop != null) select.accept(crop);
        }
        return true;
    }

    private Bitmap crop() {
        int minimum = Ui.dp(getContext(), 14);
        if (bounds.width() < minimum || bounds.height() < minimum) return null;
        float scaleX = screenshot.getWidth() / (float) canvas.getWidth();
        float scaleY = screenshot.getHeight() / (float) canvas.getHeight();
        int left = Math.max(0, (int) Math.floor(bounds.left * scaleX));
        int top = Math.max(0, (int) Math.floor(bounds.top * scaleY));
        int right = Math.min(screenshot.getWidth(), (int) Math.ceil(bounds.right * scaleX));
        int bottom = Math.min(screenshot.getHeight(), (int) Math.ceil(bounds.bottom * scaleY));
        if (right <= left || bottom <= top) return null;

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

    private void reset() {
        pointerId = -1;
        outline.reset();
        canvas.invalidate();
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
