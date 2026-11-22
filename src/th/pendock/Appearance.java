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

    static String language(Context context) {
        return preferences(context).getString(LANGUAGE, "en");
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

    static final class Palette {
        final boolean dark;
        final int background, rail, surface, text, muted, border, accent, accentWash;
        final int petal, petalSelected, petalDisabled, core, ringBorder, ringShadow, disabledInk;

        Palette(boolean dark) {
            this.dark = dark;
            background = pick(0xfff7f8fc, 0xff11151f);
            rail = pick(0xffeceff7, 0xff181e2b);
            surface = pick(0xffffffff, 0xff1b2232);
            text = pick(0xff1b2030, 0xfff1f4ff);
            muted = pick(0xff5b6479, 0xffa9b3ca);
            border = pick(0xffdfe4ef, 0xff30394d);
            accent = pick(0xff3454d1, 0xffaabaff);
            accentWash = pick(0xffe0e7ff, 0xff273456);
            petal = pick(0xfff3f5fc, 0xff252e46);
            petalSelected = pick(0xffdce6ff, 0xff455f9d);
            petalDisabled = pick(0xffe5e9f3, 0xff202739);
            core = pick(0xfffdfdff, 0xff171f34);
            ringBorder = pick(0x281b2440, 0x668ba2cc);
            ringShadow = pick(0x42101a33, 0x99000000);
            disabledInk = pick(0xff687588, 0xff9ba7bc);
        }

        int pick(int light, int darkColor) { return dark ? darkColor : light; }
    }
}
