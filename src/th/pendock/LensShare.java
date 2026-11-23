package th.pendock;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

final class LensShare {
    private static final String GOOGLE = "com.google.android.googlequicksearchbox";
    private static final String LENS = "com.google.android.apps.search.lens.LensShareEntryPointActivity";

    private LensShare() { }

    static boolean isAvailable(Context context) {
        ResolveInfo target = context.getPackageManager().resolveActivity(intent(), PackageManager.MATCH_DEFAULT_ONLY);
        return target != null && target.activityInfo != null && target.activityInfo.exported;
    }

    static void send(Context context, Uri crop) {
        Intent share = intent()
                .putExtra(Intent.EXTRA_STREAM, crop)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        share.setClipData(new ClipData("PenDock", new String[] {"image/png"}, new ClipData.Item(crop)));
        // Permission must survive Lens's entry activity forwarding the image internally.
        context.grantUriPermission(GOOGLE, crop, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            context.startActivity(share);
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            context.revokeUriPermission(GOOGLE, crop, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            throw unavailable;
        }
    }

    static void share(Context context, Uri crop, String title) {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, crop)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ClipData clip = new ClipData("PenDock", new String[] {"image/png"}, new ClipData.Item(crop));
        send.setClipData(clip);
        Intent chooser = Intent.createChooser(send, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        chooser.setClipData(clip);
        context.startActivity(chooser);
    }

    private static Intent intent() {
        return new Intent(Intent.ACTION_SEND).setType("image/png").setClassName(GOOGLE, LENS);
    }
}
