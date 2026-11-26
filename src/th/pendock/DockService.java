package th.pendock;

import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
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
import android.widget.FrameLayout;
import android.widget.Toast;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        return context.getSharedPreferences("tools", Context.MODE_PRIVATE).getBoolean(PREF_ENABLED, true);
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
    private String splitPickerPackage;
    private Intent splitPickerIntent;
    private ComponentName splitPickerActivity;
    private LaserOverlay laserOverlay;
    private TranslateOverlay translateOverlay;
    private WindowManager.LayoutParams translateParams;
    private View translateToolbarView;
    private WindowManager.LayoutParams translateToolbarParams;
    private FloatingPulse floatingPulse;
    private final android.util.LruCache<Integer, Float> floatingScales = new android.util.LruCache<>(16);
    private int pulseRequest;
    private Bitmap circleFrame;
    private int circleRequest;
    private int translateRequest;
    private int splitRequest;
    private int menuRequest;
    private boolean splitTransitioning;
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
            else if (translateOverlay != null && !translateOverlay.isPaused()) cancelTranslateOverlay();
            else if (splitPicker != null || splitTransitioning) return;
            else if (laserOverlay != null) cancelLaserOverlay();
            else if (menu != null) menu.dismiss();
            else showMenu(true);
        }
    };

    private final BroadcastReceiver screenChange = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            cancelCircleSearch();
            // Keep TranslateOverlay alive across app switches / Home; pause it if screen turns off while active.
            if (translateOverlay != null && !translateOverlay.isPaused()) {
                translateOverlay.pauseIfActive();
            }
            cancelSplitPicker();
            cancelLaserOverlay();
            cancelFloatingPulse();
            detach();
        }
    };

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            ActiveWindow.clearCache();
            if (shortcuts != null) {
                shortcuts.invalidateCache();
                if (!background.isShutdown()) background.execute(shortcuts::warmUp);
            }
            refreshShortcuts();
        }
    };

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Settings.canDrawOverlays(this)) {
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
            background.execute(shortcuts::warmUp);
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
            WindowModeMonitor.setFloatingPingListener((windowId, packageName, bounds) ->
                    main.post(() -> showFloatingPulse(windowId, packageName, bounds, false)));
        }
        return START_STICKY;
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        menuContext = Appearance.localize(this);
        if (shortcuts != null) {
            shortcuts.invalidateCache();
            if (!background.isShutdown()) background.execute(shortcuts::warmUp);
            refreshShortcuts();
        }
        cancelCircleSearch();
        if (translateOverlay != null) {
            if (!translateOverlay.isPaused()) translateOverlay.pauseIfActive();
            translateOverlay.onDisplayRotated();
        }
        cancelLaserOverlay();
        cancelFloatingPulse();
        detach();
        if (splitPicker != null || splitPickerPackage != null || splitPickerIntent != null) {
            String pkg = splitPickerPackage;
            Intent intent = splitPickerIntent == null ? null : new Intent(splitPickerIntent);
            ComponentName activity = splitPickerActivity;
            if (splitPicker != null) {
                SplitAppPicker old = splitPicker;
                splitPicker = null;
                removeOverlay(old);
            }
            int request = ++splitRequest;
            main.postDelayed(() -> showSplitPicker(request, pkg, intent, activity, false), 260);
        }
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
        if (menu != null || circleSelection != null
                || (translateOverlay != null && !translateOverlay.isPaused())
                || splitPicker != null || laserOverlay != null) return;
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        WindowModeMonitor.FocusedApp focused = WindowModeMonitor.focusedApp();
        Map<String, WindowModeMonitor.FloatingWindow> openFloating = WindowModeMonitor.floatingWindows();
        Set<String> visibleApps = WindowModeMonitor.visibleAppPackages();
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
            CircleCaptureService.capture(frame ->
                    attachMenu(request, useHover, focused, openFloating, visibleApps, frame));
            return;
        }
        attachMenu(request, useHover, focused, openFloating, visibleApps, null);
    }

    private void attachMenu(int request, boolean useHover, WindowModeMonitor.FocusedApp focused,
            Map<String, WindowModeMonitor.FloatingWindow> openFloating, Set<String> visibleApps, Bitmap blurFrame) {
        if (request != menuRequest || !running || menu != null || circleSelection != null
                || (translateOverlay != null && !translateOverlay.isPaused())
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
        if (blurFrame != null && openFloating != null && !openFloating.isEmpty()
                && "HUAWEI".equalsIgnoreCase(Build.MANUFACTURER)) {
            for (Map.Entry<String, WindowModeMonitor.FloatingWindow> entry : openFloating.entrySet()) {
                WindowModeMonitor.FloatingWindow fw = entry.getValue();
                float measured = measureHuaweiFloatingScale(blurFrame, fw.bounds);
                if (measured > 0f) {
                    if (fw.windowId >= 0) floatingScales.put(fw.windowId, measured);
                    savePackageScale(entry.getKey(), measured);
                }
            }
        }
        Rect bounds = displayBounds();
        Intent activeTarget = SystemScreenshot.isReady() && SystemScreenshot.tracksActiveApp() && focused != null
                ? focused.floatingIntent : null;
        boolean alreadyWindowed = focused != null && focused.alreadyWindowed;
        boolean alreadySplit = focused != null && focused.alreadySplit;
        RadialMenu[] menuRef = new RadialMenu[1];
        RadialMenu opened = new RadialMenu(menuContext, bounds.exactCenterX(), bounds.exactCenterY(), useHover,
                this::lockScreen, this::takeScreenshot, this::startCircleSearch, this::startLaserPointer,
                this::startTranslate, () -> detach(menuRef[0]),
                entries, app -> openShortcut(app, focused, openFloating, visibleApps), "—",
                activeTarget == null || alreadyWindowed ? null : () -> floatActive(activeTarget, focused),
                alreadyWindowed,
                activeTarget == null || alreadySplit || alreadyWindowed ? null : () -> splitActive(activeTarget, focused),
                alreadySplit, blurFrame);
        menuRef[0] = opened;
        int flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        int blurDp = RingConfig.ringBlurDp(this);
        boolean nativeBlur = blurFrame == null && blurDp > 0 && Build.VERSION.SDK_INT >= 31
                && manager.isCrossWindowBlurEnabled();
        if (nativeBlur) flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        if (nativeBlur) params.setBlurBehindRadius(Ui.dp(this, blurDp));
        params.setTitle("PenDock menu");
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
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
            detach();
            openForPermission(MainActivity.PERM_SYSTEM_ACTIONS, "screenshot");
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
            openForPermission(MainActivity.PERM_CIRCLE, "circle");
            return;
        }
        boolean hasExtra = RingConfig.isCircleShareShown(this)
                || RingConfig.isCircleCopyShown(this)
                || RingConfig.isCircleSaveShown(this);
        if (!hasExtra && !LensShare.isAvailable(this)) {
            showLensUnavailable();
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
                LensShare.isAvailable(this),
                RingConfig.isCircleShareShown(this),
                RingConfig.isCircleCopyShown(this),
                RingConfig.isCircleSaveShown(this),
                this::cancelCircleSearch, this::handleCircleAction);
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
                            android.util.Log.w("PenDock", "Could not open Google Lens for circled image", unavailable);
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

    private void showLensUnavailable() {
        Toast.makeText(this, Appearance.text(this, R.string.lens_missing), Toast.LENGTH_LONG).show();
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

    private void cancelCircleSearch() {
        circleRequest++;
        if (circleSelection == null) return;
        CircleSelection removed = circleSelection;
        circleSelection = null;
        removeOverlay(removed);
        circleFrame.recycle();
        circleFrame = null;
    }

    private void startTranslate() {
        detach();
        if (Build.VERSION.SDK_INT < 30) {
            Toast.makeText(this, Appearance.text(this, R.string.circle_android_version), Toast.LENGTH_SHORT).show();
            return;
        }
        if (!CircleCaptureService.isReady()) {
            openForPermission(MainActivity.PERM_CIRCLE, "translate");
            return;
        }
        captureAndShowTranslate();
    }

    private void captureAndShowTranslate() {
        cancelTranslateOverlay();
        int request = ++translateRequest;
        main.postDelayed(() -> {
            if (request != translateRequest || menu != null) return;
            CircleCaptureService.capture(frame -> showTranslateOverlay(request, frame));
        }, 120);
    }

    private void showTranslateOverlay(int request, Bitmap frame) {
        if (request != translateRequest || menu != null
                || getSystemService(KeyguardManager.class).isKeyguardLocked()) {
            if (frame != null) frame.recycle();
            return;
        }
        if (frame == null) {
            Toast.makeText(this, Appearance.text(this, R.string.capture_failed), Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            frame.recycle();
            stopSelf();
            return;
        }
        TranslateOverlay[] ref = new TranslateOverlay[1];
        TranslateOverlay overlay = new TranslateOverlay(menuContext, frame, new TranslateOverlay.Host() {
            @Override public void onPause() {
                if (translateOverlay != ref[0] || translateParams == null || manager == null) return;
                // Keep stage window attached behind the persistent toolbar, but invisible & pass-through.
                ref[0].setVisibility(View.GONE);
                translateParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                try {
                    manager.updateViewLayout(ref[0], translateParams);
                } catch (IllegalArgumentException ignored) { }
            }

            @Override public void onMoveToolbar(int windowX, int windowY) {
                if (translateOverlay != ref[0] || translateToolbarView == null
                        || translateToolbarParams == null || manager == null) return;
                translateToolbarParams.x = windowX;
                translateToolbarParams.y = windowY;
                try {
                    manager.updateViewLayout(translateToolbarView, translateToolbarParams);
                } catch (IllegalArgumentException ignored) { }
            }

            @Override public void onRequestResume() {
                if (translateOverlay != ref[0] || translateParams == null || manager == null) return;
                int resumeReq = ++translateRequest;
                // Capture immediately without hiding the persistent toolbar so it never blinks.
                CircleCaptureService.capture(fresh -> {
                    if (resumeReq != translateRequest || translateOverlay != ref[0] || menu != null) {
                        if (fresh != null) fresh.recycle();
                        return;
                    }
                    if (fresh == null) {
                        Toast.makeText(DockService.this, Appearance.text(DockService.this, R.string.capture_failed),
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    translateParams.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                    ref[0].setVisibility(View.VISIBLE);
                    try {
                        manager.updateViewLayout(ref[0], translateParams);
                    } catch (IllegalArgumentException ignored) { }
                    ref[0].resumeWithFrame(fresh);
                    ref[0].requestFocus();
                });
            }

            @Override public void onClose() {
                cancelTranslateOverlay();
            }
        });
        ref[0] = overlay;
        WindowManager.LayoutParams stageParams = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        stageParams.setTitle("PenDock translate stage");
        stageParams.gravity = Gravity.TOP | Gravity.LEFT;
        if (Build.VERSION.SDK_INT >= 30) {
            stageParams.setFitInsetsTypes(0);
            stageParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }

        View toolbarView = overlay.toolbarWindowView();
        WindowManager.LayoutParams barParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        barParams.setTitle("PenDock translate bar");
        barParams.gravity = Gravity.TOP | Gravity.LEFT;
        barParams.x = overlay.initialToolbarWindowX();
        barParams.y = overlay.initialToolbarWindowY();
        if (Build.VERSION.SDK_INT >= 30) {
            barParams.setFitInsetsTypes(0);
            barParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        try {
            manager.addView(overlay, stageParams);
            manager.addView(toolbarView, barParams);
            translateOverlay = overlay;
            translateParams = stageParams;
            translateToolbarView = toolbarView;
            translateToolbarParams = barParams;
            overlay.requestFocus();
        } catch (WindowManager.BadTokenException | SecurityException unavailable) {
            removeOverlay(toolbarView);
            removeOverlay(overlay);
            frame.recycle();
            Toast.makeText(this, Appearance.text(this, R.string.translate_overlay_failed),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void cancelTranslateOverlay() {
        translateRequest++;
        translateParams = null;
        translateToolbarParams = null;
        if (translateToolbarView != null) {
            View bar = translateToolbarView;
            translateToolbarView = null;
            removeOverlay(bar);
        }
        if (translateOverlay == null) return;
        TranslateOverlay removed = translateOverlay;
        translateOverlay = null;
        removeOverlay(removed);
    }

    private void open(Class<?> activity) {
        Intent intent = new Intent(this, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        launch(intent, null);
    }

    private void openForPermission(String permKey, String toolKey) {
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_FOCUS_PERMISSION, permKey)
                .putExtra(MainActivity.EXTRA_FOCUS_TOOL, toolKey);
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
            openForPermission(MainActivity.PERM_SYSTEM_ACTIONS, "lock");
            return;
        }
        boolean accepted = SystemScreenshot.lockScreen();
        android.util.Log.d("PenDock", "System lock screen action accepted=" + accepted);
        if (!accepted) {
            Toast.makeText(this, Appearance.text(this, R.string.lock_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private void openShortcut(Shortcuts.Entry app, WindowModeMonitor.FocusedApp focused,
            Map<String, WindowModeMonitor.FloatingWindow> openFloating, Set<String> visibleApps) {
        String mode = RingConfig.launchMode(this);
        if (RingConfig.LAUNCH_SPLIT.equals(mode)) {
            openAppInSplit(app, focused, openFloating, visibleApps);
            return;
        }
        WindowModeMonitor.FloatingWindow alreadyFloating = openFloating == null ? null : openFloating.get(app.id);
        openApp(app.id, app.label, RingConfig.LAUNCH_FLOATING.equals(mode), alreadyFloating);
    }

    private void openApp(String packageName, String label, boolean floating,
            WindowModeMonitor.FloatingWindow alreadyFloating) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) {
            detach();
            Toast.makeText(this, Appearance.text(this, R.string.open_named_failed, label), Toast.LENGTH_SHORT).show();
            return;
        }
        if (alreadyFloating == null) {
            alreadyFloating = WindowModeMonitor.findFloatingWindow(packageName);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        WindowModeMonitor.recordRecent(this, packageName);
        Bundle options;
        if (alreadyFloating != null) {
            options = floatingKeepBoundsOptions();
        } else {
            options = floating ? floatingOptions() : null;
        }
        launch(intent, options);
        if (alreadyFloating != null) {
            main.postDelayed(() -> {
                if (!running || splitTransitioning) return;
                WindowModeMonitor.FloatingWindow stillFloating = WindowModeMonitor.findFloatingWindow(packageName);
                if (stillFloating != null && !stillFloating.bounds.isEmpty()) {
                    showFloatingPulse(stillFloating.windowId, packageName, stillFloating.bounds, true);
                }
            }, 180);
        }
    }

    private void openAppInSplit(Shortcuts.Entry app, WindowModeMonitor.FocusedApp focused,
            Map<String, WindowModeMonitor.FloatingWindow> openFloating, Set<String> visibleApps) {
        String currentPkg = focused != null && focused.activity != null ? focused.activity.getPackageName() : null;
        WindowModeMonitor.FloatingWindow existingFloating = openFloating == null ? null : openFloating.get(app.id);
        // HarmonyOS blocks third-party startActivity() from converting a visible floating task (102)
        // into split screen (100/101). Highlight the floating window instead of opening an empty picker.
        if (existingFloating != null) {
            Intent bringFront = getPackageManager().getLaunchIntentForPackage(app.id);
            if (bringFront != null) {
                bringFront.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                launch(bringFront, floatingKeepBoundsOptions());
            } else {
                detach();
            }
            main.postDelayed(() -> {
                if (!running || splitTransitioning) return;
                WindowModeMonitor.FloatingWindow stillFloating = WindowModeMonitor.findFloatingWindow(app.id);
                if (stillFloating != null && !stillFloating.bounds.isEmpty()) {
                    showFloatingPulse(stillFloating.windowId, app.id, stillFloating.bounds, true);
                }
            }, 180);
            return;
        }
        // Rule 3: if the selected app is already open full-screen or in split screen, do nothing.
        if (app.id.equals(currentPkg) || (visibleApps != null && visibleApps.contains(app.id))) {
            detach();
            return;
        }
        Intent secondaryIntent = getPackageManager().getLaunchIntentForPackage(app.id);
        if (secondaryIntent == null) {
            detach();
            Toast.makeText(this, Appearance.text(this, R.string.open_named_failed, app.label), Toast.LENGTH_SHORT).show();
            return;
        }
        secondaryIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
        if (focused != null && focused.alreadySplit) {
            WindowModeMonitor.recordRecent(this, app.id);
            launch(secondaryIntent, splitOptions(true));
            return;
        }
        // Rule 2: when a full-screen app is open, shrink it right-to-left like Split screen and open
        // the selected app on the right side without Home flicker.
        if (focused != null && focused.floatingIntent != null && !focused.alreadyWindowed
                && SystemScreenshot.isReady() && SystemScreenshot.tracksActiveApp()) {
            Intent primaryIntent = focused.floatingIntent;
            ComponentName primaryActivity = focused.activity;
            int request = ++splitRequest;
            detach();
            if (Build.VERSION.SDK_INT >= 30 && CircleCaptureService.isReady() && Settings.canDrawOverlays(this)) {
                main.postDelayed(() -> {
                    if (request != splitRequest || !running || menu != null
                            || getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
                    CircleCaptureService.captureOrRecent(frame -> {
                        if (frame == null || request != splitRequest || !running || menu != null
                                || getSystemService(KeyguardManager.class).isKeyguardLocked()) {
                            if (frame != null) frame.recycle();
                            runSplitPairLaunch(request, primaryIntent, primaryActivity, app.id, secondaryIntent);
                            return;
                        }
                        runSplitPairTransition(request, frame, primaryIntent, primaryActivity, app.id, secondaryIntent);
                    });
                }, 32);
                return;
            }
            runSplitPairLaunch(request, primaryIntent, primaryActivity, app.id, secondaryIntent);
            return;
        }
        // Rule 1: when no app is open (Home) or another app is floating, pop the selected app up directly
        // into the primary split half and show the second-app picker.
        int request = ++splitRequest;
        Intent primary = getPackageManager().getLaunchIntentForPackage(app.id);
        if (primary == null) {
            detach();
            return;
        }
        primary.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        WindowModeMonitor.recordRecent(this, app.id);
        detach();
        if (Build.VERSION.SDK_INT >= 30 && CircleCaptureService.isReady() && Settings.canDrawOverlays(this)) {
            main.postDelayed(() -> {
                if (request != splitRequest || !running || menu != null
                        || getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
                CircleCaptureService.captureOrRecent(frame -> {
                    if (frame == null || request != splitRequest || !running || menu != null
                            || getSystemService(KeyguardManager.class).isKeyguardLocked()) {
                        if (frame != null) frame.recycle();
                        launchDirectSplitFallback(request, app.id, primary);
                        return;
                    }
                    runSplitDirectPopTransition(request, frame, app.id, primary);
                });
            }, 32);
            return;
        }
        launchDirectSplitFallback(request, app.id, primary);
    }

    private void launchDirectSplitFallback(int request, String packageName, Intent primary) {
        launch(primary, splitPopupOptions(false));
        main.postDelayed(() -> showSplitPicker(request, packageName, new Intent(primary), primary.getComponent(), false), 160);
    }

    private void runSplitDirectPopTransition(int request, Bitmap frame, String packageName, Intent primary) {
        Rect bounds = displayBounds();
        boolean landscape = bounds.width() >= bounds.height();
        int screenW = bounds.width();
        int screenH = bounds.height();
        float targetW = landscape ? screenW * 0.5f : screenW;
        float targetH = landscape ? screenH : screenH * 0.5f;
        float maxCorner = Ui.dp(this, 32);

        float[] popProgress = {0f};
        android.graphics.Path revealHole = new android.graphics.Path();
        android.graphics.RectF holeRect = new android.graphics.RectF();
        android.graphics.Paint framePaint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        android.graphics.Rect srcRect = new android.graphics.Rect(0, 0, frame.getWidth(), frame.getHeight());
        android.graphics.Rect dstRect = new android.graphics.Rect(0, 0, screenW, screenH);

        View curtain = new View(this) {
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                if (frame.isRecycled()) return;
                float t = popProgress[0];
                if (t <= 0.001f) {
                    canvas.drawBitmap(frame, srcRect, dstRect, framePaint);
                    return;
                }
                // Punch an expanding rounded card hole over the left/top split half so the real app
                // pops smoothly outward from the center of its half while Home fades cleanly away.
                float s = 0.62f + 0.38f * t;
                float rw = targetW * s;
                float rh = targetH * s;
                float cx = targetW * 0.5f;
                float cy = targetH * 0.5f;
                holeRect.set(cx - rw * 0.5f, cy - rh * 0.5f, cx + rw * 0.5f, cy + rh * 0.5f);
                float r = maxCorner * (1f - t);
                revealHole.reset();
                revealHole.addRoundRect(holeRect, r, r, android.graphics.Path.Direction.CW);
                int alpha = Math.max(0, Math.min(255, Math.round(255f * (1f - t * t))));
                framePaint.setAlpha(alpha);
                canvas.save();
                canvas.clipOutPath(revealHole);
                canvas.drawBitmap(frame, srcRect, dstRect, framePaint);
                canvas.restore();
            }
        };

        WindowManager.LayoutParams curtainParams = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        curtainParams.setTitle("PenDock split pop");
        curtainParams.gravity = Gravity.TOP | Gravity.LEFT;
        if (Build.VERSION.SDK_INT >= 30) {
            curtainParams.setFitInsetsTypes(0);
            curtainParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        boolean[] cleaned = {false};
        splitTransitioning = true;
        Runnable cleanup = () -> {
            if (cleaned[0]) return;
            cleaned[0] = true;
            splitTransitioning = false;
            removeOverlay(curtain);
            frame.recycle();
        };
        try {
            manager.addView(curtain, curtainParams);
        } catch (RuntimeException unavailable) {
            cleanup.run();
            launchDirectSplitFallback(request, packageName, primary);
            return;
        }
        main.postDelayed(cleanup, 1600);

        // Wait ~50ms so the Home curtain is composited, then launch the split app invisibly behind it
        // until HarmonyOS's slide-from-right finishes, and reveal both app + picker popping cleanly in place.
        main.postDelayed(() -> {
            if (cleaned[0] || request != splitRequest || !running
                    || getSystemService(KeyguardManager.class).isKeyguardLocked()) {
                cleanup.run();
                return;
            }
            Intent noAnim = new Intent(primary).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            try {
                startActivity(noAnim, splitOptions(false, true));
            } catch (RuntimeException failure) {
                android.util.Log.w("PenDock", "Direct split launch failed", failure);
                cleanup.run();
                launchDirectSplitFallback(request, packageName, primary);
                return;
            }
            main.postDelayed(() -> {
                if (cleaned[0] || request != splitRequest || !running
                        || getSystemService(KeyguardManager.class).isKeyguardLocked()) {
                    cleanup.run();
                    return;
                }
                showSplitPicker(request, packageName, new Intent(primary), primary.getComponent(), false);
                android.animation.ValueAnimator pop = android.animation.ValueAnimator.ofFloat(0f, 1f);
                pop.setDuration(240);
                pop.setInterpolator(new android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f));
                pop.addUpdateListener(anim -> {
                    if (cleaned[0]) return;
                    popProgress[0] = (float) anim.getAnimatedValue();
                    curtain.invalidate();
                });
                pop.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator animation) {
                        cleanup.run();
                    }
                });
                pop.start();
            }, 260);
        }, 50);
    }

    private void runSplitPairLaunch(int request, Intent primaryIntent, ComponentName primaryActivity,
            String secondaryPackage, Intent secondaryIntent) {
        if (!SystemScreenshot.switchToHome(primaryActivity, () -> {
            if (request != splitRequest || !running || getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
            try {
                startActivity(primaryIntent, splitOptions(false));
                main.postDelayed(() -> {
                    if (request != splitRequest || !running || getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
                    WindowModeMonitor.recordRecent(this, secondaryPackage);
                    launch(secondaryIntent, splitOptions(true));
                }, 150);
            } catch (RuntimeException failure) {
                android.util.Log.w("PenDock", "Primary split launch failed", failure);
                launch(secondaryIntent, splitOptions(true));
            }
        })) {
            WindowModeMonitor.recordRecent(this, secondaryPackage);
            launch(secondaryIntent, splitOptions(true));
        }
    }

    private void runSplitPairTransition(int request, Bitmap frame, Intent primaryIntent,
            ComponentName primaryActivity, String secondaryPackage, Intent secondaryIntent) {
        Rect bounds = displayBounds();
        boolean landscape = bounds.width() >= bounds.height();
        int screenW = bounds.width();
        int screenH = bounds.height();

        FrameLayout curtain = new FrameLayout(this);
        android.widget.ImageView appView = new android.widget.ImageView(this);
        appView.setImageBitmap(frame);
        appView.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        appView.setPivotX(0f);
        appView.setPivotY(0f);
        curtain.addView(appView, new FrameLayout.LayoutParams(screenW, screenH, Gravity.TOP | Gravity.LEFT));

        WindowManager.LayoutParams curtainParams = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        curtainParams.setTitle("PenDock split pair");
        curtainParams.gravity = Gravity.TOP | Gravity.LEFT;
        if (Build.VERSION.SDK_INT >= 30) {
            curtainParams.setFitInsetsTypes(0);
            curtainParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        boolean[] cleaned = {false};
        splitTransitioning = true;
        Runnable cleanup = () -> {
            if (cleaned[0]) return;
            cleaned[0] = true;
            splitTransitioning = false;
            appView.setImageDrawable(null);
            removeOverlay(curtain);
            frame.recycle();
        };
        try {
            manager.addView(curtain, curtainParams);
        } catch (RuntimeException unavailable) {
            cleanup.run();
            runSplitPairLaunch(request, primaryIntent, primaryActivity, secondaryPackage, secondaryIntent);
            return;
        }
        main.postDelayed(cleanup, 1800);

        main.postDelayed(() -> {
            if (cleaned[0] || request != splitRequest || !running
                    || getSystemService(KeyguardManager.class).isKeyguardLocked()) {
                cleanup.run();
                return;
            }
            Intent primaryNoAnim = new Intent(primaryIntent).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            Intent secondaryNoAnim = new Intent(secondaryIntent).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            if (!SystemScreenshot.switchToHome(primaryActivity, () -> {
                if (cleaned[0] || request != splitRequest || !running
                        || getSystemService(KeyguardManager.class).isKeyguardLocked()) {
                    cleanup.run();
                    return;
                }
                try {
                    startActivity(primaryNoAnim, splitOptions(false, true));
                } catch (RuntimeException failure) {
                    android.util.Log.w("PenDock", "Primary split launch failed", failure);
                    cleanup.run();
                    launch(secondaryIntent, splitOptions(true));
                    return;
                }
                main.postDelayed(() -> {
                    if (cleaned[0] || request != splitRequest || !running
                            || getSystemService(KeyguardManager.class).isKeyguardLocked()) {
                        cleanup.run();
                        return;
                    }
                    WindowModeMonitor.recordRecent(this, secondaryPackage);
                    try {
                        startActivity(secondaryNoAnim, splitOptions(true, true));
                    } catch (RuntimeException failure) {
                        android.util.Log.w("PenDock", "Secondary split launch failed", failure);
                        cleanup.run();
                        launch(secondaryIntent, splitOptions(true));
                        return;
                    }
                    main.postDelayed(() -> {
                        if (cleaned[0] || request != splitRequest || !running
                                || getSystemService(KeyguardManager.class).isKeyguardLocked()) {
                            cleanup.run();
                            return;
                        }
                        android.view.animation.PathInterpolator curve =
                                new android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f);
                        appView.animate()
                                .scaleX(landscape ? 0.5f : 1f)
                                .scaleY(landscape ? 1f : 0.5f)
                                .setDuration(260)
                                .setInterpolator(curve)
                                .withEndAction(() -> {
                                    if (cleaned[0]) return;
                                    appView.animate()
                                            .alpha(0f)
                                            .setDuration(100)
                                            .withEndAction(cleanup)
                                            .start();
                                })
                                .start();
                    }, 180);
                }, 120);
            })) {
                cleanup.run();
                WindowModeMonitor.recordRecent(this, secondaryPackage);
                launch(secondaryIntent, splitOptions(true));
            }
        }, 50);
    }

    private void splitActive(Intent requested, WindowModeMonitor.FocusedApp selection) {
        if (selection == null) {
            detach();
            return;
        }
        String primaryPackage = selection.activity == null ? null : selection.activity.getPackageName();
        Intent primaryCopy = new Intent(requested);
        ComponentName primaryActivity = selection.activity;
        int request = ++splitRequest;
        detach();
        if (Build.VERSION.SDK_INT >= 30 && CircleCaptureService.isReady() && Settings.canDrawOverlays(this)) {
            main.postDelayed(() -> {
                if (request != splitRequest || !running || menu != null
                        || getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
                CircleCaptureService.captureOrRecent(frame -> {
                    if (frame == null || request != splitRequest || !running || menu != null
                            || getSystemService(KeyguardManager.class).isKeyguardLocked()) {
                        if (frame != null) frame.recycle();
                        runSplitLaunch(request, requested, primaryPackage, primaryCopy, primaryActivity);
                        return;
                    }
                    runSplitEnterTransition(request, frame, requested, primaryPackage, primaryCopy, primaryActivity);
                });
            }, 32);
            return;
        }
        runSplitLaunch(request, requested, primaryPackage, primaryCopy, primaryActivity);
    }

    private void runSplitLaunch(int request, Intent requested, String primaryPackage,
            Intent primaryCopy, ComponentName primaryActivity) {
        Bundle options = splitOptions(false, false);
        if (!SystemScreenshot.switchToHome(primaryActivity, () -> {
            if (request != splitRequest || !running || getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
            try {
                startActivity(requested, options);
                main.postDelayed(() -> showSplitPicker(request, primaryPackage, primaryCopy, primaryActivity, true), 180);
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

    private void runSplitEnterTransition(int request, Bitmap frame, Intent requested,
            String primaryPackage, Intent primaryCopy, ComponentName primaryActivity) {
        Rect bounds = displayBounds();
        boolean landscape = bounds.width() >= bounds.height();
        int screenW = bounds.width();
        int screenH = bounds.height();

        FrameLayout curtain = new FrameLayout(this);
        android.widget.ImageView appView = new android.widget.ImageView(this);
        appView.setImageBitmap(frame);
        appView.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        appView.setPivotX(0f);
        appView.setPivotY(0f);
        curtain.addView(appView, new FrameLayout.LayoutParams(screenW, screenH, Gravity.TOP | Gravity.LEFT));

        WindowManager.LayoutParams curtainParams = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        curtainParams.setTitle("PenDock split enter");
        curtainParams.gravity = Gravity.TOP | Gravity.LEFT;
        if (Build.VERSION.SDK_INT >= 30) {
            curtainParams.setFitInsetsTypes(0);
            curtainParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        boolean[] cleaned = {false};
        splitTransitioning = true;
        Runnable cleanup = () -> {
            if (cleaned[0]) return;
            cleaned[0] = true;
            splitTransitioning = false;
            appView.setImageDrawable(null);
            removeOverlay(curtain);
            frame.recycle();
        };
        try {
            manager.addView(curtain, curtainParams);
        } catch (RuntimeException unavailable) {
            cleanup.run();
            runSplitLaunch(request, requested, primaryPackage, primaryCopy, primaryActivity);
            return;
        }
        main.postDelayed(cleanup, 1600);

        // Wait until the curtain surface is committed before switching to Home and launching split mode.
        curtain.postOnAnimation(() -> curtain.postOnAnimation(() -> {
            if (cleaned[0] || request != splitRequest || !running
                    || getSystemService(KeyguardManager.class).isKeyguardLocked()) {
                cleanup.run();
                return;
            }
            Intent noAnim = new Intent(requested).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            Bundle options = splitOptions(false, true);
            if (!SystemScreenshot.switchToHome(primaryActivity, () -> {
                if (cleaned[0] || request != splitRequest || !running
                        || getSystemService(KeyguardManager.class).isKeyguardLocked()) {
                    cleanup.run();
                    return;
                }
                try {
                    startActivity(noAnim, options);
                } catch (RuntimeException failure) {
                    android.util.Log.w("PenDock", "Split windowingMode launch failed", failure);
                    cleanup.run();
                    if (!SystemScreenshot.toggleSplitScreen()) {
                        Toast.makeText(this, Appearance.text(this, R.string.split_failed), Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                main.postDelayed(() -> {
                    if (cleaned[0] || request != splitRequest || !running
                            || getSystemService(KeyguardManager.class).isKeyguardLocked()) {
                        cleanup.run();
                        return;
                    }
                    showSplitPicker(request, primaryPackage, primaryCopy, primaryActivity, true);
                    android.view.animation.PathInterpolator curve =
                            new android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f);
                    appView.animate()
                            .scaleX(landscape ? 0.5f : 1f)
                            .scaleY(landscape ? 1f : 0.5f)
                            .setDuration(260)
                            .setInterpolator(curve)
                            .withEndAction(() -> {
                                if (cleaned[0]) return;
                                appView.animate()
                                        .alpha(0f)
                                        .setDuration(100)
                                        .withEndAction(cleanup)
                                        .start();
                            })
                            .start();
                }, 200);
            })) {
                cleanup.run();
                if (!SystemScreenshot.toggleSplitScreen()) {
                    Toast.makeText(this, Appearance.text(this, R.string.split_failed), Toast.LENGTH_SHORT).show();
                }
            }
        }));
    }

    private int splitPickerDimension(Rect bounds, boolean landscape, String primaryPackage, int seamGuard) {
        Rect primary = WindowModeMonitor.splitPrimaryBounds(primaryPackage);
        if (landscape) {
            int startX = primary != null && primary.right > bounds.width() / 4 && primary.right < bounds.width() * 3 / 4
                    ? primary.right : bounds.width() / 2;
            int divider = primary != null ? Ui.dp(this, 8) : 0;
            int secondaryW = Math.max(Ui.dp(this, 240), bounds.width() - startX - divider);
            return Math.min(bounds.width(), secondaryW + seamGuard);
        }
        int startY;
        if (primary != null && primary.bottom > bounds.height() / 4 && primary.bottom < bounds.height() * 3 / 4) {
            // Start cleanly below the live top split app + horizontal split divider bar.
            startY = primary.bottom + Ui.dp(this, 12);
        } else {
            int topInset = 0;
            int bottomInset = 0;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets insets = manager.getCurrentWindowMetrics().getWindowInsets()
                        .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                topInset = insets.top;
                bottomInset = insets.bottom;
            }
            startY = (topInset + bounds.height() - bottomInset) / 2 + Ui.dp(this, 10);
        }
        int secondaryH = Math.max(Ui.dp(this, 240), bounds.height() - startY);
        return Math.min(bounds.height(), secondaryH + seamGuard);
    }

    private void showSplitPicker(int request, String primaryPackage, Intent primaryIntent,
            ComponentName primaryActivity, boolean animateIn) {
        if (request != splitRequest || !running || splitPicker != null || menu != null
                || getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
        if (!Settings.canDrawOverlays(this)) return;
        Rect bounds = displayBounds();
        boolean landscape = bounds.width() >= bounds.height();
        int seamGuard = Ui.dp(this, 20);
        int width = landscape ? splitPickerDimension(bounds, true, primaryPackage, seamGuard)
                : WindowManager.LayoutParams.MATCH_PARENT;
        int height = landscape ? WindowManager.LayoutParams.MATCH_PARENT
                : splitPickerDimension(bounds, false, primaryPackage, seamGuard);
        Set<String> excluded = new HashSet<>(WindowModeMonitor.floatingPackages());
        if (primaryPackage != null) excluded.add(primaryPackage);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.setTitle("PenDock split picker");
        params.gravity = landscape ? (Gravity.TOP | Gravity.END) : (Gravity.BOTTOM | Gravity.START);
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        if (Build.VERSION.SDK_INT >= 30) {
            params.setFitInsetsTypes(WindowInsets.Type.ime());
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        Runnable onClose = () -> {
            SplitAppPicker closing = splitPicker;
            int closeRequest = ++splitRequest;
            splitPicker = null;
            splitPickerPackage = null;
            splitPickerIntent = null;
            splitPickerActivity = null;
            if (closing != null) {
                params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                try {
                    manager.updateViewLayout(closing, params);
                } catch (RuntimeException ignored) { }
            }
            if (primaryIntent == null) {
                if (closing != null) removeOverlay(closing);
                return;
            }
            int closingW = landscape ? params.width : bounds.width();
            int closingH = landscape ? bounds.height() : params.height;
            expandPrimaryToFullscreen(closeRequest, closing, primaryIntent, primaryActivity,
                    bounds, landscape, closingW, closingH);
        };
        SplitAppPicker picker = new SplitAppPicker(Appearance.localize(this), shortcuts, entries,
                WindowModeMonitor.recentPackages(this), excluded, background,
                seamGuard, landscape,
                onClose, onClose, chosen -> {
            cancelSplitPicker();
            Intent intent = getPackageManager().getLaunchIntentForPackage(chosen.id);
            if (intent == null) {
                Toast.makeText(this, Appearance.text(this, R.string.open_named_failed, chosen.label),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
            WindowModeMonitor.recordRecent(this, chosen.id);
            launch(intent, splitPopupOptions(true));
        });
        if (animateIn) {
            picker.setTranslationX(landscape ? (bounds.width() / 2f) : 0f);
            picker.setTranslationY(landscape ? 0f : (bounds.height() / 2f));
        } else {
            picker.setScaleX(0.88f);
            picker.setScaleY(0.88f);
            picker.setAlpha(0f);
        }
        try {
            manager.addView(picker, params);
            splitPicker = picker;
            splitPickerPackage = primaryPackage;
            splitPickerIntent = primaryIntent == null ? null : new Intent(primaryIntent);
            splitPickerActivity = primaryActivity;
            picker.requestFocus();
            if (animateIn) {
                picker.animate()
                        .translationX(0f)
                        .translationY(0f)
                        .setDuration(260)
                        .setInterpolator(new android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f))
                        .start();
            } else {
                picker.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(240)
                        .setInterpolator(new android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f))
                        .start();
            }
            // Re-verify primary split bounds shortly after launch/rotation so vertical & horizontal
            // split dividers align tightly without overlapping the primary app.
            main.postDelayed(() -> {
                if (splitPicker != picker || request != splitRequest || !running || !picker.isAttachedToWindow()) return;
                Rect latest = displayBounds();
                boolean stillLandscape = latest.width() >= latest.height();
                if (stillLandscape != landscape) return;
                int updatedDim = splitPickerDimension(latest, landscape, primaryPackage, seamGuard);
                int currentDim = landscape ? params.width : params.height;
                if (Math.abs(updatedDim - currentDim) > Ui.dp(this, 3)) {
                    if (landscape) params.width = updatedDim;
                    else params.height = updatedDim;
                    try {
                        manager.updateViewLayout(picker, params);
                    } catch (RuntimeException ignored) { }
                }
            }, 220);
        } catch (WindowManager.BadTokenException | SecurityException unavailable) {
            android.util.Log.w("PenDock", "Could not show split app picker", unavailable);
        }
    }

    private void expandPrimaryToFullscreen(int request, SplitAppPicker closing, Intent requested,
            ComponentName primaryActivity, Rect bounds, boolean landscape, int pickerWidth, int pickerHeight) {
        if (Build.VERSION.SDK_INT >= 30 && CircleCaptureService.isReady() && Settings.canDrawOverlays(this)) {
            CircleCaptureService.capture(frame -> {
                if (frame == null || !running || request != splitRequest
                        || getSystemService(KeyguardManager.class).isKeyguardLocked()) {
                    if (frame != null) frame.recycle();
                    slideOutAndRestore(closing, requested, primaryActivity, landscape, pickerWidth, pickerHeight);
                    return;
                }
                runSplitExpandTransition(closing, frame, requested, primaryActivity, bounds, landscape);
            });
            return;
        }
        slideOutAndRestore(closing, requested, primaryActivity, landscape, pickerWidth, pickerHeight);
    }

    private void slideOutAndRestore(SplitAppPicker closing, Intent requested, ComponentName primaryActivity,
            boolean landscape, int pickerWidth, int pickerHeight) {
        if (closing != null) {
            closing.animate()
                    .translationX(landscape ? pickerWidth : 0)
                    .translationY(landscape ? 0 : pickerHeight)
                    .alpha(0f)
                    .setDuration(240)
                    .setInterpolator(new android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f))
                    .withEndAction(() -> removeOverlay(closing))
                    .start();
        }
        restoreFullscreenFromSplit(requested, primaryActivity, null);
    }

    private void runSplitExpandTransition(SplitAppPicker closing, Bitmap frame, Intent requested,
            ComponentName primaryActivity, Rect bounds, boolean landscape) {
        int fw = frame.getWidth();
        int fh = frame.getHeight();
        int halfW = Math.max(1, fw / 2);
        int halfH = Math.max(1, fh / 2);
        Bitmap primaryBmp;
        Bitmap pickerBmp;
        try {
            if (landscape) {
                primaryBmp = Bitmap.createBitmap(frame, 0, 0, halfW, fh);
                pickerBmp = Bitmap.createBitmap(frame, halfW, 0, fw - halfW, fh);
            } else {
                primaryBmp = Bitmap.createBitmap(frame, 0, 0, fw, halfH);
                pickerBmp = Bitmap.createBitmap(frame, 0, halfH, fw, fh - halfH);
            }
        } catch (RuntimeException failure) {
            frame.recycle();
            if (closing != null) removeOverlay(closing);
            restoreFullscreenFromSplit(requested, primaryActivity, null);
            return;
        }
        frame.recycle();

        FrameLayout curtain = new FrameLayout(this);
        android.widget.ImageView primaryView = new android.widget.ImageView(this);
        primaryView.setImageBitmap(primaryBmp);
        primaryView.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        android.widget.ImageView pickerView = new android.widget.ImageView(this);
        pickerView.setImageBitmap(pickerBmp);
        pickerView.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);

        int screenW = bounds.width();
        int screenH = bounds.height();
        int splitW = screenW / 2;
        int splitH = screenH / 2;
        if (landscape) {
            curtain.addView(primaryView, new FrameLayout.LayoutParams(splitW, screenH, Gravity.TOP | Gravity.LEFT));
            FrameLayout.LayoutParams rightParams = new FrameLayout.LayoutParams(
                    screenW - splitW, screenH, Gravity.TOP | Gravity.LEFT);
            rightParams.leftMargin = splitW;
            curtain.addView(pickerView, rightParams);
        } else {
            curtain.addView(primaryView, new FrameLayout.LayoutParams(screenW, splitH, Gravity.TOP | Gravity.LEFT));
            FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                    screenW, screenH - splitH, Gravity.TOP | Gravity.LEFT);
            bottomParams.topMargin = splitH;
            curtain.addView(pickerView, bottomParams);
        }

        WindowManager.LayoutParams curtainParams = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        curtainParams.setTitle("PenDock split transition");
        curtainParams.gravity = Gravity.TOP | Gravity.LEFT;
        if (Build.VERSION.SDK_INT >= 30) {
            curtainParams.setFitInsetsTypes(0);
            curtainParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        boolean[] cleaned = {false};
        splitTransitioning = true;
        Runnable cleanup = () -> {
            if (cleaned[0]) return;
            cleaned[0] = true;
            splitTransitioning = false;
            primaryView.setImageDrawable(null);
            pickerView.setImageDrawable(null);
            removeOverlay(curtain);
            primaryBmp.recycle();
            pickerBmp.recycle();
        };
        try {
            manager.addView(curtain, curtainParams);
        } catch (RuntimeException unavailable) {
            cleanup.run();
            if (closing != null) removeOverlay(closing);
            restoreFullscreenFromSplit(requested, primaryActivity, null);
            return;
        }

        // Safety cleanup in case animation callbacks do not fire.
        main.postDelayed(cleanup, 1400);

        // Wait until the curtain surface is committed before removing the picker and switching tasks.
        curtain.postOnAnimation(() -> curtain.postOnAnimation(() -> {
            if (cleaned[0]) return;
            if (closing != null) removeOverlay(closing);
            restoreFullscreenFromSplit(requested, primaryActivity, () -> main.postDelayed(() -> {
                if (cleaned[0]) return;
                android.view.animation.PathInterpolator curve =
                        new android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f);
                primaryView.animate()
                        .alpha(0f)
                        .setDuration(140)
                        .start();
                pickerView.animate()
                        .translationX(landscape ? (screenW - splitW) : 0)
                        .translationY(landscape ? 0 : (screenH - splitH))
                        .setDuration(260)
                        .setInterpolator(curve)
                        .withEndAction(cleanup)
                        .start();
            }, 220));
        }));
    }

    private void restoreFullscreenFromSplit(Intent requested, ComponentName primaryActivity,
            Runnable onLaunched) {
        Intent copy = new Intent(requested).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        Bundle options = ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle();
        options.putInt("android.activity.windowingMode", 1);
        Runnable launchFullscreen = () -> {
            if (running && !getSystemService(KeyguardManager.class).isKeyguardLocked()) {
                launch(copy, options, false);
            }
            if (onLaunched != null) onLaunched.run();
        };
        if (!SystemScreenshot.switchToHome(primaryActivity, launchFullscreen)) {
            launchFullscreen.run();
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
        splitPickerPackage = null;
        splitPickerIntent = null;
        splitPickerActivity = null;
        if (splitPicker == null) return;
        SplitAppPicker removed = splitPicker;
        splitPicker = null;
        removeOverlay(removed);
    }

    private Bundle splitOptions(boolean secondary) {
        return splitOptions(secondary, false);
    }

    private Bundle splitOptions(boolean secondary, boolean noAnim) {
        Bundle options = noAnim
                ? ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()
                : ActivityOptions.makeBasic().toBundle();
        boolean huawei = "HUAWEI".equalsIgnoreCase(Build.MANUFACTURER);
        options.putInt("android.activity.windowingMode", huawei ? (secondary ? 101 : 100) : (secondary ? 4 : 3));
        return options;
    }

    private Bundle splitPopupOptions(boolean secondary) {
        Bundle options = ActivityOptions.makeCustomAnimation(this, R.anim.split_popup_enter, 0).toBundle();
        boolean huawei = "HUAWEI".equalsIgnoreCase(Build.MANUFACTURER);
        options.putInt("android.activity.windowingMode", huawei ? (secondary ? 101 : 100) : (secondary ? 4 : 3));
        return options;
    }

    private void floatActive(Intent requested, WindowModeMonitor.FocusedApp selection) {
        if (selection == null) {
            detach();
            return;
        }
        Bundle options = floatingOptions(false);
        detach();
        if (options == null) {
            Toast.makeText(this, Appearance.text(this, R.string.float_failed), Toast.LENGTH_SHORT).show();
            return;
        }
        int request = ++splitRequest;
        if (Build.VERSION.SDK_INT >= 30 && CircleCaptureService.isReady() && Settings.canDrawOverlays(this)) {
            main.postDelayed(() -> {
                if (request != splitRequest || !running || menu != null
                        || getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
                CircleCaptureService.captureOrRecent(frame -> {
                    if (frame == null || request != splitRequest || !running || menu != null
                            || getSystemService(KeyguardManager.class).isKeyguardLocked()) {
                        if (frame != null) frame.recycle();
                        runFloatLaunch(requested, selection, options);
                        return;
                    }
                    runFloatEnterTransition(request, frame, requested, selection);
                });
            }, 32);
            return;
        }
        runFloatLaunch(requested, selection, options);
    }

    private void runFloatLaunch(Intent requested, WindowModeMonitor.FocusedApp selection, Bundle options) {
        if (!SystemScreenshot.switchToHome(selection.activity, () -> {
            if (running && !getSystemService(KeyguardManager.class).isKeyguardLocked()) {
                launch(requested, options, false);
            }
        })) {
            Toast.makeText(this, Appearance.text(this, R.string.float_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private void runFloatEnterTransition(int request, Bitmap frame, Intent requested,
            WindowModeMonitor.FocusedApp selection) {
        Rect display = displayBounds();
        int screenW = Math.max(1, display.width());
        int screenH = Math.max(1, display.height());
        Rect start = selection.bounds != null && !selection.bounds.isEmpty()
                ? new Rect(selection.bounds) : new Rect(0, 0, screenW, screenH);
        if (!start.intersect(0, 0, screenW, screenH) || start.width() < 20 || start.height() < 20) {
            start.set(0, 0, screenW, screenH);
        }

        int fw = frame.getWidth();
        int fh = frame.getHeight();
        int cropL = Math.max(0, Math.min(fw - 1, Math.round(start.left * (fw / (float) screenW))));
        int cropT = Math.max(0, Math.min(fh - 1, Math.round(start.top * (fh / (float) screenH))));
        int cropR = Math.max(cropL + 1, Math.min(fw, Math.round(start.right * (fw / (float) screenW))));
        int cropB = Math.max(cropT + 1, Math.min(fh, Math.round(start.bottom * (fh / (float) screenH))));
        Bitmap appBmp;
        try {
            if (cropL == 0 && cropT == 0 && cropR == fw && cropB == fh) {
                appBmp = frame;
            } else {
                appBmp = Bitmap.createBitmap(frame, cropL, cropT, cropR - cropL, cropB - cropT);
                if (appBmp != frame) frame.recycle();
            }
        } catch (RuntimeException failure) {
            frame.recycle();
            runFloatLaunch(requested, selection, floatingOptions(false));
            return;
        }

        String targetPkg = selection.activity == null ? null : selection.activity.getPackageName();
        float maxRadius = Ui.dp(this, 20);
        int startW = start.width();
        int startH = start.height();
        int[] clipRect = {0, 0, startW, startH};
        float[] cornerRadius = {0f};

        FrameLayout curtain = new FrameLayout(this);
        FrameLayout card = new FrameLayout(this);
        card.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(clipRect[0], clipRect[1], clipRect[2], clipRect[3], cornerRadius[0]);
            }
        });
        card.setClipToOutline(true);
        card.setPivotX(0f);
        card.setPivotY(0f);

        android.widget.ImageView appView = new android.widget.ImageView(this);
        appView.setImageBitmap(appBmp);
        appView.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        card.addView(appView, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                startW, startH, Gravity.TOP | Gravity.LEFT);
        cardParams.leftMargin = start.left;
        cardParams.topMargin = start.top;
        curtain.addView(card, cardParams);

        WindowManager.LayoutParams curtainParams = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        curtainParams.setTitle("PenDock float transition");
        curtainParams.gravity = Gravity.TOP | Gravity.LEFT;
        if (Build.VERSION.SDK_INT >= 30) {
            curtainParams.setFitInsetsTypes(0);
            curtainParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }

        boolean[] cleaned = {false};
        splitTransitioning = true;
        Runnable cleanup = () -> {
            if (cleaned[0]) return;
            cleaned[0] = true;
            splitTransitioning = false;
            appView.setImageDrawable(null);
            removeOverlay(curtain);
            appBmp.recycle();
        };
        try {
            manager.addView(curtain, curtainParams);
        } catch (RuntimeException unavailable) {
            cleanup.run();
            runFloatLaunch(requested, selection, floatingOptions(false));
            return;
        }
        main.postDelayed(cleanup, 1800);

        // Wait ~50ms after addView so the curtain's first frame is composited before switching to Home.
        main.postDelayed(() -> {
            if (cleaned[0] || request != splitRequest || !running
                    || getSystemService(KeyguardManager.class).isKeyguardLocked()) {
                cleanup.run();
                return;
            }
            Intent noAnim = new Intent(requested).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            Bundle options = floatingOptions(true);
            if (options == null || !SystemScreenshot.switchToHome(selection.activity, () -> {
                if (cleaned[0] || request != splitRequest || !running
                        || getSystemService(KeyguardManager.class).isKeyguardLocked()) {
                    cleanup.run();
                    return;
                }
                launch(noAnim, options, false);
                // Wait 180ms so HarmonyOS creates and positions the freeform task behind the
                // full-screen card, then read its task bounds and shrink directly onto the visible rect.
                main.postDelayed(() -> {
                    if (cleaned[0] || !running) {
                        cleanup.run();
                        return;
                    }
                    Rect liveTarget = WindowModeMonitor.floatingWindowBounds(targetPkg);
                    float targetScale = rememberedPackageScale(targetPkg);
                    Rect target = visibleFloatingBounds(liveTarget != null && !liveTarget.isEmpty()
                            ? liveTarget : floatingBounds(), targetScale);
                    float targetRadius = "HUAWEI".equalsIgnoreCase(Build.MANUFACTURER)
                            ? Ui.dp(this, 24) * targetScale : maxRadius;
                    int targetW = Math.max(1, target.width());
                    int targetH = Math.max(1, target.height());

                    android.animation.ValueAnimator shrink = android.animation.ValueAnimator.ofFloat(0f, 1f);
                    shrink.setDuration(300);
                    shrink.setInterpolator(new android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f));
                    shrink.addUpdateListener(anim -> {
                        if (cleaned[0]) return;
                        float t = (float) anim.getAnimatedValue();
                        float curL = start.left + (target.left - start.left) * t;
                        float curT = start.top + (target.top - start.top) * t;
                        float curW = startW + (targetW - startW) * t;
                        float curH = startH + (targetH - startH) * t;
                        float scale = Math.max(curW / startW, curH / startH);
                        float visW = Math.min(startW, curW / scale);
                        float visH = Math.min(startH, curH / scale);
                        int cL = Math.max(0, Math.round((startW - visW) * 0.5f));
                        int cT = 0;
                        int cR = Math.min(startW, cL + Math.max(1, Math.round(visW)));
                        int cB = Math.min(startH, cT + Math.max(1, Math.round(visH)));
                        clipRect[0] = cL;
                        clipRect[1] = cT;
                        clipRect[2] = cR;
                        clipRect[3] = cB;
                        cornerRadius[0] = targetRadius * t / Math.max(0.2f, scale);
                        card.setScaleX(scale);
                        card.setScaleY(scale);
                        card.setTranslationX(curL - start.left - cL * scale);
                        card.setTranslationY(curT - start.top - cT * scale);
                        card.invalidateOutline();
                    });
                    shrink.addListener(new android.animation.AnimatorListenerAdapter() {
                        @Override public void onAnimationEnd(android.animation.Animator animation) {
                            if (cleaned[0]) return;
                            card.animate()
                                    .alpha(0f)
                                    .setDuration(100)
                                    .withEndAction(() -> {
                                        cleanup.run();
                                        calibrateFloatingScale(targetPkg);
                                    })
                                    .start();
                        }
                    });
                    shrink.start();
                }, 180);
            })) {
                cleanup.run();
                Toast.makeText(this, Appearance.text(this, R.string.float_failed), Toast.LENGTH_SHORT).show();
            }
        }, 50);
    }

    private Rect floatingBounds() {
        Rect bounds = displayBounds();
        int width = RingConfig.floatingWidth(this, bounds.width());
        int height = RingConfig.floatingHeight(this, bounds.height());
        int x = bounds.centerX() - width / 2;
        int y = bounds.centerY() - height / 2;
        return new Rect(x, y, x + width, y + height);
    }

    private Bundle floatingOptions() {
        return floatingOptions(false);
    }

    private Bundle floatingOptions(boolean noAnim) {
        Bundle options = null;
        if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)) {
            Rect rect = floatingBounds();
            ActivityOptions base = noAnim
                    ? ActivityOptions.makeCustomAnimation(this, 0, 0)
                    : ActivityOptions.makeBasic();
            options = base.setLaunchBounds(rect).toBundle();
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
        detach(menu);
    }

    private void detach(RadialMenu target) {
        menuRequest++;
        if (target != null && target == menu) menu = null;
        removeOverlay(target);
    }

    private Bundle floatingKeepBoundsOptions() {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)) {
            return null;
        }
        Bundle options = ActivityOptions.makeBasic().toBundle();
        boolean huawei = "HUAWEI".equalsIgnoreCase(Build.MANUFACTURER);
        options.putInt("android.activity.windowingMode", huawei ? 102 : 5);
        return options;
    }

    private float rememberedPackageScale(String packageName) {
        if (!"HUAWEI".equalsIgnoreCase(Build.MANUFACTURER)) return 1f;
        if (packageName != null) {
            float saved = getSharedPreferences("floating_scales", MODE_PRIVATE).getFloat(packageName, -1f);
            if (saved >= 0.48f && saved <= 1.05f) return saved;
        }
        return 0.816f;
    }

    private void savePackageScale(String packageName, float scale) {
        if (packageName == null || scale < 0.48f || scale > 1.05f) return;
        getSharedPreferences("floating_scales", MODE_PRIVATE).edit().putFloat(packageName, scale).apply();
    }

    private void calibrateFloatingScale(String packageName) {
        if (packageName == null || !"HUAWEI".equalsIgnoreCase(Build.MANUFACTURER)
                || Build.VERSION.SDK_INT < 30 || !CircleCaptureService.isReady()) return;
        main.postDelayed(() -> {
            if (!running || splitTransitioning || menu != null
                    || getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
            WindowModeMonitor.FloatingWindow fw = WindowModeMonitor.findFloatingWindow(packageName);
            if (fw == null || fw.bounds.isEmpty()) return;
            CircleCaptureService.capture(frame -> {
                if (frame == null) return;
                float measured = -1f;
                try {
                    measured = measureHuaweiFloatingScale(frame, fw.bounds);
                } finally {
                    frame.recycle();
                }
                if (measured > 0f) {
                    if (fw.windowId >= 0) floatingScales.put(fw.windowId, measured);
                    savePackageScale(packageName, measured);
                }
            });
        }, 320);
    }

    private Rect visibleFloatingBounds(Rect taskBounds, float scale) {
        Rect rect = new Rect(taskBounds);
        if (scale > 0f && Math.abs(scale - 1f) > 0.001f) {
            rect.right = rect.left + Math.round(rect.width() * scale);
            rect.bottom = rect.top + Math.round(rect.height() * scale);
        }
        return rect;
    }

    private void showFloatingPulse(int windowId, String packageName, Rect rawBounds, boolean allowRecentFrame) {
        if (!running || manager == null || rawBounds == null || rawBounds.isEmpty()
                || splitTransitioning || !Settings.canDrawOverlays(this)
                || getSystemService(KeyguardManager.class).isKeyguardLocked()) {
            return;
        }
        int request = ++pulseRequest;
        boolean huawei = "HUAWEI".equalsIgnoreCase(Build.MANUFACTURER);
        if (huawei && Build.VERSION.SDK_INT >= 30 && CircleCaptureService.isReady()) {
            // Wait ~80ms so HarmonyOS updates the focused floating handle pill and brings it forward,
            // then measure the live SurfaceFlinger scale (handles resized windows & mini-windows).
            main.postDelayed(() -> {
                if (request != pulseRequest || !running || splitTransitioning
                        || getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
                java.util.function.Consumer<Bitmap> onFrame = frame -> {
                    float scale = -1f;
                    if (frame != null) {
                        try {
                            scale = measureHuaweiFloatingScale(frame, rawBounds);
                        } finally {
                            frame.recycle();
                        }
                    }
                    if (request != pulseRequest || !running || splitTransitioning
                            || getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
                    if (scale > 0f) {
                        if (windowId >= 0) floatingScales.put(windowId, scale);
                        savePackageScale(packageName, scale);
                    } else {
                        Float cached = windowId >= 0 ? floatingScales.get(windowId) : null;
                        scale = cached != null ? cached : rememberedPackageScale(packageName);
                    }
                    renderFloatingPulse(visibleFloatingBounds(rawBounds, scale), Ui.dp(this, 24) * scale);
                };
                if (allowRecentFrame) CircleCaptureService.captureOrRecent(onFrame);
                else CircleCaptureService.capture(onFrame);
            }, allowRecentFrame ? 30 : 80);
            return;
        }
        float scale = rememberedPackageScale(packageName);
        float radius = huawei ? Ui.dp(this, 24) * scale : Ui.dp(this, 20);
        renderFloatingPulse(visibleFloatingBounds(rawBounds, scale), radius);
    }

    private float measureHuaweiFloatingScale(Bitmap frame, Rect taskBounds) {
        Rect display = displayBounds();
        int screenW = Math.max(1, display.width());
        int screenH = Math.max(1, display.height());
        int fw = frame.getWidth();
        int fh = frame.getHeight();
        float fx = fw / (float) screenW;
        float fy = fh / (float) screenH;

        float bLeft = taskBounds.left * fx;
        float bTop = taskBounds.top * fy;
        float bUw = taskBounds.width() * fx;
        if (bUw < 60f) return -1f;

        int scanStartX = Math.max(0, Math.round(bLeft + 24f * fx));
        int scanEndX = Math.min(fw, Math.round(bLeft + bUw));
        int scanW = scanEndX - scanStartX;
        if (scanW < 40) return -1f;
        int startY = Math.max(0, Math.round(bTop + 10f * fy));
        int endY = Math.min(fh - 1, Math.round(bTop + 68f * fy));
        if (endY <= startY) return -1f;

        // Pass 1: Focused HarmonyOS floating handle pill (vivid royal blue centered in a uniform caption bar).
        int[] row = new int[scanW];
        int bestLen = 0;
        float bestScale = -1f;
        for (int y = startY; y <= endY; y += 2) {
            frame.getPixels(row, 0, scanW, scanStartX, y, scanW, 1);
            int runStart = -1;
            for (int i = 0; i <= scanW; i++) {
                boolean isPill = false;
                if (i < scanW) {
                    int rgb = row[i];
                    int r = (rgb >> 16) & 0xff;
                    int g = (rgb >> 8) & 0xff;
                    int b = rgb & 0xff;
                    isPill = b >= 215 && (b - r) >= 160 && (b - g) >= 110;
                }
                if (isPill) {
                    if (runStart < 0) runStart = i;
                } else if (runStart >= 0) {
                    int len = i - runStart;
                    if (len >= 28 && len > bestLen) {
                        int leftBgIdx = Math.max(0, runStart - Math.round(16f * fx));
                        int rightBgIdx = Math.min(scanW - 1, i + Math.round(15f * fx));
                        int cL = row[leftBgIdx];
                        int cR = row[rightBgIdx];
                        int bgDiff = Math.abs(((cL >> 16) & 0xff) - ((cR >> 16) & 0xff))
                                + Math.abs(((cL >> 8) & 0xff) - ((cR >> 8) & 0xff))
                                + Math.abs((cL & 0xff) - (cR & 0xff));
                        float cx = scanStartX + (runStart + i - 1) * 0.5f;
                        float s = (cx - bLeft) * 2f / bUw;
                        float expectedY = bTop + 43f * s * fy;
                        float expectedLen = 135f * s * fx;
                        if (bgDiff <= 30
                                && s >= 0.32f && s <= 1.05f
                                && bLeft + bUw * s <= fw + 24f * fx
                                && Math.abs(y - expectedY) <= 22f * fy
                                && len >= expectedLen * 0.45f
                                && len <= expectedLen * 1.65f) {
                            bestLen = len;
                            bestScale = s;
                        }
                    }
                    runStart = -1;
                }
            }
        }
        if (bestScale > 0f) return bestScale;

        // Pass 2: Mini-windows and unfocused/transitioning windows without a blue handle pill:
        // sample the clean caption background above the icons (left+120, top+12) and scan right across
        // multiple header rows to locate the rendered right edge of the floating window.
        int refX = Math.max(0, Math.min(fw - 1, Math.round(bLeft + 120f * fx)));
        int refY = Math.max(0, Math.min(fh - 1, Math.round(bTop + 12f * fy)));
        int flatScanW = Math.min(fw, Math.round(bLeft + bUw)) - refX;
        if (flatScanW < 60) return -1f;
        int refRgb = frame.getPixel(refX, refY);
        int rr = (refRgb >> 16) & 0xff;
        int rg = (refRgb >> 8) & 0xff;
        int rb = refRgb & 0xff;
        int[] flatRow = new int[flatScanW];
        int maxRight = -1;
        int[] sampleDys = {8, 10, 12, 14, 16, 20, 24, 28, 32};
        for (int dy : sampleDys) {
            int y = Math.round(bTop + dy * fy);
            if (y < 0 || y >= fh) continue;
            frame.getPixels(flatRow, 0, flatScanW, refX, y, flatScanW, 1);
            int lastMatch = 0;
            int miss = 0;
            for (int i = 0; i < flatScanW; i++) {
                int rgb = flatRow[i];
                int diff = Math.abs(((rgb >> 16) & 0xff) - rr)
                        + Math.abs(((rgb >> 8) & 0xff) - rg)
                        + Math.abs((rgb & 0xff) - rb);
                if (diff <= 34) {
                    lastMatch = i;
                    miss = 0;
                } else if (++miss >= 24) {
                    break;
                }
            }
            int rightX = refX + lastMatch + 1;
            if (rightX > maxRight) maxRight = rightX;
        }
        if (maxRight > refX + 60) {
            float s = (maxRight - bLeft) / bUw;
            if (s >= 0.34f && s <= 1.05f && bLeft + bUw * s <= fw + 24f * fx) {
                return s;
            }
        }
        return -1f;
    }

    private void renderFloatingPulse(Rect target, float cornerRadiusPx) {
        if (!running || manager == null || target.width() < 20 || target.height() < 20) return;
        cancelFloatingPulse();
        FloatingPulse[] holder = new FloatingPulse[1];
        FloatingPulse pulse = new FloatingPulse(this, target, cornerRadiusPx, () -> {
            if (floatingPulse == holder[0]) cancelFloatingPulse();
        });
        holder[0] = pulse;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.setTitle("PenDock floating pulse");
        params.gravity = Gravity.TOP | Gravity.LEFT;
        if (Build.VERSION.SDK_INT >= 30) {
            params.setFitInsetsTypes(0);
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        try {
            manager.addView(pulse, params);
            floatingPulse = pulse;
            pulse.start();
        } catch (RuntimeException unavailable) {
            pulse.stop();
        }
    }

    private void cancelFloatingPulse() {
        pulseRequest++;
        if (floatingPulse == null) return;
        FloatingPulse removed = floatingPulse;
        floatingPulse = null;
        removed.stop();
        removeOverlay(removed);
    }

    private void removeOverlay(View view) {
        if (manager == null || view == null) return;
        try {
            manager.removeViewImmediate(view);
        } catch (IllegalArgumentException ignored) { }
    }

    @Override public void onDestroy() {
        WindowModeMonitor.setFloatingPingListener(null);
        if (listening) {
            unregisterReceiver(stylus);
            unregisterReceiver(screenChange);
            unregisterReceiver(packagesChanged);
            shortcuts.preferences().unregisterOnSharedPreferenceChangeListener(shortcutsChanged);
            Appearance.preferences(this).unregisterOnSharedPreferenceChangeListener(appearanceChanged);
        }
        main.removeCallbacksAndMessages(null);
        cancelCircleSearch();
        cancelTranslateOverlay();
        cancelSplitPicker();
        cancelLaserOverlay();
        cancelFloatingPulse();
        background.shutdown();
        detach();
        setRunning(false);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
