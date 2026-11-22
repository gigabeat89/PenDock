package th.pendock;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import java.util.function.Consumer;

final class SurfaceRingAnimation extends SurfaceView implements SurfaceHolder.Callback {
    private final Bitmap artwork;
    private final TimeInterpolator easing;
    private final long durationNanos;
    private final boolean entering;
    private final Consumer<Runnable> handoff;
    private final Consumer<SurfaceRingAnimation> complete;
    private final HandlerThread renderer = new HandlerThread("PenDock transition");
    // Finish each buffer submission before letting the view release the surface.
    private final Object surfaceLock = new Object();
    private boolean ready;
    private boolean stopped;

    SurfaceRingAnimation(Context context, Bitmap artwork, TimeInterpolator easing, long duration,
            boolean entering, Consumer<Runnable> handoff, Consumer<SurfaceRingAnimation> complete) {
        super(context);
        this.artwork = artwork;
        this.entering = entering;
        this.easing = easing;
        this.durationNanos = duration * 1_000_000;
        this.handoff = handoff;
        this.complete = complete;
        setZOrderOnTop(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        getHolder().addCallback(this);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        synchronized (surfaceLock) {
            if (stopped) return;
            ready = true;
        }
        if (renderer.getState() != Thread.State.NEW) return;
        renderer.start();
        Surface surface = holder.getSurface();
        Handler draw = new Handler(renderer.getLooper());
        android.view.Display display = getDisplay();
        float refreshRate = display == null ? 60f : display.getMode().getRefreshRate();
        long interval = Math.round(1_000_000_000d / Math.min(120, Math.max(24, refreshRate)));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (entering) {
            animate(surface, draw, interval, paint);
        } else {
            draw.post(() -> {
                if (drawFrame(surface, paint, 1)) {
                    post(() -> handoff.accept(() -> animate(surface, draw, interval, paint)));
                }
            });
        }
    }

    private void animate(Surface surface, Handler draw, long interval, Paint paint) {
        draw.post(new Runnable() {
            private long start;

            @Override public void run() {
                long frameTime = System.nanoTime();
                if (start == 0) start = frameTime;
                float time = Math.min(1f, (frameTime - start) / (float) durationNanos);
                float progress = easing.getInterpolation(time);
                if (!drawFrame(surface, paint, entering ? progress : 1 - progress)) return;
                if (time >= 1) {
                    finish();
                } else {
                    long now = System.nanoTime();
                    long target = start + interval * (1 + (now - start) / interval);
                    draw.postAtTime(this, SystemClock.uptimeMillis() + (target - now) / 1_000_000L);
                }
            }
        });
    }

    private boolean drawFrame(Surface surface, Paint paint, float visible) {
        try {
            synchronized (surfaceLock) {
                if (!ready || stopped) return false;
                Canvas canvas = surface.lockHardwareCanvas();
                try {
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                    float half = artwork.getWidth() / 2f;
                    float scale = .48f + .52f * visible;
                    canvas.scale(scale, scale, half, half);
                    canvas.rotate(-32 * (1 - visible), half, half);
                    paint.setAlpha(Math.round(visible * 255));
                    canvas.drawBitmap(artwork, 0, 0, paint);
                } finally {
                    surface.unlockCanvasAndPost(canvas);
                }
            }
            return true;
        } catch (RuntimeException unavailable) {
            finish();
            return false;
        }
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        synchronized (surfaceLock) {
            ready = false;
            if (stopped) return;
        }
        finish();
    }

    private void finish() {
        post(() -> complete.accept(this));
    }

    void stop() {
        getHolder().removeCallback(this);
        synchronized (surfaceLock) {
            stopped = true;
            ready = false;
            if (!artwork.isRecycled()) artwork.recycle();
        }
        renderer.quitSafely();
    }
}
