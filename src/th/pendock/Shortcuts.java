package th.pendock;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.util.LruCache;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Shortcuts {
    static final int LIMIT = 10;
    private static final String RING_KEY = "ring_items";
    private static final String LEGACY_KEY = "apps";
    private final Context context;
    private final PackageManager packages;
    private final LruCache<String, Drawable> iconCache = new LruCache<>(128);
    private volatile List<Entry> launcherCache;
    private volatile Map<String, Entry> launcherMap;

    enum Tool {
        LOCK("tool:lock", R.string.lock_screen, R.drawable.ic_lock),
        SCREENSHOT("tool:screenshot", R.string.capture_short, R.drawable.ic_capture),
        CIRCLE_SEARCH("tool:circle_search", R.string.circle_search, R.drawable.ic_circle_search),
        FLOAT_WINDOW("tool:float_window", R.string.float_window, R.drawable.ic_float),
        SPLIT_SCREEN("tool:split_screen", R.string.split_screen, R.drawable.ic_split),
        LASER_POINTER("tool:laser_pointer", R.string.laser_tool, R.drawable.ic_laser),
        TRANSLATE("tool:translate", R.string.translate_tool, R.drawable.ic_translate),
        PIN_SCREEN("tool:pin_screen", R.string.pin_tool, R.drawable.ic_pin),
        EXTRACT_TEXT("tool:extract_text", R.string.extract_tool, R.drawable.ic_extract_text),
        COLOR_PICKER("tool:color_picker", R.string.color_picker_tool, R.drawable.ic_eyedropper);

        final String id;
        final int label;
        final int icon;

        Tool(String id, int label, int icon) {
            this.id = id;
            this.label = label;
            this.icon = icon;
        }
    }

    Shortcuts(Context context) {
        this.context = context;
        packages = context.getPackageManager();
    }

    SharedPreferences preferences() {
        return context.getSharedPreferences("shortcuts", Context.MODE_PRIVATE);
    }

    List<Entry> selected() {
        SharedPreferences settings = preferences();
        String saved = settings.getString(RING_KEY, null);
        if (saved == null) {
            // Preserve the order used by the ring before tools became configurable.
            saved = Tool.LOCK.id + "|" + Tool.SCREENSHOT.id + "|" + Tool.CIRCLE_SEARCH.id
                    + "|" + settings.getString(LEGACY_KEY, "") + "|" + Tool.FLOAT_WINDOW.id;
        }
        List<Entry> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String id : saved.split("\\|")) {
            if (result.size() >= LIMIT) break;
            if (id.isEmpty() || !seen.add(id)) continue;
            Tool tool = findTool(id);
            if (tool != null) {
                result.add(toolEntry(tool));
            } else {
                Entry app = loadApp(id, true);
                if (app != null) result.add(app);
            }
        }
        return result;
    }

    private Tool findTool(String id) {
        if (!id.startsWith("tool:")) return null;
        for (Tool tool : Tool.values()) if (tool.id.equals(id)) return tool;
        return null;
    }

    Entry toolEntry(Tool tool) {
        return new Entry(tool.id, context.getString(tool.label), context.getDrawable(tool.icon).mutate(), tool);
    }

    List<Entry> available() {
        Set<String> selectedPackages = new HashSet<>();
        for (Entry entry : selected()) if (entry.tool == null) selectedPackages.add(entry.id);
        return availableExcept(selectedPackages);
    }

    void invalidateCache() {
        launcherCache = null;
        launcherMap = null;
        iconCache.evictAll();
    }

    void warmUp() {
        List<Entry> all = allLauncherApps();
        int preload = Math.min(all.size(), 24);
        for (int i = 0; i < preload; i++) {
            loadIcon(all.get(i).id);
        }
    }

    List<Entry> cachedAvailableExcept(Set<String> excluded) {
        List<Entry> cached = launcherCache;
        if (cached == null) return null;
        return filterCached(cached, excluded);
    }

    Entry cachedDescribe(String packageName) {
        Map<String, Entry> map = launcherMap;
        if (map == null) return null;
        Entry base = map.get(packageName);
        if (base == null) return null;
        Drawable icon = iconCache.get(packageName);
        if (icon == null) return null;
        return new Entry(base.id, base.label, icon, null);
    }

    List<Entry> availableExcept(Set<String> excluded) {
        return filterCached(allLauncherApps(), excluded);
    }

    private List<Entry> allLauncherApps() {
        List<Entry> cached = launcherCache;
        if (cached != null) return cached;
        Set<String> seen = new HashSet<>();
        seen.add(context.getPackageName());
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<Entry> loaded = new ArrayList<>();
        Map<String, Entry> byPkg = new HashMap<>();
        for (ResolveInfo entry : packages.queryIntentActivities(launcher, 0)) {
            ApplicationInfo info = entry.activityInfo.applicationInfo;
            if (!seen.add(info.packageName)) continue;
            Entry item = new Entry(info.packageName, info.loadLabel(packages).toString(), null, null);
            loaded.add(item);
            byPkg.put(info.packageName, item);
        }
        Collator order = Collator.getInstance(context.getResources().getConfiguration().getLocales().get(0));
        loaded.sort((first, second) -> order.compare(first.label, second.label));
        List<Entry> immutable = Collections.unmodifiableList(loaded);
        launcherMap = Collections.unmodifiableMap(byPkg);
        launcherCache = immutable;
        return immutable;
    }

    private List<Entry> filterCached(List<Entry> source, Set<String> excluded) {
        List<Entry> result = new ArrayList<>(source.size());
        for (Entry item : source) {
            if (excluded.contains(item.id)) continue;
            result.add(new Entry(item.id, item.label, iconCache.get(item.id), null));
        }
        return result;
    }

    Drawable cachedIcon(String packageName) {
        return iconCache.get(packageName);
    }

    Drawable loadIcon(String packageName) {
        Drawable cached = iconCache.get(packageName);
        if (cached != null) return cached;
        try {
            Drawable icon = packages.getApplicationInfo(packageName, 0).loadIcon(packages);
            if (icon != null) iconCache.put(packageName, icon);
            return icon;
        } catch (PackageManager.NameNotFoundException unavailable) {
            return packages.getDefaultActivityIcon();
        }
    }

    Entry describe(String packageName) {
        Entry entry = loadApp(packageName, false);
        return entry == null ? new Entry(packageName, packageName, packages.getDefaultActivityIcon(), null) : entry;
    }

    private Entry loadApp(String packageName, boolean requireLauncher) {
        try {
            ApplicationInfo info = packages.getApplicationInfo(packageName, 0);
            if (requireLauncher && packages.getLaunchIntentForPackage(packageName) == null) return null;
            Drawable icon = iconCache.get(packageName);
            if (icon == null) {
                icon = info.loadIcon(packages);
                if (icon != null) iconCache.put(packageName, icon);
            }
            return new Entry(packageName, info.loadLabel(packages).toString(), icon, null);
        } catch (PackageManager.NameNotFoundException unavailable) {
            return null;
        }
    }

    boolean add(Entry entry) {
        List<Entry> entries = selected();
        if (entries.size() >= LIMIT) return false;
        for (Entry current : entries) if (current.id.equals(entry.id)) return false;
        entries.add(entry);
        save(entries);
        return true;
    }

    boolean moveTo(String id, int destination) {
        List<Entry> entries = selected();
        if (destination < 0 || destination >= entries.size()) return false;
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            if (!entry.id.equals(id)) continue;
            if (index == destination) return false;
            entries.remove(index);
            entries.add(destination, entry);
            save(entries);
            return true;
        }
        return false;
    }

    void remove(String id) {
        List<Entry> entries = selected();
        if (entries.removeIf(entry -> entry.id.equals(id))) save(entries);
    }

    private void save(List<Entry> entries) {
        List<String> ids = new ArrayList<>(entries.size());
        for (Entry entry : entries) ids.add(entry.id);
        preferences().edit().putString(RING_KEY, String.join("|", ids)).apply();
    }

    static final class Entry {
        final String id;
        final String label;
        final Drawable icon;
        final Tool tool;

        Entry(String id, String label, Drawable icon, Tool tool) {
            this.id = id;
            this.label = label;
            this.icon = icon;
            this.tool = tool;
        }
    }
}
