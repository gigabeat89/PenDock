package th.pendock;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.InputDevice;
import android.view.WindowManager;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Lightweight privacy-safe in-memory diagnostic log & device snapshot for troubleshooting. */
final class DiagLog {
    private static final int MAX_ENTRIES = 120;
    private static final ArrayDeque<String> BUFFER = new ArrayDeque<>(MAX_ENTRIES);

    private DiagLog() { }

    static synchronized void log(String tag, String message) {
        String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String line = time + " [" + tag + "] " + message;
        if (BUFFER.size() >= MAX_ENTRIES) BUFFER.removeFirst();
        BUFFER.addLast(line);
        android.util.Log.d("PenDock", "[" + tag + "] " + message);
    }

    static String buildReport(Context context, List<String> liveProbeEvents) {
        StringBuilder sb = new StringBuilder();
        String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        sb.append("=== PenDock Diagnostics ===\n");
        sb.append("Generated: ").append(now).append('\n');

        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            sb.append("App: ").append(pi.versionName).append(" (code ").append(pi.getLongVersionCode()).append(")\n");
        } catch (Exception ignored) { }

        sb.append("\n--- Device ---\n");
        sb.append("Manufacturer: ").append(Build.MANUFACTURER).append('\n');
        sb.append("Brand / Model: ").append(Build.BRAND).append(" / ").append(Build.MODEL).append('\n');
        sb.append("Device / Product: ").append(Build.DEVICE).append(" / ").append(Build.PRODUCT).append('\n');
        sb.append("Display: ").append(Build.DISPLAY).append('\n');
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        String hmos = sysProp("hw_sc.build.platform.version");
        String emui = sysProp("ro.build.version.emui");
        if (!hmos.isEmpty()) sb.append("HarmonyOS: ").append(hmos).append('\n');
        if (!emui.isEmpty()) sb.append("EMUI: ").append(emui).append('\n');

        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        WindowManager wm = context.getSystemService(WindowManager.class);
        boolean xBlur = Build.VERSION.SDK_INT >= 31 && wm != null && wm.isCrossWindowBlurEnabled();
        sb.append("Screen: ").append(dm.widthPixels).append('x').append(dm.heightPixels)
                .append(" @ ").append(dm.densityDpi).append("dpi, crossWindowBlur=").append(xBlur).append('\n');

        sb.append("\n--- Permissions & Services ---\n");
        sb.append("Overlay (canDrawOverlays): ").append(Settings.canDrawOverlays(context)).append('\n');
        sb.append("DockService: running=").append(DockService.running)
                .append(", enabled=").append(DockService.isEnabled(context)).append('\n');
        sb.append("SystemScreenshot A11y: ").append(SystemScreenshot.isReady()).append('\n');
        sb.append("WindowModeMonitor A11y: ").append(WindowModeMonitor.isReady()).append('\n');
        sb.append("CircleCaptureService A11y: ").append(CircleCaptureService.isReady()).append('\n');

        sb.append("\n--- Pen & Ring Settings ---\n");
        sb.append("penOnlyMenu=").append(PenInput.isPenOnlyMenu(context))
                .append(", requireNearScreen=").append(PenInput.requiresNearScreen(context))
                .append(", altSpaceRing=").append(PenInput.isHmosSpaceRingEnabled(context))
                .append(", autoStart=").append(RingConfig.isAutoStart(context)).append('\n');
        sb.append("style=").append(RingConfig.ringStyle(context))
                .append(", theme=").append(RingConfig.ringTheme(context))
                .append(", blurDp=").append(RingConfig.ringBlurDp(context))
                .append(", dimPct=").append(RingConfig.ringDimPercent(context))
                .append(", launch=").append(RingConfig.launchMode(context)).append('\n');
        sb.append("ringItems=").append(new Shortcuts(context).selected().size())
                .append(", excludedApps=").append(new ExcludedApps(context).packages().size()).append('\n');

        sb.append("\n--- Connected Stylus / Keyboard Devices ---\n");
        int found = 0;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice dev = InputDevice.getDevice(id);
            if (dev == null || dev.isVirtual()) continue;
            String name = dev.getName() == null ? "" : dev.getName();
            String lower = name.toLowerCase(Locale.ROOT);
            int src = dev.getSources();
            boolean interesting = (src & InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS
                    || (src & InputDevice.SOURCE_BLUETOOTH_STYLUS) == InputDevice.SOURCE_BLUETOOTH_STYLUS
                    || (src & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD
                    || lower.contains("pen") || lower.contains("stylus")
                    || lower.contains("pencil") || lower.contains("glide")
                    || lower.contains("touchpad") || lower.contains("keyboard");
            if (!interesting) continue;
            found++;
            sb.append("• [").append(id).append("] ").append(name)
                    .append(" (vid=").append(String.format(Locale.US, "%04x", dev.getVendorId()))
                    .append(", pid=").append(String.format(Locale.US, "%04x", dev.getProductId()))
                    .append(", ext=").append(dev.isExternal())
                    .append(", src=0x").append(Integer.toHexString(src)).append(")\n");
        }
        if (found == 0) sb.append("(none detected)\n");

        if (liveProbeEvents != null && !liveProbeEvents.isEmpty()) {
            sb.append("\n--- Live Double-Tap Probe ---\n");
            for (String ev : liveProbeEvents) {
                sb.append(ev).append('\n');
            }
        }

        sb.append("\n--- Recent PenDock Events ---\n");
        synchronized (DiagLog.class) {
            if (BUFFER.isEmpty()) {
                sb.append("(no events yet)\n");
            } else {
                for (String entry : BUFFER) {
                    sb.append(entry).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static String sysProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            Object val = get.invoke(null, key, "");
            return val == null ? "" : val.toString().trim();
        } catch (Exception ignored) {
            return "";
        }
    }
}
