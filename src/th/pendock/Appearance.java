package th.pendock;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.LocaleList;
import java.util.Locale;

final class Appearance {
    static final String THEME = "theme";
    static final String LANGUAGE = "language";
    static final String SYSTEM = "system";
    static final String LIGHT = "light";
    static final String DARK = "dark";

    private Appearance() { }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("appearance", Context.MODE_PRIVATE);
    }

    static String theme(Context context) {
        return preferences(context).getString(THEME, LIGHT);
    }

    static String languagePref(Context context) {
        String value = preferences(context).getString(LANGUAGE, SYSTEM);
        if ("th".equals(value) || "en".equals(value)) return value;
        return SYSTEM;
    }

    static String language(Context context) {
        String pref = languagePref(context);
        if ("th".equals(pref) || "en".equals(pref)) return pref;
        Locale sys = android.content.res.Resources.getSystem().getConfiguration().getLocales().get(0);
        return sys != null && "th".equalsIgnoreCase(sys.getLanguage()) ? "th" : "en";
    }

    static Context localize(Context context) {
        Configuration configuration = new Configuration();
        String mode = theme(context);
        Context application = context.getApplicationContext();
        int systemNight = (application == null ? context.getResources().getConfiguration()
                : application.getResources().getConfiguration()).uiMode & Configuration.UI_MODE_NIGHT_MASK;
        int night = LIGHT.equals(mode) ? Configuration.UI_MODE_NIGHT_NO
                : DARK.equals(mode) ? Configuration.UI_MODE_NIGHT_YES : systemNight;
        configuration.uiMode = night;
        Locale locale = Locale.forLanguageTag(language(context));
        configuration.setLocales(new LocaleList(locale));
        return context.createConfigurationContext(configuration);
    }

    static boolean isDark(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    static String text(Context context, int string, Object... arguments) {
        return localize(context).getString(string, arguments);
    }

    static Palette ringPalette(Context context) {
        return new Palette(RingConfig.isRingDark(context), RingConfig.ringHue(context));
    }

    static final class Palette {
        final boolean dark;
        final int background, rail, surface, text, muted, border, accent, accentWash;
        final int petal, petalSelected, petalDisabled, core, ringBorder, ringShadow, disabledInk;
        final int glassFill, glassHub, glassSlice, glassSheenAccent;

        Palette(boolean dark) {
            this(dark, RingConfig.HUE_DEFAULT);
        }

        Palette(boolean dark, int ringHue) {
            this.dark = dark;
            background = pick(0xfff7f8fc, 0xff11151f);
            rail = pick(0xffeceff7, 0xff181e2b);
            surface = pick(0xffffffff, 0xff1b2232);
            text = pick(0xff1b2030, 0xfff1f4ff);
            muted = pick(0xff5b6479, 0xffa9b3ca);
            border = pick(0xffdfe4ef, 0xff30394d);
            ringShadow = pick(0x42101a33, 0x99000000);
            disabledInk = pick(0xff687588, 0xff9ba7bc);

            if (ringHue < 0) {
                accent = pick(0xff3454d1, 0xffaabaff);
                accentWash = pick(0xffe0e7ff, 0xff273456);
                petal = pick(0xfff3f5fc, 0xff252e46);
                petalSelected = pick(0xffdce6ff, 0xff455f9d);
                petalDisabled = pick(0xffe5e9f3, 0xff202739);
                core = pick(0xfffdfdff, 0xff171f34);
                ringBorder = pick(0x281b2440, 0x668ba2cc);
                glassFill = pick(0x76ffffff, 0x6c1b263d);
                glassHub = pick(0x9cffffff, 0x8c182238);
                glassSlice = pick(0xffffffff, 0xff7da2ff);
                glassSheenAccent = 0x1c9cb8ff;
            } else {
                int lightAccent = RingConfig.ringAccentLight(ringHue);
                int darkAccent = RingConfig.ringAccentDark(ringHue);
                accent = pick(lightAccent, darkAccent);
                accentWash = pick(mix(0xffffffff, lightAccent, 0.14f), mix(0xff171b24, lightAccent, 0.28f));
                petal = pick(mix(0xffffffff, lightAccent, 0.12f), mix(0xff1a1f2b, lightAccent, 0.23f));
                petalSelected = pick(mix(0xffffffff, lightAccent, 0.27f), mix(0xff232a3a, lightAccent, 0.48f));
                petalDisabled = pick(mix(0xffe7ebf3, lightAccent, 0.05f), mix(0xff191e2a, lightAccent, 0.08f));
                core = pick(mix(0xffffffff, lightAccent, 0.06f), mix(0xff141822, lightAccent, 0.16f));
                ringBorder = pick(withAlpha(lightAccent, 0x5c), withAlpha(darkAccent, 0x8a));
                glassFill = pick(withAlpha(mix(0xffffffff, lightAccent, 0.19f), 0x80),
                        withAlpha(mix(0xff151a24, lightAccent, 0.34f), 0x76));
                glassHub = pick(withAlpha(mix(0xffffffff, lightAccent, 0.11f), 0xa4),
                        withAlpha(mix(0xff131722, lightAccent, 0.24f), 0x94));
                glassSlice = pick(mix(0xffffffff, lightAccent, 0.24f), darkAccent);
                glassSheenAccent = withAlpha(darkAccent, 0x2a);
            }
        }

        int pick(int light, int darkColor) { return dark ? darkColor : light; }

        private static int mix(int base, int tint, float weight) {
            int br = (base >> 16) & 255, bg = (base >> 8) & 255, bb = base & 255;
            int tr = (tint >> 16) & 255, tg = (tint >> 8) & 255, tb = tint & 255;
            int r = Math.round(br + (tr - br) * weight);
            int g = Math.round(bg + (tg - bg) * weight);
            int b = Math.round(bb + (tb - bb) * weight);
            return 0xff000000 | (r << 16) | (g << 8) | b;
        }

        private static int withAlpha(int color, int alpha) {
            return (color & 0x00ffffff) | ((alpha & 255) << 24);
        }
    }
}
