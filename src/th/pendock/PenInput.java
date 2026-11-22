package th.pendock;

import android.content.Context;
import android.view.InputDevice;
import android.view.MotionEvent;
import java.util.Locale;

final class PenInput {
    private static final String PEN_ONLY_MENU = "pen_only_menu";
    private static final String REQUIRE_NEAR_SCREEN = "require_near_screen";

    static boolean isPenOnlyMenu(Context context) {
        return context.getSharedPreferences("tools", Context.MODE_PRIVATE)
                .getBoolean(PEN_ONLY_MENU, true);
    }

    static void setPenOnlyMenu(Context context, boolean enabled) {
        context.getSharedPreferences("tools", Context.MODE_PRIVATE).edit()
                .putBoolean(PEN_ONLY_MENU, enabled).apply();
    }

    static boolean requiresNearScreen(Context context) {
        return context.getSharedPreferences("tools", Context.MODE_PRIVATE)
                .getBoolean(REQUIRE_NEAR_SCREEN, false);
    }

    static void setRequiresNearScreen(Context context, boolean enabled) {
        context.getSharedPreferences("tools", Context.MODE_PRIVATE).edit()
                .putBoolean(REQUIRE_NEAR_SCREEN, enabled).apply();
    }

    static boolean isButtonPress(MotionEvent event) {
        return event.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS
                && (event.getActionButton() & (MotionEvent.BUTTON_STYLUS_PRIMARY
                        | MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0;
    }

    static boolean isPen(MotionEvent event, int index) {
        int tool = event.getToolType(index);
        return tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER;
    }

    static boolean isTouchpad(MotionEvent event) {
        if (event.isFromSource(InputDevice.SOURCE_TOUCHPAD)) return true;
        InputDevice device = event.getDevice();
        if (device == null) return false;
        // Huawei combines the Glide Keyboard and its touchpad into one Android input device.
        String name = device.getName().toLowerCase(Locale.ROOT);
        return name.contains("touchpad") || name.contains("trackpad")
                || name.startsWith("huawei glide keyboard");
    }
}
