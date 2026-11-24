package th.pendock;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import java.util.function.Consumer;

public class CircleCaptureService extends AccessibilityService {
    private static final long CACHE_TTL_MS = 2500L;
    private static CircleCaptureService connected;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Bitmap recentFrame;
    private static long recentTimeMs;
    private static final Runnable clearRecent = CircleCaptureService::clearRecentFrame;

    static boolean isReady() {
        return Build.VERSION.SDK_INT >= 30 && connected != null;
    }

    static void capture(Consumer<Bitmap> result) {
        capture(result, false);
    }

    static void captureOrRecent(Consumer<Bitmap> result) {
        capture(result, true);
    }

    private static void capture(Consumer<Bitmap> result, boolean allowRecentFallback) {
        CircleCaptureService service = connected;
        if (!isReady()) {
            result.accept(null);
            return;
        }
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, service.getMainExecutor(),
                    new TakeScreenshotCallback() {
                        @Override public void onSuccess(ScreenshotResult screenshot) {
                            Bitmap copy = null;
                            try (HardwareBuffer buffer = screenshot.getHardwareBuffer()) {
                                Bitmap bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.getColorSpace());
                                if (bitmap != null) {
                                    try {
                                        copy = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                                    } finally {
                                        bitmap.recycle();
                                    }
                                }
                            } catch (RuntimeException unavailable) {
                                android.util.Log.w("PenDock", "Could not read screenshot", unavailable);
                            }
                            if (copy != null) cacheFrame(copy);
                            result.accept(copy);
                        }

                        @Override public void onFailure(int errorCode) {
                            android.util.Log.d("PenDock", "Circle capture rejected: " + errorCode);
                            if (allowRecentFallback && errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                                result.accept(copyRecent());
                                return;
                            }
                            result.accept(null);
                        }
                    });
        } catch (RuntimeException unavailable) {
            result.accept(allowRecentFallback ? copyRecent() : null);
        }
    }

    private static void cacheFrame(Bitmap source) {
        clearRecentFrame();
        try {
            recentFrame = source.copy(Bitmap.Config.ARGB_8888, false);
            recentTimeMs = SystemClock.uptimeMillis();
            main.removeCallbacks(clearRecent);
            main.postDelayed(clearRecent, CACHE_TTL_MS);
        } catch (RuntimeException ignored) { }
    }

    private static Bitmap copyRecent() {
        if (recentFrame == null || recentFrame.isRecycled()) return null;
        if (SystemClock.uptimeMillis() - recentTimeMs > CACHE_TTL_MS) {
            clearRecentFrame();
            return null;
        }
        try {
            return recentFrame.copy(Bitmap.Config.ARGB_8888, false);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void clearRecentFrame() {
        main.removeCallbacks(clearRecent);
        if (recentFrame != null) {
            if (!recentFrame.isRecycled()) recentFrame.recycle();
            recentFrame = null;
        }
    }

    @Override protected void onServiceConnected() { connected = this; }

    @Override public boolean onUnbind(Intent intent) {
        if (connected == this) {
            connected = null;
            clearRecentFrame();
        }
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        if (connected == this) {
            connected = null;
            clearRecentFrame();
        }
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
}
