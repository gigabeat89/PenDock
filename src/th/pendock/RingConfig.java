package th.pendock;

import android.content.Context;
import android.content.SharedPreferences;

final class RingConfig {
    static final String SIZE_SMALL = "small";
    static final String SIZE_NORMAL = "normal";
    static final String SIZE_LARGE = "large";

    static final String STYLE_PETALS = "petals";
    static final String STYLE_ORBIT = "orbit";
    static final String STYLE_HALO = "halo";
    static final String STYLE_HEXA = "hexa";
    static final String STYLE_DIAL = "dial";
    static final String STYLE_MINIMAL = "minimal";

    static final String THEME_APP = "app";
    static final String THEME_LIGHT = "light";
    static final String THEME_DARK = "dark";

    static final int HUE_DEFAULT = -1;

    static final String LAUNCH_FLOATING = "floating";
    static final String LAUNCH_SPLIT = "split";
    static final String LAUNCH_FULLSCREEN = "fullscreen";

    static final String WINDOW_COMPACT = "compact";
    static final String WINDOW_NORMAL = "normal";
    static final String WINDOW_LARGE = "large";

    private static final String RING_SIZE = "ring_size";
    private static final String RING_STYLE = "ring_style";
    private static final String RING_THEME = "ring_theme";
    private static final String RING_HUE = "ring_color_hue";
    private static final String RING_DIM = "ring_dim_percent";
    private static final String RING_BLUR = "ring_blur_dp";
    private static final String LAUNCH_MODE = "launch_mode";
    private static final String WINDOW_SIZE = "floating_window_size";
    private static final String AUTO_START = "auto_start_boot";
    private static final String CIRCLE_SHARE = "circle_show_share";
    private static final String CIRCLE_COPY = "circle_show_copy";
    private static final String CIRCLE_SAVE = "circle_show_save";
    static final int MAX_DIM_PERCENT = 70;
    static final int MAX_BLUR_DP = 40;

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

    static String ringStyle(Context context) {
        String value = prefs(context).getString(RING_STYLE, STYLE_PETALS);
        if (STYLE_ORBIT.equals(value) || STYLE_HALO.equals(value)
                || STYLE_HEXA.equals(value) || STYLE_DIAL.equals(value)
                || STYLE_MINIMAL.equals(value)) {
            return value;
        }
        return STYLE_PETALS;
    }

    static void setRingStyle(Context context, String style) {
        prefs(context).edit().putString(RING_STYLE, style).apply();
    }

    static String ringTheme(Context context) {
        String value = prefs(context).getString(RING_THEME, THEME_APP);
        if (THEME_LIGHT.equals(value) || THEME_DARK.equals(value)) return value;
        return THEME_APP;
    }

    static void setRingTheme(Context context, String theme) {
        prefs(context).edit().putString(RING_THEME, theme).apply();
    }

    static boolean isRingDark(Context context) {
        String mode = ringTheme(context);
        if (THEME_DARK.equals(mode)) return true;
        if (THEME_LIGHT.equals(mode)) return false;
        String appTheme = Appearance.theme(context);
        if (Appearance.DARK.equals(appTheme)) return true;
        if (Appearance.LIGHT.equals(appTheme)) return false;
        Context app = context.getApplicationContext();
        int uiMode = (app == null ? context.getResources().getConfiguration()
                : app.getResources().getConfiguration()).uiMode;
        return (uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    static int ringHue(Context context) {
        int hue = prefs(context).getInt(RING_HUE, HUE_DEFAULT);
        if (hue < 0) return HUE_DEFAULT;
        return hue % 360;
    }

    static void setRingHue(Context context, int hue) {
        int stored = hue < 0 ? HUE_DEFAULT : (hue % 360);
        prefs(context).edit().putInt(RING_HUE, stored).apply();
    }

    static int ringAccentLight(int hue) {
        if (hue < 0) return 0xff3454d1;
        return android.graphics.Color.HSVToColor(new float[] {hue % 360, 0.78f, 0.80f});
    }

    static int ringAccentDark(int hue) {
        if (hue < 0) return 0xffaabaff;
        return android.graphics.Color.HSVToColor(new float[] {hue % 360, 0.50f, 0.96f});
    }

    static int ringPreviewColor(int hue) {
        if (hue < 0) return 0xffeef2f8;
        return android.graphics.Color.HSVToColor(new float[] {hue % 360, 0.82f, 0.88f});
    }

    static int ringDimPercent(Context context) {
        int value = prefs(context).getInt(RING_DIM, 0);
        return Math.max(0, Math.min(MAX_DIM_PERCENT, value));
    }

    static void setRingDimPercent(Context context, int percent) {
        int clamped = Math.max(0, Math.min(MAX_DIM_PERCENT, percent));
        prefs(context).edit().putInt(RING_DIM, clamped).apply();
    }

    static float ringDimAlpha(Context context) {
        return ringDimPercent(context) / 100f;
    }

    static int ringBlurDp(Context context) {
        int value = prefs(context).getInt(RING_BLUR, 0);
        return Math.max(0, Math.min(MAX_BLUR_DP, value));
    }

    static void setRingBlurDp(Context context, int dp) {
        int clamped = Math.max(0, Math.min(MAX_BLUR_DP, dp));
        prefs(context).edit().putInt(RING_BLUR, clamped).apply();
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

    static boolean isCircleShareShown(Context context) {
        return prefs(context).getBoolean(CIRCLE_SHARE, true);
    }

    static void setCircleShareShown(Context context, boolean shown) {
        prefs(context).edit().putBoolean(CIRCLE_SHARE, shown).apply();
    }

    static boolean isCircleCopyShown(Context context) {
        return prefs(context).getBoolean(CIRCLE_COPY, true);
    }

    static void setCircleCopyShown(Context context, boolean shown) {
        prefs(context).edit().putBoolean(CIRCLE_COPY, shown).apply();
    }

    static boolean isCircleSaveShown(Context context) {
        return prefs(context).getBoolean(CIRCLE_SAVE, true);
    }

    static void setCircleSaveShown(Context context, boolean shown) {
        prefs(context).edit().putBoolean(CIRCLE_SAVE, shown).apply();
    }
}
