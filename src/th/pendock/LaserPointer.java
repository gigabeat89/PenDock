package th.pendock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

final class LaserPointer extends View {
    private static final String PREF = "laser_pointer";
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int size;
    private boolean enabled;

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences("tools", Context.MODE_PRIVATE).getBoolean(PREF, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("tools", Context.MODE_PRIVATE).edit().putBoolean(PREF, enabled).apply();
    }

    LaserPointer(FrameLayout parent) {
        super(parent.getContext());
        size = Ui.dp(getContext(), 24);
        float center = size / 2f;
        paint.setShader(new RadialGradient(center, center, center,
                new int[] {0xfffff7f1, 0xffff7c71, 0xeaff343e, 0x00ff343e},
                new float[] {0f, .18f, .36f, 1f}, Shader.TileMode.CLAMP));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setVisibility(INVISIBLE);
        parent.addView(this, new FrameLayout.LayoutParams(size, size));
        setEnabled(isEnabled(getContext()));
    }

    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        this.enabled = enabled;
        if (!enabled) hide();
    }

    void update(MotionEvent event) {
        if (!enabled) return;
        int action = event.getActionMasked();
        if ((action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE)
                && PenInput.isPen(event, 0)) {
            setTranslationX(event.getX() - size / 2f);
            setTranslationY(event.getY() - size / 2f);
            setVisibility(VISIBLE);
        } else {
            hide();
        }
    }

    void hide() { setVisibility(INVISIBLE); }

    @Override protected void onDraw(Canvas canvas) {
        float center = size / 2f;
        canvas.drawCircle(center, center, center, paint);
    }
}
