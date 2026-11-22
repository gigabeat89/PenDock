package th.pendock;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.Typeface;

final class BatteryWidgetArt {
    private static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);
    private static Bitmap horizontalPen;
    private static Bitmap closeUpPen;
    private static Bitmap verticalPen;

    final int ink, caption, track, accent;

    BatteryWidgetArt(PenBattery battery, boolean dark) {
        ink = dark ? 0xfff0f5ff : 0xff142440;
        caption = dark ? 0xffafc3e6 : 0xff395270;
        track = dark ? 0xff35496e : 0xffdbe7fa;
        accent = battery.available() && battery.level < 20
                ? (dark ? 0xffff687c : 0xffda314e)
                : (dark ? 0xff77a6ff : 0xff2360ef);
    }

    Bitmap ring(PenBattery battery) {
        Bitmap bitmap = Bitmap.createBitmap(180, 180, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(2, 2);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(track);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(5);
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawCircle(45, 45, 35, paint);
        if (battery.level > 0) {
            paint.setColor(accent);
            canvas.drawArc(10, 10, 80, 80, -90, battery.level * 3.6f, false, paint);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ink);
        paint.setTypeface(BOLD);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(23);
        String value = battery.available() ? battery.level + "%" : "—";
        float width = paint.measureText(value);
        if (width > 55) paint.setTextSize(23 * 55 / width);
        float center = battery.charging ? 40 : 45;
        canvas.drawText(value, 45, center - (paint.ascent() + paint.descent()) / 2, paint);
        if (battery.charging) {
            paint.setColor(accent);
            drawBolt(canvas, paint, 45, 59, 10);
        }
        return bitmap;
    }

    Bitmap meter(int level) {
        Bitmap bitmap = Bitmap.createBitmap(240, 16, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(track);
        canvas.drawRoundRect(0, 0, 240, 16, 8, 8, paint);
        if (level > 0) {
            paint.setColor(accent);
            canvas.drawRoundRect(0, 0, 240 * level / 100f, 16, 8, 8, paint);
        }
        return bitmap;
    }

    Bitmap bolt() {
        Bitmap bitmap = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(accent);
        drawBolt(new Canvas(bitmap), paint, 20, 30, 33);
        return bitmap;
    }

    private static void drawBolt(Canvas canvas, Paint paint, float x, float y, float width) {
        Path path = new Path();
        path.moveTo(x + width * 0.12f, y - width * 0.80f);
        path.lineTo(x - width * 0.50f, y + width * 0.10f);
        path.lineTo(x - width * 0.04f, y + width * 0.10f);
        path.lineTo(x - width * 0.14f, y + width * 0.80f);
        path.lineTo(x + width * 0.50f, y - width * 0.14f);
        path.lineTo(x + width * 0.04f, y - width * 0.14f);
        path.close();
        canvas.drawPath(path, paint);
    }

    static Bitmap pen(Context context) {
        if (horizontalPen == null) horizontalPen = loadPen(context);
        return horizontalPen;
    }

    static Bitmap penCloseUp(Context context, boolean tall) {
        if (closeUpPen == null) {
            Bitmap full = pen(context);
            // Remove part of the transparent margin beyond the nib without cropping its shadow.
            int width = Math.round(full.getWidth() * .91f);
            closeUpPen = Bitmap.createBitmap(full, 0, 0, width, full.getHeight());
        }
        if (!tall) return closeUpPen;
        if (verticalPen == null) {
            Matrix rotation = new Matrix();
            rotation.setRotate(-90);
            verticalPen = Bitmap.createBitmap(closeUpPen, 0, 0, closeUpPen.getWidth(),
                    closeUpPen.getHeight(), rotation, true);
        }
        return verticalPen;
    }

    private static Bitmap loadPen(Context context) {
        Bitmap source = BitmapFactory.decodeResource(context.getResources(), R.drawable.pen_widget_tip);
        Bitmap faded = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas layer = new Canvas(faded);
        Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
        layer.drawBitmap(source, 0, 0, mask);
        mask.setShader(new LinearGradient(0, 0, source.getWidth() * 0.38f, 0,
                0x00000000, 0xff000000, Shader.TileMode.CLAMP));
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        layer.drawRect(0, 0, source.getWidth(), source.getHeight(), mask);
        source.recycle();
        return faded;
    }
}
