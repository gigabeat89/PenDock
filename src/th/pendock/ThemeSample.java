package th.pendock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

final class ThemeSample extends View {
    private final String theme;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frame = new RectF();

    ThemeSample(Context context, String theme) {
        super(context);
        this.theme = theme;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        float scaleX = getWidth() / 59f;
        float scaleY = getHeight() / 34f;
        canvas.save();
        canvas.scale(scaleX, scaleY);
        frame.set(0, 0, 59, 34);
        paint.setColor(0xffcad2e4);
        canvas.drawRoundRect(frame, 7, 7, paint);
        drawWindow(canvas, Appearance.DARK.equals(theme));
        if (Appearance.SYSTEM.equals(theme)) {
            canvas.save();
            canvas.clipRect(30, 0, 59, 34);
            drawWindow(canvas, true);
            canvas.restore();
        }
        canvas.restore();
    }

    private void drawWindow(Canvas canvas, boolean dark) {
        paint.setColor(dark ? 0xff202940 : 0xfff8faff);
        canvas.drawRoundRect(1, 1, 58, 33, 6, 6, paint);
        paint.setColor(dark ? 0xff54658b : 0xffc4cdec);
        canvas.drawRoundRect(6, 6, 17, 28, 3, 3, paint);
        paint.setColor(dark ? 0xff8597c0 : 0xffaab9e5);
        for (int y = 8; y <= 22; y += 7) canvas.drawRoundRect(22, y, 49, y + 3, 1.5f, 1.5f, paint);
    }
}
