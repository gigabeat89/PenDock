package th.pendock;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

public class SystemScreenshot extends AccessibilityService {
    private static SystemScreenshot connected;
    private boolean tracking;
    private String homePackage;
    private Intent homeIntent;
    private ComponentName origin;
    private Runnable homeAction;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable completeSwitch = this::finishHomeSwitch;
    static boolean isReady() { return connected != null; }

    static boolean tracksActiveApp() {
        return WindowModeMonitor.isReady();
    }

    static void refreshTracking() {
        if (connected != null && connected.tracking != tracksActiveApp()) connected.configure();
    }

    static boolean take() {
        return connected != null && connected.performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT);
    }

    static boolean lockScreen() {
        return connected != null && connected.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    static boolean toggleSplitScreen() {
        return connected != null && connected.performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN);
    }

    static boolean switchToHome(ComponentName focusedActivity, Runnable action) {
        SystemScreenshot service = connected;
        if (service == null || !service.tracking || service.homeIntent == null) return false;
        service.homeAction = action;
        service.origin = focusedActivity;
        try {
            service.startActivity(service.homeIntent,
                    ActivityOptions.makeCustomAnimation(service, 0, 0).toBundle());
        } catch (RuntimeException unavailable) {
            if (!service.performGlobalAction(GLOBAL_ACTION_HOME)) {
                service.homeAction = null;
                return false;
            }
        }
        // Some Huawei launcher transitions have no accessibility announcement.
        service.main.removeCallbacks(service.completeSwitch);
        service.main.postDelayed(service.completeSwitch, 110);
        return true;
    }

    private void finishHomeSwitch() {
        Runnable action = homeAction;
        homeAction = null;
        if (action == null || !tracking) return;
        WindowModeMonitor.FocusedApp focused = WindowModeMonitor.focusedApp();
        if (focused != null && focused.activity != null
                && !focused.activity.getPackageName().equals(homePackage)
                && !focused.activity.getPackageName().equals("com.huawei.android.launcher")
                && !focused.activity.equals(origin)) return;
        action.run();
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        connected = this;
        configure();
    }

    private void configure() {
        homeAction = null;
        main.removeCallbacksAndMessages(null);
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo handler = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        homePackage = handler == null ? "com.huawei.android.launcher" : handler.activityInfo.packageName;
        homeIntent = home.setPackage(homePackage)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        tracking = tracksActiveApp();
        info.eventTypes = tracking ? AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED : 0;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        setServiceInfo(info);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!tracking || homeAction == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        if (pkg.equals(homePackage) || pkg.equals("com.huawei.android.launcher")) {
            main.removeCallbacks(completeSwitch);
            // Let the launcher finish taking focus before the existing task is reopened.
            main.postDelayed(completeSwitch, 32);
        }
    }

    @Override public void onInterrupt() { }

    @Override public boolean onUnbind(Intent intent) {
        if (connected == this) connected = null;
        main.removeCallbacksAndMessages(null);
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        if (connected == this) connected = null;
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
