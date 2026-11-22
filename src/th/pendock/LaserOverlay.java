package th.pendock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;

final class LaserOverlay extends FrameLayout {
    private static final long TRAIL_LIFE_MS = 1100L;
    private static final int[] LASER_COLORS = {0xffff343e, 0xff22c55e, 0xff38bdf8};

    private final Runnable close;
    private final Appearance.Palette colors;
    private final LaserCanvas canvas;
    private final View colorDot;
    private int colorIndex;

    LaserOverlay(Context context, Runnable close) {
        super(context);
        this.close = close;
        this.colors = Ui.colors(context);
        setFocusableInTouchMode(true);

        canvas = new LaserCanvas(context);
        addView(canvas, new LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(context);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(8);
        bar.setPadding(pad * 2, pad, pad, pad);
        bar.setBackground(Ui.border(context, colors.background, 20, colors.border));
        bar.setAlpha(0.92f);

        colorDot = new View(context);
        colorDot.setBackground(Ui.shape(context, LASER_COLORS[colorIndex], 12));
        colorDot.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout colorButton = new FrameLayout(context);
        colorButton.setBackground(Ui.pressable(context, colors.surface, 12, colors.border));
        colorButton.setContentDescription(context.getString(R.string.laser_color));
        colorButton.setFocusable(true);
        colorButton.setOnClickListener(view -> cycleColor());
        colorButton.addView(colorDot, new LayoutParams(dp(16), dp(16), Gravity.CENTER));
        bar.addView(colorButton, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView label = Ui.text(context, context.getString(R.string.laser_tool), 14, colors.text, true);
        label.setPadding(dp(10), 0, dp(10), 0);
        bar.addView(label);

        bar.addView(Ui.action(context, context.getString(R.string.close), false, close));

        LayoutParams barParams = new LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        barParams.topMargin = dp(28);
        addView(bar, barParams);
    }

    private void cycleColor() {
        colorIndex = (colorIndex + 1) % LASER_COLORS.length;
        int chosen = LASER_COLORS[colorIndex];
        colorDot.setBackground(Ui.shape(getContext(), chosen, 12));
        canvas.setLaserColor(chosen);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) close.run();
        return true;
    }

    private int dp(float value) { return Ui.dp(getContext(), value); }

    private static final class TrailPoint {
        final float x, y;
        final long time;
        final int stroke;

        TrailPoint(float x, float y, long time, int stroke) {
            this.x = x;
            this.y = y;
            this.time = time;
            this.stroke = stroke;
        }
    }

    private final class LaserCanvas extends View {
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ArrayList<TrailPoint> trail = new ArrayList<>();
        private final float dotRadius;
        private final float glowWidth;
        private final float coreWidth;
        private int laserColor = LASER_COLORS[0];
        private boolean dotVisible;
        private float dotX, dotY;
        private int activePointer = -1;
        private int strokeId;
        private boolean animating;
        private final Runnable tick = () -> {
            animating = false;
            invalidate();
        };

        LaserCanvas(Context context) {
            super(context);
            dotRadius = Ui.dp(context, 14);
            glowWidth = Ui.dp(context, 10);
            coreWidth = Ui.dp(context, 3.5f);
            glowPaint.setStyle(Paint.Style.STROKE);
            glowPaint.setStrokeCap(Paint.Cap.ROUND);
            glowPaint.setStrokeJoin(Paint.Join.ROUND);
            corePaint.setStyle(Paint.Style.STROKE);
            corePaint.setStrokeCap(Paint.Cap.ROUND);
            corePaint.setStrokeJoin(Paint.Join.ROUND);
            corePaint.setColor(0xfffff7f5);
            rebuildDotShader();
        }

        void setLaserColor(int color) {
            laserColor = color;
            rebuildDotShader();
            invalidate();
        }

        private void rebuildDotShader() {
            int r = Color.red(laserColor);
            int g = Color.green(laserColor);
            int b = Color.blue(laserColor);
            int mid = Color.argb(235, Math.min(255, r + 90), Math.min(255, g + 90), Math.min(255, b + 90));
            int ring = Color.argb(220, r, g, b);
            int edge = Color.argb(0, r, g, b);
            dotPaint.setShader(new RadialGradient(0, 0, dotRadius,
                    new int[] {0xffffffff, mid, ring, edge},
                    new float[] {0f, .20f, .42f, 1f}, Shader.TileMode.CLAMP));
        }

        @Override public boolean onHoverEvent(MotionEvent event) {
            if (PenInput.isTouchpad(event)) return true;
            int action = event.getActionMasked();
            if ((action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE)
                    && PenInput.isPen(event, 0)) {
                dotX = event.getX();
                dotY = event.getY();
                dotVisible = true;
                invalidate();
                return true;
            }
            if (action == MotionEvent.ACTION_HOVER_EXIT && activePointer == -1) {
                dotVisible = false;
                invalidate();
                return true;
            }
            return true;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (PenInput.isTouchpad(event)) return true;
            int action = event.getActionMasked();
            long now = SystemClock.uptimeMillis();
            if (action == MotionEvent.ACTION_DOWN) {
                activePointer = event.getPointerId(0);
                strokeId++;
                dotX = event.getX();
                dotY = event.getY();
                dotVisible = true;
                addTrailPoint(dotX, dotY, now);
                invalidate();
                return true;
            }
            int pointer = event.findPointerIndex(activePointer);
            if (pointer < 0) return true;
            if (action == MotionEvent.ACTION_MOVE) {
                for (int i = 0; i < event.getHistorySize(); i++) {
                    addTrailPoint(event.getHistoricalX(pointer, i), event.getHistoricalY(pointer, i),
                            event.getHistoricalEventTime(i));
                }
                dotX = event.getX(pointer);
                dotY = event.getY(pointer);
                dotVisible = true;
                addTrailPoint(dotX, dotY, now);
                invalidate();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
                    || action == MotionEvent.ACTION_CANCEL) {
                if (action == MotionEvent.ACTION_CANCEL || event.getActionIndex() == pointer) {
                    activePointer = -1;
                    if (!PenInput.isPen(event, pointer)) dotVisible = false;
                    invalidate();
                }
            }
            return true;
        }

        private void addTrailPoint(float x, float y, long time) {
            if (!trail.isEmpty()) {
                TrailPoint last = trail.get(trail.size() - 1);
                if (last.stroke == strokeId) {
                    float dx = x - last.x;
                    float dy = y - last.y;
                    if (dx * dx + dy * dy < 9f) return;
                }
            }
            trail.add(new TrailPoint(x, y, time, strokeId));
        }

        @Override protected void onDraw(Canvas canvas) {
            long now = SystemClock.uptimeMillis();
            int firstAlive = 0;
            while (firstAlive < trail.size() && now - trail.get(firstAlive).time > TRAIL_LIFE_MS) {
                firstAlive++;
            }
            if (firstAlive > 0) trail.subList(0, firstAlive).clear();

            int r = Color.red(laserColor);
            int g = Color.green(laserColor);
            int b = Color.blue(laserColor);
            for (int i = 1; i < trail.size(); i++) {
                TrailPoint a = trail.get(i - 1);
                TrailPoint c = trail.get(i);
                if (a.stroke != c.stroke) continue;
                float life = 1f - Math.min(1f, (now - c.time) / (float) TRAIL_LIFE_MS);
                if (life <= 0f) continue;
                float ease = life * life;
                glowPaint.setColor(Color.argb(Math.round(135 * ease), r, g, b));
                glowPaint.setStrokeWidth(glowWidth * (0.45f + 0.55f * life));
                canvas.drawLine(a.x, a.y, c.x, c.y, glowPaint);

                corePaint.setAlpha(Math.round(240 * ease));
                corePaint.setStrokeWidth(coreWidth * (0.4f + 0.6f * life));
                canvas.drawLine(a.x, a.y, c.x, c.y, corePaint);
            }

            if (dotVisible) {
                int save = canvas.save();
                canvas.translate(dotX, dotY);
                canvas.drawCircle(0, 0, dotRadius, dotPaint);
                canvas.restoreToCount(save);
            }

            if (!trail.isEmpty() && !animating) {
                animating = true;
                postOnAnimation(tick);
            }
        }

        @Override protected void onDetachedFromWindow() {
            removeCallbacks(tick);
            animating = false;
            trail.clear();
            super.onDetachedFromWindow();
        }
    }
}
