package th.pendock;

import android.content.Context;
import android.content.SharedPreferences;

final class RingConfig {
    static final String SIZE_SMALL = "small";
    static final String SIZE_NORMAL = "normal";
    static final String SIZE_LARGE = "large";

    static final String LAUNCH_FLOATING = "floating";
    static final String LAUNCH_SPLIT = "split";
    static final String LAUNCH_FULLSCREEN = "fullscreen";

    static final String WINDOW_COMPACT = "compact";
    static final String WINDOW_NORMAL = "normal";
    static final String WINDOW_LARGE = "large";

    private static final String RING_SIZE = "ring_size";
    private static final String LAUNCH_MODE = "launch_mode";
    private static final String WINDOW_SIZE = "floating_window_size";
    private static final String AUTO_START = "auto_start_boot";

    private RingConfig() { }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("tools", Context.MODE_PRIVATE);
    }

    static String ringSize(Context context) {
        String value = prefs(context).getString(RING_SIZE, SIZE_NORMAL);
        if (SIZE_SMALL.equals(value) || SIZE_LARGE.equals(value)) return value;
        return SIZE_NORMAL;
    }

    static void setRingSize(Context context, String size) {
        prefs(context).edit().putString(RING_SIZE, size).apply();
    }

    static float ringScale(Context context) {
        String size = ringSize(context);
        if (SIZE_SMALL.equals(size)) return 0.84f;
        if (SIZE_LARGE.equals(size)) return 1.18f;
        return 1.0f;
    }

    static String launchMode(Context context) {
        String value = prefs(context).getString(LAUNCH_MODE, LAUNCH_FLOATING);
        if (LAUNCH_FULLSCREEN.equals(value) || LAUNCH_SPLIT.equals(value)) return value;
        return LAUNCH_FLOATING;
    }

    static void setLaunchMode(Context context, String mode) {
        prefs(context).edit().putString(LAUNCH_MODE, mode).apply();
    }

    static boolean isFloatingLaunch(Context context) {
        return LAUNCH_FLOATING.equals(launchMode(context));
    }

    static boolean isSplitLaunch(Context context) {
        return LAUNCH_SPLIT.equals(launchMode(context));
    }

    static String windowSize(Context context) {
        String value = prefs(context).getString(WINDOW_SIZE, WINDOW_NORMAL);
        if (WINDOW_COMPACT.equals(value) || WINDOW_LARGE.equals(value)) return value;
        return WINDOW_NORMAL;
    }

    static void setWindowSize(Context context, String size) {
        prefs(context).edit().putString(WINDOW_SIZE, size).apply();
    }

    static int floatingWidth(Context context, int displayWidth) {
        String size = windowSize(context);
        if (WINDOW_COMPACT.equals(size)) return Math.min(Ui.dp(context, 380), displayWidth * 65 / 100);
        if (WINDOW_LARGE.equals(size)) return Math.min(Ui.dp(context, 600), displayWidth * 85 / 100);
        return Math.min(Ui.dp(context, 460), displayWidth * 3 / 4);
    }

    static int floatingHeight(Context context, int displayHeight) {
        String size = windowSize(context);
        if (WINDOW_COMPACT.equals(size)) return Math.min(Ui.dp(context, 520), displayHeight * 72 / 100);
        if (WINDOW_LARGE.equals(size)) return Math.min(Ui.dp(context, 760), displayHeight * 88 / 100);
        return Math.min(Ui.dp(context, 620), displayHeight * 4 / 5);
    }

    static boolean isAutoStart(Context context) {
        return prefs(context).getBoolean(AUTO_START, true);
    }

    static void setAutoStart(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(AUTO_START, enabled).apply();
    }
}
