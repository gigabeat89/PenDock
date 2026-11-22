package th.pendock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

final class PreviewWallpaper extends View {
    private final boolean dark;
    private final Path fold = new Path();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient base, wave;

    PreviewWallpaper(Context context, boolean dark) {
        super(context);
        this.dark = dark;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        if (width == 0 || height == 0) return;
        base = new LinearGradient(0, height, width, 0,
                dark ? new int[] {0xff273255, 0xff3f4271, 0xff6c5375}
                     : new int[] {0xffacbce9, 0xffd6d7f2, 0xfff2e5d8},
                null, Shader.TileMode.CLAMP);
        wave = new LinearGradient(0, height * .55f, width, height,
                dark ? 0xff354373 : 0xffaab8e7, dark ? 0xff212b4d : 0xff8f9bdb, Shader.TileMode.CLAMP);
        fold.reset();
        fold.moveTo(0, height * .69f);
        fold.cubicTo(width * .25f, height * .44f, width * .68f, height * .13f, width * .93f, height * .22f);
        fold.cubicTo(width * 1.13f, height * .54f, width * .75f, height * .8f, width * .64f, height);
        fold.lineTo(0, height);
        fold.close();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (base == null) return;
        paint.setShader(base);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        paint.setShader(wave);
        canvas.drawPath(fold, paint);
    }
}
