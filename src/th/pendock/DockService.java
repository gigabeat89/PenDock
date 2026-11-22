package th.pendock;

import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Toast;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.IOException;

public class DockService extends Service {
    private static final String DOUBLE_TAP = "com.huawei.stylus.action.BUTTON_DOUBLE_PRESSED";
    private static final String PREF_ENABLED = "dock_enabled";
    private static final CopyOnWriteArraySet<Runnable> stateListeners = new CopyOnWriteArraySet<>();
    static volatile boolean running;

    static void addStateListener(Runnable listener) { stateListeners.add(listener); }

    static void removeStateListener(Runnable listener) { stateListeners.remove(listener); }

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences("tools", Context.MODE_PRIVATE).getBoolean(PREF_ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("tools", Context.MODE_PRIVATE).edit().putBoolean(PREF_ENABLED, enabled).apply();
    }

    static boolean start(Context context) {
        if (!Settings.canDrawOverlays(context)) return false;
        setEnabled(context, true);
        try {
            context.startForegroundService(new Intent(context, DockService.class));
            return true;
        } catch (RuntimeException unavailable) {
            android.util.Log.w("PenDock", "Could not start DockService", unavailable);
            return false;
        }
    }

    static void stop(Context context) {
        setEnabled(context, false);
        context.stopService(new Intent(context, DockService.class));
    }

    private void setRunning(boolean active) {
        if (running == active) return;
        running = active;
        DockTileService.requestUpdate(this);
        for (Runnable listener : stateListeners) main.post(listener);
    }
    private WindowManager manager;
    private RadialMenu menu;
    private CircleSelection circleSelection;
    private SplitAppPicker splitPicker;
    private LaserOverlay laserOverlay;
    private Bitmap circleFrame;
    private int circleRequest;
    private int splitRequest;
    private int menuRequest;
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
            else if (splitPicker != null) cancelSplitPicker();
            else if (laserOverlay != null) cancelLaserOverlay();
            else if (menu != null) menu.dismiss();
            else showMenu(true);
        }
    };

    private final BroadcastReceiver screenChange = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            cancelCircleSearch();
            cancelSplitPicker();
            cancelLaserOverlay();
            detach();
        }
    };

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            ActiveWindow.clearCache();
            refreshShortcuts();
        }
    };

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Settings.canDrawOverlays(this)) {
            setEnabled(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null && !isEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null) setEnabled(this, true);
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
            setRunning(true);
            IntentFilter filter = new IntentFilter(DOUBLE_TAP);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(stylus, filter, "android.permission.INJECT_EVENTS", null,
                        Context.RECEIVER_EXPORTED);
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
        return START_STICKY;
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        menuContext = Appearance.localize(this);
        if (shortcuts != null) refreshShortcuts();
        cancelCircleSearch();
        cancelSplitPicker();
        cancelLaserOverlay();
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
        if (menu != null || circleSelection != null || splitPicker != null || laserOverlay != null) return;
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        WindowModeMonitor.FocusedApp focused = WindowModeMonitor.focusedApp();
        String foreground = focused != null && focused.activity != null
                ? focused.activity.getPackageName() : null;
        if (useHover && exclusions.contains(foreground)) {
            android.util.Log.d("PenDock", "Skipped pen menu for an excluded app");
            return;
        }
        int request = ++menuRequest;
        int blurDp = RingConfig.ringBlurDp(this);
        if (blurDp > 0 && Build.VERSION.SDK_INT >= 31
                && !manager.isCrossWindowBlurEnabled() && CircleCaptureService.isReady()) {
            CircleCaptureService.capture(frame -> attachMenu(request, useHover, focused, frame));
            return;
        }
        attachMenu(request, useHover, focused, null);
    }

    private void attachMenu(int request, boolean useHover, WindowModeMonitor.FocusedApp focused, Bitmap blurFrame) {
        if (request != menuRequest || !running || menu != null || circleSelection != null
                || splitPicker != null || laserOverlay != null
                || getSystemService(KeyguardManager.class).isKeyguardLocked()) {
            if (blurFrame != null) blurFrame.recycle();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            if (blurFrame != null) blurFrame.recycle();
            stopSelf();
            return;
        }
        Rect bounds = displayBounds();
        Intent activeTarget = SystemScreenshot.isReady() && SystemScreenshot.tracksActiveApp() && focused != null
                ? focused.floatingIntent : null;
        boolean alreadyWindowed = focused != null && focused.alreadyWindowed;
        boolean alreadySplit = focused != null && focused.alreadySplit;
        RadialMenu opened = new RadialMenu(menuContext, bounds.exactCenterX(), bounds.exactCenterY(), useHover,
                this::lockScreen, this::takeScreenshot, this::startCircleSearch, this::startLaserPointer, this::detach,
                entries, app -> openShortcut(app, focused), "—",
                activeTarget == null || alreadyWindowed ? null : () -> floatActive(activeTarget, focused),
                alreadyWindowed,
                activeTarget == null || alreadySplit ? null : () -> splitActive(activeTarget, focused),
                alreadySplit, blurFrame);
        int flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        int blurDp = RingConfig.ringBlurDp(this);
        boolean nativeBlur = blurFrame == null && blurDp > 0 && Build.VERSION.SDK_INT >= 31
                && manager.isCrossWindowBlurEnabled();
        if (nativeBlur) flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        if (nativeBlur) params.setBlurBehindRadius(Ui.dp(this, blurDp));
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
            if (blurFrame != null && !blurFrame.isRecycled()) blurFrame.recycle();
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
        CircleSelection selection = new CircleSelection(Appearance.localize(this), frame,
                LensShare.isAvailable(this), this::cancelCircleSearch, this::handleCircleAction);
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

    private void handleCircleAction(CircleSelection.Action action, Bitmap crop) {
        cancelCircleSearch();
        if (action == CircleSelection.Action.SAVE) {
            saveCircleToGallery(crop);
            return;
        }
        if (action == CircleSelection.Action.LENS && !LensShare.isAvailable(this)) {
            crop.recycle();
            showLensUnavailable();
            return;
        }
        int request = circleRequest;
        background.execute(() -> {
            try {
                Uri uri = CircleFiles.save(this, crop);
                main.post(() -> {
                    if (request != circleRequest || !running
                            || getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
                    if (action == CircleSelection.Action.LENS) {
                        try {
                            LensShare.send(this, uri);
                        } catch (ActivityNotFoundException | SecurityException unavailable) {
                            android.util.Log.w("PenDock", "Could not send the circled image to Lens", unavailable);
                            showLensUnavailable();
                        }
                    } else if (action == CircleSelection.Action.SHARE) {
                        try {
                            LensShare.share(this, uri, Appearance.text(this, R.string.circle_share_title));
                        } catch (ActivityNotFoundException | SecurityException unavailable) {
                            android.util.Log.w("PenDock", "Could not open share sheet for circled image", unavailable);
                            Toast.makeText(this, Appearance.text(this, R.string.circle_share_failed),
                                    Toast.LENGTH_SHORT).show();
                        }
                    } else if (action == CircleSelection.Action.COPY) {
                        try {
                            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                            clipboard.setPrimaryClip(ClipData.newUri(getContentResolver(), "PenDock", uri));
                            Toast.makeText(this, Appearance.text(this, R.string.circle_copied),
                                    Toast.LENGTH_SHORT).show();
                        } catch (RuntimeException unavailable) {
                            android.util.Log.w("PenDock", "Could not copy circled image", unavailable);
                            Toast.makeText(this, Appearance.text(this, R.string.circle_copy_failed),
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            } catch (IOException unavailable) {
                android.util.Log.w("PenDock", "Could not share circled image", unavailable);
                main.post(() -> Toast.makeText(this, Appearance.text(this, R.string.circle_share_failed),
                        Toast.LENGTH_SHORT).show());
            } finally {
                crop.recycle();
            }
        });
    }

    private void saveCircleToGallery(Bitmap crop) {
        background.execute(() -> {
            Uri uri = null;
            boolean complete = false;
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, "PenDock-Circle-" + System.currentTimeMillis() + ".png");
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PenDock");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("No media URI");
                try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                    if (output == null || !crop.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        throw new IOException("PNG encoding failed");
                    }
                }
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                complete = getContentResolver().update(uri, ready, null, null) > 0;
            } catch (IOException | RuntimeException failure) {
                android.util.Log.w("PenDock", "Could not save circled image", failure);
            } finally {
                crop.recycle();
                if (!complete && uri != null) {
                    try { getContentResolver().delete(uri, null, null); }
                    catch (RuntimeException ignored) { }
                }
            }
            boolean saved = complete;
            main.post(() -> Toast.makeText(this,
                    Appearance.text(this, saved ? R.string.saved_album : R.string.save_failed),
                    Toast.LENGTH_SHORT).show());
        });
    }

    private void showLensUnavailable() {
        Toast.makeText(this, Appearance.text(this, R.string.lens_missing),
                Toast.LENGTH_LONG).show();
    }

    private void cancelCircleSearch() {
        circleRequest++;
        if (circleSelection == null) return;
        CircleSelection removed = circleSelection;
        circleSelection = null;
        removeOverlay(removed);
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

    private void openShortcut(Shortcuts.Entry app, WindowModeMonitor.FocusedApp focused) {
        String mode = RingConfig.launchMode(this);
        if (RingConfig.LAUNCH_SPLIT.equals(mode)) {
            openAppInSplit(app, focused);
            return;
        }
        openApp(app.id, app.label, RingConfig.LAUNCH_FLOATING.equals(mode));
    }

    private void openApp(String packageName, String label, boolean floating) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) {
            detach();
            Toast.makeText(this, Appearance.text(this, R.string.open_named_failed, label), Toast.LENGTH_SHORT).show();
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        launch(intent, floating ? floatingOptions() : null);
    }

    private void openAppInSplit(Shortcuts.Entry app, WindowModeMonitor.FocusedApp focused) {
        Intent secondaryIntent = getPackageManager().getLaunchIntentForPackage(app.id);
        if (secondaryIntent == null) {
            detach();
            Toast.makeText(this, Appearance.text(this, R.string.open_named_failed, app.label), Toast.LENGTH_SHORT).show();
            return;
        }
        secondaryIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
        String currentPkg = focused != null && focused.activity != null ? focused.activity.getPackageName() : null;
        if (focused != null && focused.alreadySplit) {
            launch(secondaryIntent, splitOptions(true));
            return;
        }
        if (focused != null && focused.floatingIntent != null && !focused.alreadyWindowed
                && !app.id.equals(currentPkg) && SystemScreenshot.isReady() && SystemScreenshot.tracksActiveApp()) {
            Intent primaryIntent = focused.floatingIntent;
            int request = ++splitRequest;
            detach();
            if (SystemScreenshot.switchToHome(focused.activity, () -> {
                if (request != splitRequest || !running || getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
                try {
                    startActivity(primaryIntent, splitOptions(false));
                    main.postDelayed(() -> {
                        if (request != splitRequest || !running || getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
                        launch(secondaryIntent, splitOptions(true));
                    }, 150);
                } catch (RuntimeException failure) {
                    android.util.Log.w("PenDock", "Primary split launch failed", failure);
                    launch(secondaryIntent, splitOptions(true));
                }
            })) {
                return;
            }
        }
        // On Home screen or when no active app can be paired, open app in primary half and show the second-app picker.
        int request = ++splitRequest;
        Intent primary = getPackageManager().getLaunchIntentForPackage(app.id);
        if (primary != null) {
            primary.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            launch(primary, splitOptions(false));
            main.postDelayed(() -> showSplitPicker(request, app.id), 180);
        } else {
            detach();
        }
    }

    private void splitActive(Intent requested, WindowModeMonitor.FocusedApp selection) {
        if (selection == null) {
            detach();
            return;
        }
        Bundle options = splitOptions(false);
        String primaryPackage = selection.activity == null ? null : selection.activity.getPackageName();
        int request = ++splitRequest;
        detach();
        if (!SystemScreenshot.switchToHome(selection.activity, () -> {
            if (request != splitRequest || !running || getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
            try {
                startActivity(requested, options);
                main.postDelayed(() -> showSplitPicker(request, primaryPackage), 180);
            } catch (RuntimeException failure) {
                android.util.Log.w("PenDock", "Split windowingMode launch failed", failure);
                if (!SystemScreenshot.toggleSplitScreen()) {
                    Toast.makeText(this, Appearance.text(this, R.string.split_failed), Toast.LENGTH_SHORT).show();
                }
            }
        })) {
            if (!SystemScreenshot.toggleSplitScreen()) {
                Toast.makeText(this, Appearance.text(this, R.string.split_failed), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showSplitPicker(int request, String primaryPackage) {
        if (request != splitRequest || !running || splitPicker != null || menu != null
                || getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
        if (!Settings.canDrawOverlays(this)) return;
        Rect bounds = displayBounds();
        boolean landscape = bounds.width() >= bounds.height();
        int width = landscape ? bounds.width() / 2 : WindowManager.LayoutParams.MATCH_PARENT;
        int height = landscape ? WindowManager.LayoutParams.MATCH_PARENT : bounds.height() / 2;
        SplitAppPicker picker = new SplitAppPicker(Appearance.localize(this), shortcuts, entries,
                primaryPackage, background, this::cancelSplitPicker, chosen -> {
            cancelSplitPicker();
            Intent intent = getPackageManager().getLaunchIntentForPackage(chosen.id);
            if (intent == null) {
                Toast.makeText(this, Appearance.text(this, R.string.open_named_failed, chosen.label),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
            launch(intent, splitOptions(true));
        });
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.setTitle("PenDock split picker");
        params.gravity = landscape ? (Gravity.TOP | Gravity.END) : (Gravity.BOTTOM | Gravity.START);
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        if (Build.VERSION.SDK_INT >= 30) {
            params.setFitInsetsTypes(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            params.setFitInsetsIgnoringVisibility(true);
        }
        try {
            manager.addView(picker, params);
            splitPicker = picker;
            picker.requestFocus();
        } catch (WindowManager.BadTokenException | SecurityException unavailable) {
            android.util.Log.w("PenDock", "Could not show split app picker", unavailable);
        }
    }

    private void startLaserPointer() {
        detach();
        if (laserOverlay != null || !Settings.canDrawOverlays(this)) return;
        LaserOverlay overlay = new LaserOverlay(Appearance.localize(this), this::cancelLaserOverlay);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.setTitle("PenDock laser pointer");
        params.gravity = Gravity.TOP | Gravity.LEFT;
        if (Build.VERSION.SDK_INT >= 30) {
            params.setFitInsetsTypes(0);
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        try {
            manager.addView(overlay, params);
            laserOverlay = overlay;
            overlay.requestFocus();
        } catch (WindowManager.BadTokenException | SecurityException unavailable) {
            Toast.makeText(this, Appearance.text(this, R.string.laser_overlay_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private void cancelLaserOverlay() {
        if (laserOverlay == null) return;
        LaserOverlay removed = laserOverlay;
        laserOverlay = null;
        removeOverlay(removed);
    }

    private void cancelSplitPicker() {
        splitRequest++;
        if (splitPicker == null) return;
        SplitAppPicker removed = splitPicker;
        splitPicker = null;
        removeOverlay(removed);
    }

    private Bundle splitOptions(boolean secondary) {
        Bundle options = ActivityOptions.makeBasic().toBundle();
        boolean huawei = "HUAWEI".equalsIgnoreCase(Build.MANUFACTURER);
        options.putInt("android.activity.windowingMode", huawei ? (secondary ? 101 : 100) : (secondary ? 4 : 3));
        return options;
    }

    private void floatActive(Intent requested, WindowModeMonitor.FocusedApp selection) {
        if (selection == null) {
            detach();
            return;
        }
        Bundle options = floatingOptions();
        detach();
        if (options == null || !SystemScreenshot.switchToHome(selection.activity, () -> {
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
            int width = RingConfig.floatingWidth(this, bounds.width());
            int height = RingConfig.floatingHeight(this, bounds.height());
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
        menuRequest++;
        if (menu == null) return;
        RadialMenu removed = menu;
        menu = null;
        removeOverlay(removed);
    }

    private void removeOverlay(View view) {
        if (manager == null || view == null || !view.isAttachedToWindow()) return;
        try {
            manager.removeViewImmediate(view);
        } catch (IllegalArgumentException ignored) { }
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
        cancelSplitPicker();
        cancelLaserOverlay();
        background.shutdown();
        detach();
        setRunning(false);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
