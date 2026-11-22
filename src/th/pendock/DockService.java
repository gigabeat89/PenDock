package th.pendock;

import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Toast;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.IOException;

public class DockService extends Service {
    private static final String DOUBLE_TAP = "com.huawei.stylus.action.BUTTON_DOUBLE_PRESSED";
    static boolean running;
    private WindowManager manager;
    private RadialMenu menu;
    private CircleSelection circleSelection;
    private Bitmap circleFrame;
    private int circleRequest;
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private Shortcuts shortcuts;
    private ExcludedApps exclusions;
    private List<Shortcuts.Entry> entries;
    private Context menuContext;
    private final SharedPreferences.OnSharedPreferenceChangeListener appearanceChanged =
            (preferences, key) -> {
                menuContext = Appearance.localize(this);
                if (Appearance.LANGUAGE.equals(key) && running) {
                    getSystemService(android.app.NotificationManager.class).notify(1,
                            Ui.notification(this, "dock", "PenDock", Appearance.text(this, R.string.menu_tip)).build());
                }
            };
    private final SharedPreferences.OnSharedPreferenceChangeListener shortcutsChanged =
            (preferences, key) -> refreshShortcuts();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean listening;
    private final BroadcastReceiver stylus = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!DOUBLE_TAP.equals(intent.getAction()) || !running) return;
            if (getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
            android.util.Log.d("PenDock", "Huawei stylus double tap");
            if (circleSelection != null) cancelCircleSearch();
            else if (menu != null) menu.dismiss();
            else showMenu(true);
        }
    };

    private final BroadcastReceiver screenChange = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            cancelCircleSearch();
            detach();
        }
    };

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refreshShortcuts(); }
    };

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) {
            startForeground(1, Ui.notification(this, "dock", "PenDock",
                    Appearance.text(this, R.string.menu_tip)).build());
            manager = getSystemService(WindowManager.class);
            menuContext = Appearance.localize(this);
            shortcuts = new Shortcuts(this);
            exclusions = new ExcludedApps(this);
            shortcuts.preferences().registerOnSharedPreferenceChangeListener(shortcutsChanged);
            Appearance.preferences(this).registerOnSharedPreferenceChangeListener(appearanceChanged);
            refreshShortcuts();
            running = true;
            IntentFilter filter = new IntentFilter(DOUBLE_TAP);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(stylus, filter, "android.permission.INJECT_EVENTS", null,
                        Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(stylus, filter, "android.permission.INJECT_EVENTS", null);
            }
            IntentFilter screen = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            screen.addAction(Intent.ACTION_SCREEN_OFF);
            IntentFilter packages = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
            packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
            packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
            packages.addDataScheme("package");
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenChange, screen, Context.RECEIVER_NOT_EXPORTED);
                registerReceiver(packagesChanged, packages, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenChange, screen);
                registerReceiver(packagesChanged, packages);
            }
            listening = true;
        }
        return START_NOT_STICKY;
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        menuContext = Appearance.localize(this);
        if (shortcuts != null) refreshShortcuts();
        cancelCircleSearch();
        detach();
    }

    private void refreshShortcuts() {
        entries = shortcuts.selected();
    }

    private Rect displayBounds() {
        if (Build.VERSION.SDK_INT >= 30) return manager.getCurrentWindowMetrics().getBounds();
        android.util.DisplayMetrics display = new android.util.DisplayMetrics();
        manager.getDefaultDisplay().getRealMetrics(display);
        return new Rect(0, 0, display.widthPixels, display.heightPixels);
    }

    private void showMenu(boolean useHover) {
        if (menu != null || circleSelection != null) return;
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        WindowModeMonitor.FocusedApp focused = WindowModeMonitor.focusedApp();
        String foreground = null;
        if (focused != null && focused.activity != null) {
            foreground = focused.activity.getPackageName();
        } else if (!WindowModeMonitor.isReady()) {
            // Home can gain focus without announcing a new activity. Never reuse
            // the previous app when the window monitor cannot name the new one.
            foreground = SystemScreenshot.foregroundPackage();
        }
        if (useHover && exclusions.contains(foreground)) {
            android.util.Log.d("PenDock", "Skipped pen menu for an excluded app");
            return;
        }
        Rect bounds = displayBounds();
        Intent activeApp = null;
        if (SystemScreenshot.isReady() && SystemScreenshot.tracksActiveApp(this)) {
            activeApp = focused != null ? focused.floatingIntent
                    : WindowModeMonitor.isReady() ? null : SystemScreenshot.floatingActiveApp();
        }
        Intent floatingTarget = activeApp;
        boolean alreadyWindowed = focused != null && focused.alreadyWindowed;
        RadialMenu opened = new RadialMenu(menuContext, bounds.exactCenterX(), bounds.exactCenterY(), useHover,
                this::lockScreen, this::takeScreenshot, this::startCircleSearch, this::detach,
                entries, this::openShortcut, "—",
                floatingTarget == null || alreadyWindowed ? null : () -> floatActive(floatingTarget, focused),
                alreadyWindowed);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                PixelFormat.TRANSLUCENT);
        params.setTitle("PenDock menu");
        params.gravity = Gravity.TOP | Gravity.LEFT;
        if (Build.VERSION.SDK_INT >= 30) {
            params.setFitInsetsTypes(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            params.setFitInsetsIgnoringVisibility(true);
        }
        try {
            manager.addView(opened, params);
            menu = opened;
            background.execute(() -> {
                String battery = penBattery();
                main.post(() -> {
                    if (menu == opened) opened.setBattery(battery);
                });
            });
            // Settings can accept an overlay window but suppress its surface and focus.
            main.postDelayed(() -> {
                if (menu == opened && !opened.hasWindowFocus()) {
                    android.util.Log.d("PenDock", "Removed menu without window focus");
                    detach();
                }
            }, 350);
        } catch (WindowManager.BadTokenException | SecurityException unavailable) {
            stopSelf();
        }
    }

    private void takeScreenshot() {
        if (!SystemScreenshot.isReady()) {
            open(MainActivity.class);
            Toast.makeText(this, Appearance.text(this, R.string.capture_permission_message),
                    Toast.LENGTH_LONG).show();
            return;
        }
        detach();
        main.postDelayed(() -> {
            boolean accepted = SystemScreenshot.take();
            android.util.Log.d("PenDock", "System screenshot action accepted=" + accepted);
            if (!accepted) {
                Toast.makeText(this, Appearance.text(this, R.string.capture_failed), Toast.LENGTH_SHORT).show();
            }
        }, 120);
    }

    private void startCircleSearch() {
        detach();
        if (Build.VERSION.SDK_INT < 30) {
            Toast.makeText(this, Appearance.text(this, R.string.circle_android_version), Toast.LENGTH_SHORT).show();
            return;
        }
        if (!LensShare.isAvailable(this)) {
            showLensUnavailable();
            return;
        }
        if (!CircleCaptureService.isReady()) {
            open(MainActivity.class);
            Toast.makeText(this, Appearance.text(this, R.string.circle_permission_message),
                    Toast.LENGTH_LONG).show();
            return;
        }
        int request = ++circleRequest;
        main.postDelayed(() -> {
            if (request != circleRequest || menu != null) return;
            CircleCaptureService.capture(frame -> showCircleSelection(request, frame));
        }, 120);
    }

    private void showCircleSelection(int request, Bitmap frame) {
        if (request != circleRequest || menu != null || getSystemService(KeyguardManager.class).isKeyguardLocked()) {
            if (frame != null) frame.recycle();
            return;
        }
        if (frame == null) {
            Toast.makeText(this, Appearance.text(this, R.string.capture_failed), Toast.LENGTH_SHORT).show();
            return;
        }
        CircleSelection selection = new CircleSelection(Appearance.localize(this), frame, this::cancelCircleSearch, this::sendCircle);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.OPAQUE);
        params.setTitle("PenDock circle search");
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.setFitInsetsTypes(0);
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        try {
            manager.addView(selection, params);
            circleSelection = selection;
            circleFrame = frame;
            selection.requestFocus();
        } catch (WindowManager.BadTokenException | SecurityException unavailable) {
            frame.recycle();
            Toast.makeText(this, Appearance.text(this, R.string.circle_overlay_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private void sendCircle(Bitmap crop) {
        cancelCircleSearch();
        if (!LensShare.isAvailable(this)) {
            crop.recycle();
            showLensUnavailable();
            return;
        }
        int request = circleRequest;
        background.execute(() -> {
            try {
                android.net.Uri uri = CircleFiles.save(this, crop);
                main.post(() -> {
                    if (request != circleRequest || !running
                            || getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
                    try {
                        LensShare.send(this, uri);
                    } catch (ActivityNotFoundException | SecurityException unavailable) {
                        android.util.Log.w("PenDock", "Could not send the circled image to Lens", unavailable);
                        showLensUnavailable();
                    }
                });
            } catch (IOException unavailable) {
                android.util.Log.w("PenDock", "Could not share circled image", unavailable);
                main.post(() -> Toast.makeText(this, Appearance.text(this, R.string.circle_share_failed), Toast.LENGTH_SHORT).show());
            } finally {
                crop.recycle();
            }
        });
    }

    private void showLensUnavailable() {
        Toast.makeText(this, Appearance.text(this, R.string.lens_missing),
                Toast.LENGTH_LONG).show();
    }

    private void cancelCircleSearch() {
        circleRequest++;
        if (circleSelection == null) return;
        manager.removeViewImmediate(circleSelection);
        circleSelection = null;
        circleFrame.recycle();
        circleFrame = null;
    }

    private void open(Class<?> activity) {
        Intent intent = new Intent(this, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        launch(intent, null);
    }

    private String penBattery() {
        int level = PenBattery.level(getContentResolver());
        if (level < 0) return "—";
        return java.text.NumberFormat.getPercentInstance(
                java.util.Locale.forLanguageTag(Appearance.language(this))).format(level / 100.0);
    }

    private void lockScreen() {
        detach();
        if (!SystemScreenshot.isReady()) {
            open(MainActivity.class);
            Toast.makeText(this, Appearance.text(this, R.string.system_actions_permission),
                    Toast.LENGTH_LONG).show();
            return;
        }
        boolean accepted = SystemScreenshot.lockScreen();
        android.util.Log.d("PenDock", "System lock screen action accepted=" + accepted);
        if (!accepted) {
            Toast.makeText(this, Appearance.text(this, R.string.lock_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private void openShortcut(Shortcuts.Entry app) {
        openFloatingApp(app.id, app.label);
    }

    private void openFloatingApp(String packageName, String label) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) {
            detach();
            Toast.makeText(this, Appearance.text(this, R.string.open_named_failed, label), Toast.LENGTH_SHORT).show();
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        launch(intent, floatingOptions());
    }

    private void floatActive(Intent requested, WindowModeMonitor.FocusedApp selection) {
        Intent current = SystemScreenshot.floatingActiveApp();
        if (selection == null && (current == null || !requested.filterEquals(current))) {
            detach();
            return;
        }
        Bundle options = floatingOptions();
        detach();
        if (options == null || !SystemScreenshot.switchToHome(selection == null ? null : selection.activity, () -> {
            if (running && !getSystemService(KeyguardManager.class).isKeyguardLocked()) {
                launch(requested, options, false);
            }
        })) {
            Toast.makeText(this, Appearance.text(this, R.string.float_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private Bundle floatingOptions() {
        Bundle options = null;
        if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)) {
            Rect bounds = displayBounds();
            int width = Math.min(Ui.dp(this, 460), bounds.width() * 3 / 4);
            int height = Math.min(Ui.dp(this, 620), bounds.height() * 4 / 5);
            int x = bounds.centerX() - width / 2;
            int y = bounds.centerY() - height / 2;
            options = ActivityOptions.makeBasic().setLaunchBounds(new Rect(x, y, x + width, y + height)).toBundle();
            // Huawei's Android-based tablets use their own freeform task mode.
            boolean huawei = "HUAWEI".equalsIgnoreCase(Build.MANUFACTURER);
            options.putInt("android.activity.windowingMode", huawei ? 102 : 5);
        }
        return options;
    }

    private void launch(Intent intent, Bundle options) {
        launch(intent, options, true);
    }

    private void launch(Intent intent, Bundle options, boolean allowFullscreen) {
        try {
            startActivity(intent, options);
        } catch (RuntimeException failure) {
            if (options != null && allowFullscreen) {
                try {
                    startActivity(intent);
                    return;
                } catch (RuntimeException fallbackFailed) {
                    android.util.Log.w("PenDock", "Unable to open shortcut", fallbackFailed);
                }
            }
            Toast.makeText(this, Appearance.text(this, R.string.open_failed), Toast.LENGTH_SHORT).show();
        } finally {
            detach();
        }
    }

    private void detach() {
        if (menu == null) return;
        RadialMenu removed = menu;
        menu = null;
        manager.removeViewImmediate(removed);
    }

    @Override public void onDestroy() {
        if (listening) {
            unregisterReceiver(stylus);
            unregisterReceiver(screenChange);
            unregisterReceiver(packagesChanged);
            shortcuts.preferences().unregisterOnSharedPreferenceChangeListener(shortcutsChanged);
            Appearance.preferences(this).unregisterOnSharedPreferenceChangeListener(appearanceChanged);
        }
        main.removeCallbacksAndMessages(null);
        cancelCircleSearch();
        background.shutdown();
        detach();
        running = false;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
