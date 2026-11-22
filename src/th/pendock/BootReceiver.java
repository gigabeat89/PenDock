package th.pendock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        BatteryWidget.refreshAll(context);
        if (!DockService.isEnabled(context) || !RingConfig.isAutoStart(context)
                || !Settings.canDrawOverlays(context)) return;
        try {
            context.startForegroundService(new Intent(context, DockService.class));
        } catch (RuntimeException unavailable) {
            android.util.Log.w("PenDock", "Could not auto-start PenDock after " + action, unavailable);
        }
    }
}
