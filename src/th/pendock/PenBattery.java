package th.pendock;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

final class PenBattery {
    private static final String CONNECTION = "huawei_bt_pencil_connection_state";
    private static final String LEVEL = "huawei_bt_pencil_battery_level";
    private static final String MODEL = "huawei_bt_pencil_model_number";
    private static final String CHARGING = "huawei_bt_pencil_charging_state";
    static final String[] SETTINGS_KEYS = { CONNECTION, LEVEL, MODEL, CHARGING };

    final int level;
    final String model;
    final boolean charging;

    private PenBattery(int level, String model, boolean charging) {
        this.level = level;
        this.model = model;
        this.charging = charging;
    }

    boolean available() { return level >= 0; }

    static PenBattery read(Context context) {
        ContentResolver resolver = context.getContentResolver();
        int level = level(resolver);
        if (level < 0) {
            return new PenBattery(-1, "", false);
        }
        String model = null;
        try {
            model = Settings.Global.getString(resolver, MODEL);
        } catch (SecurityException ignored) {
            // Some Huawei firmware restricts accessory metadata.
        }
        if (model != null) {
            model = model.trim();
            if (model.regionMatches(true, 0, "HUAWEI ", 0, 7)) model = model.substring(7).trim();
        }
        return new PenBattery(level, model == null || model.isEmpty() ? "M-Pencil" : model,
                readInt(resolver, CHARGING) == 1);
    }

    static int level(ContentResolver resolver) {
        int connection = readInt(resolver, CONNECTION);
        int level = readInt(resolver, LEVEL);
        return (connection == -1 || connection == 2) && level >= 0 && level <= 100 ? level : -1;
    }

    private static int readInt(ContentResolver resolver, String key) {
        try {
            return Settings.Global.getInt(resolver, key, -1);
        } catch (SecurityException ignored) {
            return -1;
        }
    }
}
