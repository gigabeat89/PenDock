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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class CircleCaptureService extends AccessibilityService {
    private static final long CACHE_TTL_MS = 2500L;
    private static CircleCaptureService connected;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService captureWorker = Executors.newSingleThreadExecutor();
    private static Bitmap recentFrame;
    private static long recentTimeMs;
    private static final Runnable clearRecent = CircleCaptureService::clearRecentFrame;

    static boolean isReady() {
        return Build.VERSION.SDK_INT >= 30 && connected != null;
    }

    static void capture(Consumer<Bitmap> result) {
        capture(result, false, false, true);
    }

    static void captureOrRecent(Consumer<Bitmap> result) {
        capture(result, true, false, true);
    }

    /** Fast 1/4-resolution capture on a background thread for GPU RenderEffect backdrop blur. */
    static void captureFastForBlur(Consumer<Bitmap> result) {
        capture(result, true, true, false);
    }

    private static void capture(Consumer<Bitmap> result, boolean allowRecentFallback,
            boolean downscaleForBlur, boolean retryOnShortInterval) {
        CircleCaptureService service = connected;
        if (!isReady()) {
            result.accept(null);
            return;
        }
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, captureWorker,
                    new TakeScreenshotCallback() {
                        @Override public void onSuccess(ScreenshotResult screenshot) {
                            Bitmap output = null;
                            try (HardwareBuffer buffer = screenshot.getHardwareBuffer()) {
                                Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, screenshot.getColorSpace());
                                if (hw != null) {
                                    try {
                                        if (downscaleForBlur) {
                                            int sw = Math.max(1, hw.getWidth() / 4);
                                            int sh = Math.max(1, hw.getHeight() / 4);
                                            Bitmap soft = hw.copy(Bitmap.Config.ARGB_8888, false);
                                            if (soft != null) {
                                                try {
                                                    output = Bitmap.createScaledBitmap(soft, sw, sh, true);
                                                } finally {
                                                    if (output != soft) soft.recycle();
                                                }
                                            }
                                        } else {
                                            output = hw.copy(Bitmap.Config.ARGB_8888, false);
                                        }
                                    } finally {
                                        hw.recycle();
                                    }
                                }
                            } catch (RuntimeException unavailable) {
                                android.util.Log.w("PenDock", "Could not read screenshot", unavailable);
                            }
                            if (output != null && !downscaleForBlur) {
                                Bitmap cachedCopy = null;
                                try {
                                    cachedCopy = output.copy(Bitmap.Config.ARGB_8888, false);
                                } catch (RuntimeException ignored) { }
                                final Bitmap toCache = cachedCopy;
                                final Bitmap delivered = output;
                                main.post(() -> {
                                    if (toCache != null) storeRecentFrame(toCache);
                                    result.accept(delivered);
                                });
                                return;
                            }
                            final Bitmap delivered = output;
                            main.post(() -> result.accept(delivered));
                        }

                        @Override public void onFailure(int errorCode) {
                            android.util.Log.d("PenDock", "Circle capture rejected: " + errorCode);
                            main.post(() -> {
                                if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                                    if (allowRecentFallback) {
                                        Bitmap cached = copyRecent();
                                        if (cached != null) {
                                            result.accept(cached);
                                            return;
                                        }
                                    }
                                    if (retryOnShortInterval) {
                                        main.postDelayed(() -> capture(result, allowRecentFallback,
                                                downscaleForBlur, false), 450);
                                        return;
                                    }
                                }
                                result.accept(null);
                            });
                        }
                    });
        } catch (RuntimeException unavailable) {
            result.accept(allowRecentFallback ? copyRecent() : null);
        }
    }

    private static void storeRecentFrame(Bitmap copy) {
        clearRecentFrame();
        recentFrame = copy;
        recentTimeMs = SystemClock.uptimeMillis();
        main.removeCallbacks(clearRecent);
        main.postDelayed(clearRecent, CACHE_TTL_MS);
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
