package th.pendock;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

final class ExcludedApps {
    private static final String KEY = "packages";
    private static final Set<String> DEFAULTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "com.huawei.hicreate", "com.huawei.notepad", "com.huawei.hinote")));
    private final SharedPreferences preferences;
    private final PackageManager packageManager;

    ExcludedApps(Context context) {
        preferences = context.getSharedPreferences("excluded_apps", Context.MODE_PRIVATE);
        packageManager = context.getPackageManager();
    }

    Set<String> packages() {
        Set<String> saved = preferences.getStringSet(KEY, null);
        if (saved != null) return saved;

        Set<String> installed = new HashSet<>();
        for (String packageName : DEFAULTS) {
            try {
                packageManager.getApplicationInfo(packageName, 0);
                installed.add(packageName);
            } catch (PackageManager.NameNotFoundException unavailable) {
                // Some MatePad models do not ship every default app.
            }
        }
        return installed;
    }

    boolean contains(String packageName) {
        return packageName != null && preferences.getStringSet(KEY, DEFAULTS).contains(packageName);
    }

    boolean add(String packageName) {
        Set<String> updated = new HashSet<>(packages());
        if (!updated.add(packageName)) return false;
        save(updated);
        return true;
    }

    void remove(String packageName) {
        Set<String> updated = new HashSet<>(packages());
        if (updated.remove(packageName)) save(updated);
    }

    private void save(Set<String> packages) {
        preferences.edit().putStringSet(KEY, packages).apply();
        WindowModeMonitor.refreshTracking();
    }
}
