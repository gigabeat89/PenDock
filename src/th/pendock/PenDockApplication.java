package th.pendock;

import android.app.Application;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

public final class PenDockApplication extends Application {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refresh = () -> BatteryWidget.refreshAll(this);
    private final SharedPreferences.OnSharedPreferenceChangeListener appearanceListener =
            (preferences, key) -> {
                if (Appearance.THEME.equals(key)) scheduleWidgetRefresh();
            };

    @Override public void onCreate() {
        super.onCreate();
        Appearance.preferences(this).registerOnSharedPreferenceChangeListener(appearanceListener);
        ContentObserver batteryListener = new ContentObserver(main) {
            @Override public void onChange(boolean selfChange) {
                scheduleWidgetRefresh();
            }
        };
        for (String key : PenBattery.SETTINGS_KEYS) {
            try {
                getContentResolver().registerContentObserver(Settings.Global.getUriFor(key), false, batteryListener);
            } catch (SecurityException ignored) {
                // Widgets still refresh on their system-provided schedule.
            }
        }
        scheduleWidgetRefresh();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        scheduleWidgetRefresh();
    }

    private void scheduleWidgetRefresh() {
        main.removeCallbacks(refresh);
        main.postDelayed(refresh, 200);
    }
}
