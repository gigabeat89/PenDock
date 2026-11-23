package th.pendock;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.util.LruCache;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/** Associates activity announcements with window geometry without retrieving UI nodes. */
public class WindowModeMonitor extends AccessibilityService {
    private static final String RECENT_PREFS = "recent_apps";
    private static final String RECENT_KEY = "packages";
    private static final int MAX_RECENT = 12;
    private static final LinkedList<String> recent = new LinkedList<>();
    private static boolean recentLoaded;
    private static WindowModeMonitor connected;

    private final LruCache<Integer, App> activities = new LruCache<>(32);
    private boolean tracking;

    static boolean isReady() { return connected != null; }

    static void recordRecent(Context context, String packageName) {
        if (packageName == null || !ActiveWindow.isAppWindow(context, packageName)) return;
        if (context.getPackageManager().getLaunchIntentForPackage(packageName) == null) return;
        synchronized (recent) {
            ensureRecentLoaded(context);
            if (!recent.isEmpty() && packageName.equals(recent.getFirst())) return;
            recent.remove(packageName);
            recent.addFirst(packageName);
            while (recent.size() > MAX_RECENT) recent.removeLast();
            saveRecent(context);
        }
    }

    static List<String> recentPackages(Context context) {
        synchronized (recent) {
            ensureRecentLoaded(context);
            return new ArrayList<>(recent);
        }
    }

    private static void ensureRecentLoaded(Context context) {
        if (recentLoaded) return;
        recentLoaded = true;
        SharedPreferences prefs = context.getSharedPreferences(RECENT_PREFS, Context.MODE_PRIVATE);
        String saved = prefs.getString(RECENT_KEY, "");
        if (saved == null || saved.isEmpty()) return;
        for (String pkg : saved.split("\\|")) {
            if (!pkg.isEmpty() && !recent.contains(pkg) && recent.size() < MAX_RECENT) {
                recent.addLast(pkg);
            }
        }
    }

    private static void saveRecent(Context context) {
        context.getSharedPreferences(RECENT_PREFS, Context.MODE_PRIVATE).edit()
                .putString(RECENT_KEY, String.join("|", recent)).apply();
    }

    static void refreshTracking() {
        if (connected != null) connected.configure();
    }

    static FocusedApp focusedApp() {
        return connected == null || !connected.tracking ? null : connected.readFocusedApp();
    }

    static Set<String> floatingPackages() {
        return connected == null || !connected.tracking ? new HashSet<>() : connected.readFloatingPackages();
    }

    private Set<String> readFloatingPackages() {
        Set<String> result = new HashSet<>();
        List<AccessibilityWindowInfo> windows = getWindows();
        try {
            Rect display = usableDisplay();
            for (AccessibilityWindowInfo window : windows) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION
                        || window.isInPictureInPictureMode()) continue;
                Rect bounds = new Rect();
                window.getBoundsInScreen(bounds);
                if (bounds.isEmpty()) continue;
                boolean spansWidth = spansAxis(bounds.left, bounds.right, display.left, display.right);
                boolean spansHeight = spansAxis(bounds.top, bounds.bottom, display.top, display.bottom);
                if (!spansWidth && !spansHeight) {
                    App app = activities.get(window.getId());
                    if (app != null && app.activity != null) result.add(app.activity.getPackageName());
                }
            }
            return result;
        } finally {
            for (AccessibilityWindowInfo window : windows) window.recycle();
        }
    }

    private void configure() {
        tracking = SystemScreenshot.tracksActiveApp()
                || !new ExcludedApps(this).packages().isEmpty();
        if (!tracking) activities.evictAll();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.eventTypes = tracking ? AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOWS_CHANGED : 0;
        setServiceInfo(info);
    }

    private FocusedApp readFocusedApp() {
        List<AccessibilityWindowInfo> windows = getWindows();
        try {
            for (AccessibilityWindowInfo window : windows) {
                if (!window.isFocused() || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                Rect bounds = new Rect();
                window.getBoundsInScreen(bounds);
                if (bounds.isEmpty()) return null;
                Rect display = usableDisplay();
                boolean spansWidth = spansAxis(bounds.left, bounds.right, display.left, display.right);
                boolean spansHeight = spansAxis(bounds.top, bounds.bottom, display.top, display.bottom);
                // Huawei can letterbox a fullscreen app on one axis, especially in Magic Window.
                boolean spansDisplay = spansWidth || spansHeight;
                boolean windowed = window.isInPictureInPictureMode() || !spansDisplay;
                boolean split = !windowed && (spansWidth != spansHeight)
                        && isDockedSplit(bounds, display, spansWidth);
                App app = activities.get(window.getId());
                return new FocusedApp(app, windowed, split);
            }
            return null;
        } finally {
            for (AccessibilityWindowInfo window : windows) window.recycle();
        }
    }

    private boolean isDockedSplit(Rect bounds, Rect display, boolean spansWidth) {
        if (spansWidth) {
            int tolerance = Math.max(Ui.dp(this, 18), display.height() / 20);
            boolean touchesEdge = Math.abs(bounds.top - display.top) <= tolerance
                    || Math.abs(bounds.bottom - display.bottom) <= tolerance;
            return touchesEdge && bounds.height() <= display.height() * 78 / 100;
        }
        int tolerance = Math.max(Ui.dp(this, 18), display.width() / 20);
        boolean touchesEdge = Math.abs(bounds.left - display.left) <= tolerance
                || Math.abs(bounds.right - display.right) <= tolerance;
        return touchesEdge && bounds.width() <= display.width() * 78 / 100;
    }

    private boolean spansAxis(int start, int end, int displayStart, int displayEnd) {
        int tolerance = Math.max(Ui.dp(this, 18), (displayEnd - displayStart) / 20);
        return Math.abs(start - displayStart) <= tolerance
                && Math.abs(end - displayEnd) <= tolerance;
    }

    static final class FocusedApp {
        final ComponentName activity;
        final Intent floatingIntent;
        final boolean alreadyWindowed;
        final boolean alreadySplit;

        FocusedApp(App app, boolean windowed, boolean split) {
            activity = app == null ? null : app.activity;
            floatingIntent = app == null || app.floatingIntent == null ? null : new Intent(app.floatingIntent);
            alreadyWindowed = windowed;
            alreadySplit = split;
        }
    }

    private static final class App {
        final ComponentName activity;
        final Intent floatingIntent;

        App(ComponentName activity, Intent floatingIntent) {
            this.activity = activity;
            this.floatingIntent = floatingIntent;
        }
    }

    private Rect usableDisplay() {
        WindowManager manager = getSystemService(WindowManager.class);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowMetrics metrics = manager.getMaximumWindowMetrics();
            Rect bounds = new Rect(metrics.getBounds());
            Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            bounds.inset(insets.left, insets.top, insets.right, insets.bottom);
            return bounds;
        }
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        manager.getDefaultDisplay().getMetrics(metrics);
        return new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        connected = this;
        configure();
        SystemScreenshot.refreshTracking();
    }

    @Override public boolean onUnbind(Intent intent) {
        if (connected == this) {
            connected = null;
            SystemScreenshot.refreshTracking();
        }
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        if (connected == this) {
            connected = null;
            SystemScreenshot.refreshTracking();
        }
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!tracking) return;
        int id = event.getWindowId();
        if (id < 0) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if ((event.getWindowChanges() & AccessibilityEvent.WINDOWS_CHANGE_REMOVED) != 0) {
                activities.remove(id);
            }
            return;
        }
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getPackageName() == null || event.getClassName() == null) return;
        String pkg = event.getPackageName().toString();
        ComponentName next = new ComponentName(pkg, ActiveWindow.fullClassName(pkg, event.getClassName().toString()));
        App previous = activities.get(id);
        if (previous != null && next.equals(previous.activity)) return;
        try {
            getPackageManager().getActivityInfo(next, 0);
            activities.put(id, new App(next, ActiveWindow.floatingLauncher(this, next)));
            recordRecent(this, pkg);
        } catch (PackageManager.NameNotFoundException ignored) {
            // A caption, menu or dialog can announce itself for the same window.
        }
    }
    @Override public void onInterrupt() { }
}
