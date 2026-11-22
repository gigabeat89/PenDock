package th.pendock;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.XmlResourceParser;
import java.io.IOException;
import org.xmlpull.v1.XmlPullParserException;

final class ActiveWindow {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    private ActiveWindow() { }

    static Intent floatingLauncher(Context context, ComponentName active) {
        if (active == null) return null;
        PackageManager packages = context.getPackageManager();
        if (!packages.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)) return null;
        String name = active.getPackageName();
        if (!isAppWindow(context, name)) return null;
        Intent intent = packages.getLaunchIntentForPackage(name);
        if (intent == null || !supportsMultiWindow(packages, active)) return null;
        // Bring the existing task forward without resetting its current screen.
        return intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    static boolean isAppWindow(Context context, String name) {
        if (name == null || name.equals(context.getPackageName()) || name.equals("com.android.settings")
                || name.equals("com.android.systemui") || name.equals("com.huawei.android.launcher")) return false;
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo launcher = context.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return launcher == null || !name.equals(launcher.activityInfo.packageName);
    }

    private static boolean supportsMultiWindow(PackageManager packages, ComponentName active) {
        try {
            ActivityInfo info = packages.getActivityInfo(active, 0);
            String className = info.targetActivity == null ? info.name : info.targetActivity;
            boolean resizable = info.applicationInfo.targetSdkVersion >= 24;
            try (XmlResourceParser manifest = packages.getResourcesForApplication(info.applicationInfo)
                    .getAssets().openXmlResourceParser("AndroidManifest.xml")) {
                for (int event = manifest.getEventType(); event != XmlResourceParser.END_DOCUMENT;
                        event = manifest.next()) {
                    if (event != XmlResourceParser.START_TAG) continue;
                    if ("application".equals(manifest.getName())) {
                        resizable = manifest.getAttributeBooleanValue(ANDROID, "resizeableActivity", resizable);
                    }
                    if ("activity".equals(manifest.getName()) && className.equals(
                            fullClassName(info.packageName, manifest.getAttributeValue(ANDROID, "name")))) {
                        return manifest.getAttributeBooleanValue(ANDROID, "resizeableActivity", resizable);
                    }
                }
            }
            return resizable;
        } catch (PackageManager.NameNotFoundException | IOException | XmlPullParserException | RuntimeException failure) {
            return false;
        }
    }

    static String fullClassName(String pkg, String name) {
        if (name == null || name.isEmpty()) return "";
        if (name.charAt(0) == '.') return pkg + name;
        return name.indexOf('.') < 0 ? pkg + "." + name : name;
    }
}
