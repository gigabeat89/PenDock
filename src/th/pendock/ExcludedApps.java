package th.pendock;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

final class ExcludedApps {
    static final String HUAWEI_NOTES = "com.huawei.notepad";
    private static final String KEY = "packages";
    private static final Set<String> DEFAULTS = Collections.singleton(HUAWEI_NOTES);
    private final SharedPreferences preferences;

    ExcludedApps(Context context) {
        preferences = context.getSharedPreferences("excluded_apps", Context.MODE_PRIVATE);
    }

    Set<String> packages() { return preferences.getStringSet(KEY, DEFAULTS); }

    boolean contains(String packageName) {
        return packageName != null && packages().contains(packageName);
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
