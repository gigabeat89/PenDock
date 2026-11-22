package th.pendock;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import java.util.function.Consumer;

public class CircleCaptureService extends AccessibilityService {
    private static CircleCaptureService connected;

    static boolean isReady() {
        return Build.VERSION.SDK_INT >= 30 && connected != null;
    }

    static void capture(Consumer<Bitmap> result) {
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
                            result.accept(copy);
                        }

                        @Override public void onFailure(int errorCode) {
                            android.util.Log.d("PenDock", "Circle capture rejected: " + errorCode);
                            result.accept(null);
                        }
                    });
        } catch (RuntimeException unavailable) {
            result.accept(null);
        }
    }

    @Override protected void onServiceConnected() { connected = this; }

    @Override public boolean onUnbind(Intent intent) {
        if (connected == this) connected = null;
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        if (connected == this) connected = null;
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
}
