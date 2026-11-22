package th.pendock;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;

final class InkView extends View {
    private static final int PAPER = 0xfffaf7ef;
    private static final PorterDuffXfermode CLEAR = new PorterDuffXfermode(PorterDuff.Mode.CLEAR);
    private static final int MAX_UNDO = 30;

    private Bitmap base;
    private Bitmap ink;
    private Canvas inkCanvas;
    private final RectF area = new RectF();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final ArrayList<Stroke> history = new ArrayList<>();
    private Stroke current;
    private int activePointer = -1;
    private float scale = 1;
    int color = 0xff192635;
    boolean erase;
    boolean dirty;

    InkView(Context context, Bitmap base) {
        super(context);
        this.base = base;
        setContentDescription(context.getString(R.string.note_canvas));
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        if (width == 0 || height == 0) return;
        if (ink == null) {
            int sourceWidth = base == null ? width : base.getWidth();
            int sourceHeight = base == null ? height : base.getHeight();
            float resolution = Math.min(1f, 2000f / Math.max(sourceWidth, sourceHeight));
            ink = Bitmap.createBitmap(Math.max(1, Math.round(sourceWidth * resolution)),
                    Math.max(1, Math.round(sourceHeight * resolution)), Bitmap.Config.ARGB_8888);
            inkCanvas = new Canvas(ink);
            redraw();
        }
        scale = Math.min(width / (float) ink.getWidth(), height / (float) ink.getHeight());
        float left = (width - ink.getWidth() * scale) / 2;
        float top = (height - ink.getHeight() * scale) / 2;
        area.set(left, top, left + ink.getWidth() * scale, top + ink.getHeight() * scale);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (ink == null) return;
        paint.setColor(PAPER);
        canvas.drawRect(area, paint);
        canvas.drawBitmap(ink, null, area, imagePaint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (ink == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                int index = event.getActionIndex();
                boolean pen = PenInput.isPen(event, index);
                if (activePointer != -1 && pen && current != null && !current.pen) cancelCurrent();
                if (activePointer != -1 || !area.contains(event.getX(index), event.getY(index))) return true;
                boolean eraserTip = event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER;
                current = new Stroke(color, erase || eraserTip, pen);
                activePointer = event.getPointerId(index);
                addPoint(event.getX(index), event.getY(index), event.getPressure(index));
                return true;
            case MotionEvent.ACTION_MOVE:
                int pointer = event.findPointerIndex(activePointer);
                if (pointer < 0 || current == null) return true;
                for (int h = 0; h < event.getHistorySize(); h++) {
                    addPoint(event.getHistoricalX(pointer, h), event.getHistoricalY(pointer, h),
                            event.getHistoricalPressure(pointer, h));
                }
                addPoint(event.getX(pointer), event.getY(pointer), event.getPressure(pointer));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                int released = event.getActionIndex();
                if (event.getPointerId(released) != activePointer || current == null) return true;
                if (android.os.Build.VERSION.SDK_INT >= 33 && (event.getFlags() & MotionEvent.FLAG_CANCELED) != 0) {
                    cancelCurrent();
                    return true;
                }
                addPoint(event.getX(released), event.getY(released), event.getPressure(released));
                history.add(current);
                current = null;
                activePointer = -1;
                dirty = true;
                if (history.size() > MAX_UNDO) {
                    if (base == null || !base.isMutable()) {
                        base = base == null ? Bitmap.createBitmap(ink.getWidth(), ink.getHeight(),
                                Bitmap.Config.ARGB_8888) : base.copy(Bitmap.Config.ARGB_8888, true);
                    }
                    drawStroke(new Canvas(base), history.remove(0));
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelCurrent();
                return true;
            default:
                return true;
        }
    }

    private void addPoint(float x, float y, float pressure) {
        float thickness = current.erase ? 20 : current.pen ? 1.5f + 4f * Math.max(0, Math.min(1, pressure)) : 4f;
        Point point = new Point((x - area.left) / scale, (y - area.top) / scale,
                Ui.dp(getContext(), thickness) / scale);
        ArrayList<Point> points = current.points;
        Point last = points.isEmpty() ? null : points.get(points.size() - 1);
        points.add(point);
        paintStroke(current);
        drawPoint(inkCanvas, last, point);
        paint.setXfermode(null);
        invalidate();
    }

    private void drawPoint(Canvas canvas, Point previous, Point point) {
        if (previous == null) canvas.drawCircle(point.x, point.y, point.width / 2f, paint);
        else {
            paint.setStrokeWidth((point.width + previous.width) / 2);
            canvas.drawLine(previous.x, previous.y, point.x, point.y, paint);
        }
    }

    private void paintStroke(Stroke stroke) {
        paint.setColor(stroke.color);
        paint.setXfermode(stroke.erase ? CLEAR : null);
    }

    private void drawStroke(Canvas canvas, Stroke stroke) {
        paintStroke(stroke);
        Point previous = null;
        for (Point point : stroke.points) {
            drawPoint(canvas, previous, point);
            previous = point;
        }
        paint.setXfermode(null);
    }

    private void redraw() {
        if (ink == null) return;
        ink.eraseColor(0);
        if (base != null) inkCanvas.drawBitmap(base, 0, 0, imagePaint);
        for (Stroke stroke : history) drawStroke(inkCanvas, stroke);
        invalidate();
    }

    private void cancelCurrent() {
        current = null;
        activePointer = -1;
        redraw();
    }

    void undo() {
        if (!history.isEmpty()) {
            history.remove(history.size() - 1);
            dirty = true;
        }
        cancelCurrent();
    }

    void clear() {
        history.clear();
        base = null;
        dirty = true;
        cancelCurrent();
    }

    Bitmap copyInk() {
        return ink == null ? null : ink.copy(Bitmap.Config.ARGB_8888, false);
    }

    Bitmap compose() {
        if (ink == null) return null;
        Bitmap result = Bitmap.createBitmap(ink.getWidth(), ink.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas output = new Canvas(result);
        output.drawColor(PAPER);
        output.drawBitmap(ink, 0, 0, imagePaint);
        return result;
    }

    private static final class Point {
        final float x, y, width;
        Point(float x, float y, float width) { this.x = x; this.y = y; this.width = width; }
    }

    private static final class Stroke {
        final int color;
        final boolean erase, pen;
        final ArrayList<Point> points = new ArrayList<>();
        Stroke(int color, boolean erase, boolean pen) {
            this.color = color;
            this.erase = erase;
            this.pen = pen;
        }
    }
}
