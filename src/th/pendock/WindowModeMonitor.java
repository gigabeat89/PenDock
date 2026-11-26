package th.pendock;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Associates activity announcements with window geometry without retrieving UI nodes. */
public class WindowModeMonitor extends AccessibilityService {
    interface FloatingPingListener {
        void onFloatingWindowPinged(int windowId, String packageName, Rect rawBounds);
    }

    static final class FloatingWindow {
        final int windowId;
        final Rect bounds;

        FloatingWindow(int windowId, Rect bounds) {
            this.windowId = windowId;
            this.bounds = new Rect(bounds);
        }
    }

    private static final String RECENT_PREFS = "recent_apps";
    private static final String RECENT_KEY = "packages";
    private static final int MAX_RECENT = 12;
    private static final long HOME_CLICK_WINDOW_MS = 320L;
    private static final long FOCUS_BOUNCE_WINDOW_MS = 200L;
    private static final long PULSE_COOLDOWN_MS = 650L;
    private static final LinkedList<String> recent = new LinkedList<>();
    private static boolean recentLoaded;
    private static WindowModeMonitor connected;
    private static volatile FloatingPingListener floatingPingListener;

    private final LruCache<Integer, App> activities = new LruCache<>(32);
    private final Set<Integer> homeClickFloatingIds = new HashSet<>();
    private boolean tracking;
    private String homePackage = "com.huawei.android.launcher";
    private int homeWindowId = -1;
    private int lastFocusedWindowId = -1;
    private boolean lastFocusedWasFloating;
    private boolean lastFocusedWasHome;
    private int bouncedFromFloatingId = -1;
    private long bouncedToHomeMs;
    private long lastHomeClickMs;
    private int lastRefocusedId = -1;
    private String lastRefocusedPkg;
    private Rect lastRefocusedBounds;
    private long lastRefocusedMs;
    private long lastPulseMs;

    static boolean isReady() { return connected != null; }

    static void setFloatingPingListener(FloatingPingListener listener) {
        floatingPingListener = listener;
    }

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

    static final class Snapshot {
        final FocusedApp focused;
        final Map<String, FloatingWindow> floating;
        final Set<String> visible;

        Snapshot(FocusedApp focused, Map<String, FloatingWindow> floating, Set<String> visible) {
            this.focused = focused;
            this.floating = floating;
            this.visible = visible;
        }
    }

    /** Reads focused app, floating windows, and visible packages in a single getWindows() IPC pass. */
    static Snapshot snapshot() {
        if (connected == null || !connected.tracking) {
            return new Snapshot(null, new HashMap<>(), new HashSet<>());
        }
        return connected.readSnapshot();
    }

    private Snapshot readSnapshot() {
        FocusedApp focused = null;
        Map<String, FloatingWindow> floating = new HashMap<>();
        Set<String> visible = new HashSet<>();
        List<AccessibilityWindowInfo> windows = getWindows();
        try {
            Rect display = usableDisplay();
            for (AccessibilityWindowInfo window : windows) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                String pkg = windowPackageName(window);
                if (pkg != null && !isHomePackage(pkg)) visible.add(pkg);

                Rect bounds = new Rect();
                window.getBoundsInScreen(bounds);
                if (bounds.isEmpty()) continue;
                boolean spansWidth = spansAxis(bounds.left, bounds.right, display.left, display.right);
                boolean spansHeight = spansAxis(bounds.top, bounds.bottom, display.top, display.bottom);
                boolean spansDisplay = spansWidth || spansHeight;

                if (!window.isInPictureInPictureMode() && !spansDisplay) {
                    if (pkg != null && !floating.containsKey(pkg)) {
                        floating.put(pkg, new FloatingWindow(window.getId(), bounds));
                    }
                }
                if (focused == null && window.isFocused()) {
                    boolean windowed = window.isInPictureInPictureMode() || !spansDisplay;
                    boolean split = !windowed && (spansWidth != spansHeight)
                            && isDockedSplit(bounds, display, spansWidth);
                    App app = activities.get(window.getId());
                    focused = new FocusedApp(app, windowed, split, bounds);
                }
            }
            return new Snapshot(focused, floating, visible);
        } finally {
            for (AccessibilityWindowInfo window : windows) window.recycle();
        }
    }

    static FocusedApp focusedApp() {
        return connected == null || !connected.tracking ? null : connected.readFocusedApp();
    }

    static Set<String> floatingPackages() {
        return connected == null || !connected.tracking ? new HashSet<>() : connected.readFloatingPackages();
    }

    static Set<String> visibleAppPackages() {
        return connected == null || !connected.tracking ? new HashSet<>() : connected.readVisibleAppPackages();
    }

    private Set<String> readVisibleAppPackages() {
        Set<String> result = new HashSet<>();
        List<AccessibilityWindowInfo> windows = getWindows();
        try {
            for (AccessibilityWindowInfo window : windows) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                String pkg = windowPackageName(window);
                if (pkg != null && !isHomePackage(pkg)) result.add(pkg);
            }
            return result;
        } finally {
            for (AccessibilityWindowInfo window : windows) window.recycle();
        }
    }

    static Rect floatingWindowBounds(String packageName) {
        return connected == null || !connected.tracking
                ? null : connected.readFloatingWindowBounds(packageName);
    }

    static Rect splitPrimaryBounds(String packageName) {
        return connected == null || !connected.tracking
                ? null : connected.readSplitPrimaryBounds(packageName);
    }

    private Rect readSplitPrimaryBounds(String packageName) {
        List<AccessibilityWindowInfo> windows = getWindows();
        try {
            Rect display = usableDisplay();
            Rect fallback = null;
            for (AccessibilityWindowInfo window : windows) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION
                        || window.isInPictureInPictureMode()) continue;
                Rect bounds = new Rect();
                window.getBoundsInScreen(bounds);
                if (bounds.isEmpty()) continue;
                boolean spansWidth = spansAxis(bounds.left, bounds.right, display.left, display.right);
                boolean spansHeight = spansAxis(bounds.top, bounds.bottom, display.top, display.bottom);
                if (spansWidth == spansHeight || !isDockedSplit(bounds, display, spansWidth)) continue;
                String pkg = windowPackageName(window);
                if (packageName != null && packageName.equals(pkg)) return bounds;
                if (fallback == null) fallback = bounds;
            }
            return fallback;
        } finally {
            for (AccessibilityWindowInfo window : windows) window.recycle();
        }
    }

    static FloatingWindow findFloatingWindow(String packageName) {
        return connected == null || !connected.tracking || packageName == null
                ? null : connected.readExactFloatingWindow(packageName);
    }

    static Map<String, FloatingWindow> floatingWindows() {
        return connected == null || !connected.tracking
                ? new HashMap<>() : connected.readFloatingWindows();
    }

    private Map<String, FloatingWindow> readFloatingWindows() {
        Map<String, FloatingWindow> result = new HashMap<>();
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
                if (spansWidth || spansHeight) continue;
                String pkg = windowPackageName(window);
                if (pkg != null && !result.containsKey(pkg)) {
                    result.put(pkg, new FloatingWindow(window.getId(), bounds));
                }
            }
            return result;
        } finally {
            for (AccessibilityWindowInfo window : windows) window.recycle();
        }
    }

    private FloatingWindow readExactFloatingWindow(String packageName) {
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
                if (spansWidth || spansHeight) continue;
                if (packageName.equals(windowPackageName(window))) {
                    return new FloatingWindow(window.getId(), bounds);
                }
            }
            return null;
        } finally {
            for (AccessibilityWindowInfo window : windows) window.recycle();
        }
    }

    private String windowPackageName(AccessibilityWindowInfo window) {
        App app = activities.get(window.getId());
        if (app != null && app.activity != null) return app.activity.getPackageName();
        AccessibilityNodeInfo root = null;
        try {
            root = window.getRoot();
            CharSequence pkg = root == null ? null : root.getPackageName();
            return pkg == null ? null : pkg.toString();
        } catch (RuntimeException ignored) {
            return null;
        } finally {
            if (root != null) root.recycle();
        }
    }

    private Rect readFloatingWindowBounds(String packageName) {
        List<AccessibilityWindowInfo> windows = getWindows();
        try {
            Rect display = usableDisplay();
            Rect focusedFallback = null;
            for (AccessibilityWindowInfo window : windows) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION
                        || window.isInPictureInPictureMode()) continue;
                Rect bounds = new Rect();
                window.getBoundsInScreen(bounds);
                if (bounds.isEmpty()) continue;
                boolean spansWidth = spansAxis(bounds.left, bounds.right, display.left, display.right);
                boolean spansHeight = spansAxis(bounds.top, bounds.bottom, display.top, display.bottom);
                if (spansWidth || spansHeight) continue;
                if (packageName != null && packageName.equals(windowPackageName(window))) {
                    return bounds;
                }
                if (window.isFocused()) focusedFallback = bounds;
            }
            return focusedFallback;
        } finally {
            for (AccessibilityWindowInfo window : windows) window.recycle();
        }
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
                    String pkg = windowPackageName(window);
                    if (pkg != null && !isHomePackage(pkg)) result.add(pkg);
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
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo handler = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        homePackage = handler == null ? "com.huawei.android.launcher" : handler.activityInfo.packageName;
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.eventTypes = tracking ? AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOWS_CHANGED
                | AccessibilityEvent.TYPE_VIEW_CLICKED : 0;
        setServiceInfo(info);
        if (tracking) seedFocusState();
    }

    private void seedFocusState() {
        List<AccessibilityWindowInfo> windows = getWindows();
        try {
            Rect display = usableDisplay();
            for (AccessibilityWindowInfo window : windows) {
                if (!window.isFocused() || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                Rect bounds = new Rect();
                window.getBoundsInScreen(bounds);
                if (bounds.isEmpty()) continue;
                lastFocusedWindowId = window.getId();
                boolean spansWidth = spansAxis(bounds.left, bounds.right, display.left, display.right);
                boolean spansHeight = spansAxis(bounds.top, bounds.bottom, display.top, display.bottom);
                lastFocusedWasFloating = !window.isInPictureInPictureMode() && !spansWidth && !spansHeight;
                lastFocusedWasHome = false;
                break;
            }
        } finally {
            for (AccessibilityWindowInfo window : windows) window.recycle();
        }
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
                return new FocusedApp(app, windowed, split, bounds);
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
        final Rect bounds;

        FocusedApp(App app, boolean windowed, boolean split, Rect bounds) {
            activity = app == null ? null : app.activity;
            floatingIntent = app == null || app.floatingIntent == null ? null : new Intent(app.floatingIntent);
            alreadyWindowed = windowed;
            alreadySplit = split;
            this.bounds = bounds == null ? null : new Rect(bounds);
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

    private boolean isHomePackage(String pkg) {
        return pkg != null && (pkg.equals(homePackage) || pkg.equals("com.huawei.android.launcher"));
    }

    private void onHomeClicked(int windowId) {
        if (windowId >= 0) homeWindowId = windowId;
        long now = SystemClock.uptimeMillis();
        // Handle out-of-order delivery where focus returned to the floating window right before TYPE_VIEW_CLICKED.
        if (lastRefocusedBounds != null && now - lastRefocusedMs <= 220L) {
            int targetId = lastRefocusedId;
            String targetPkg = lastRefocusedPkg;
            Rect bounds = new Rect(lastRefocusedBounds);
            lastRefocusedId = -1;
            lastRefocusedPkg = null;
            lastRefocusedBounds = null;
            lastRefocusedMs = 0L;
            emitFloatingPing(targetId, targetPkg, bounds);
            return;
        }
        lastHomeClickMs = now;
        homeClickFloatingIds.clear();
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
                    homeClickFloatingIds.add(window.getId());
                }
            }
        } finally {
            for (AccessibilityWindowInfo window : windows) window.recycle();
        }
    }

    private void inspectFocusForFloatingPing() {
        if (floatingPingListener == null) return;
        long now = SystemClock.uptimeMillis();
        List<AccessibilityWindowInfo> windows = getWindows();
        int focusedId = -1;
        String focusedPkg = null;
        boolean focusedFloating = false;
        boolean focusedHome = false;
        Rect focusedBounds = null;
        try {
            Rect display = usableDisplay();
            for (AccessibilityWindowInfo window : windows) {
                if (!window.isFocused() || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                Rect bounds = new Rect();
                window.getBoundsInScreen(bounds);
                if (bounds.isEmpty()) continue;
                focusedId = window.getId();
                boolean spansWidth = spansAxis(bounds.left, bounds.right, display.left, display.right);
                boolean spansHeight = spansAxis(bounds.top, bounds.bottom, display.top, display.bottom);
                focusedFloating = !window.isInPictureInPictureMode() && !spansWidth && !spansHeight;
                App app = activities.get(focusedId);
                focusedHome = !focusedFloating && (focusedId == homeWindowId
                        || (app != null && app.activity != null && isHomePackage(app.activity.getPackageName())));
                if (focusedFloating) {
                    focusedBounds = bounds;
                    focusedPkg = windowPackageName(window);
                }
                break;
            }
        } finally {
            for (AccessibilityWindowInfo window : windows) window.recycle();
        }
        if (focusedId < 0 || focusedId == lastFocusedWindowId) return;

        int prevId = lastFocusedWindowId;
        boolean prevWasFloating = lastFocusedWasFloating;
        boolean prevWasHome = lastFocusedWasHome;
        lastFocusedWindowId = focusedId;
        lastFocusedWasFloating = focusedFloating;
        lastFocusedWasHome = focusedHome;

        if (!focusedFloating && prevWasFloating) {
            bouncedFromFloatingId = prevId;
            bouncedToHomeMs = now;
            return;
        }
        if (!focusedFloating || focusedBounds == null) {
            bouncedFromFloatingId = -1;
            bouncedToHomeMs = 0L;
            return;
        }

        // Case 1: User clicked on Home launcher, then HarmonyOS focused an already-floating window.
        boolean fromHomeClick = now - lastHomeClickMs <= HOME_CLICK_WINDOW_MS
                && homeClickFloatingIds.contains(focusedId);
        // Case 2: Rapid floating -> background/Home -> same floating focus bounce from a dock/launcher
        // icon tap even on launchers that do not emit TYPE_VIEW_CLICKED for custom dock icons.
        boolean fromRapidHomeBounce = !prevWasFloating
                && focusedId == bouncedFromFloatingId
                && now - bouncedToHomeMs <= FOCUS_BOUNCE_WINDOW_MS;

        if (fromHomeClick || fromRapidHomeBounce) {
            lastHomeClickMs = 0L;
            homeClickFloatingIds.clear();
            bouncedFromFloatingId = -1;
            bouncedToHomeMs = 0L;
            emitFloatingPing(focusedId, focusedPkg, focusedBounds);
        } else if (prevWasHome || !prevWasFloating) {
            lastRefocusedId = focusedId;
            lastRefocusedPkg = focusedPkg;
            lastRefocusedBounds = new Rect(focusedBounds);
            lastRefocusedMs = now;
        }
    }

    private void emitFloatingPing(int windowId, String packageName, Rect bounds) {
        long now = SystemClock.uptimeMillis();
        if (now - lastPulseMs < PULSE_COOLDOWN_MS) return;
        lastPulseMs = now;
        FloatingPingListener listener = floatingPingListener;
        if (listener != null) listener.onFloatingWindowPinged(windowId, packageName, new Rect(bounds));
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!tracking) return;
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            CharSequence pkg = event.getPackageName();
            if (pkg != null && isHomePackage(pkg.toString())) {
                onHomeClicked(event.getWindowId());
            }
            return;
        }
        int id = event.getWindowId();
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            int changes = event.getWindowChanges();
            if (id >= 0 && (changes & AccessibilityEvent.WINDOWS_CHANGE_REMOVED) != 0) {
                activities.remove(id);
                homeClickFloatingIds.remove(id);
            }
            if ((changes & (AccessibilityEvent.WINDOWS_CHANGE_FOCUSED
                    | AccessibilityEvent.WINDOWS_CHANGE_ACTIVE
                    | AccessibilityEvent.WINDOWS_CHANGE_LAYER)) != 0) {
                inspectFocusForFloatingPing();
            }
            return;
        }
        if (id < 0 || type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getPackageName() == null || event.getClassName() == null) return;
        String pkg = event.getPackageName().toString();
        if (isHomePackage(pkg)) homeWindowId = id;
        ComponentName next = new ComponentName(pkg, ActiveWindow.fullClassName(pkg, event.getClassName().toString()));
        App previous = activities.get(id);
        if (previous == null || !next.equals(previous.activity)) {
            try {
                getPackageManager().getActivityInfo(next, 0);
                activities.put(id, new App(next, ActiveWindow.floatingLauncher(this, next)));
                recordRecent(this, pkg);
            } catch (PackageManager.NameNotFoundException ignored) {
                // A caption, menu or dialog can announce itself for the same window.
            }
        }
        inspectFocusForFloatingPing();
    }
    @Override public void onInterrupt() { }
}
