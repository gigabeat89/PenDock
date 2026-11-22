package th.pendock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    private static final Appearance.Palette LIGHT = new Appearance.Palette(false);
    private static final Appearance.Palette DARK = new Appearance.Palette(true);

    static Appearance.Palette colors(Context context) {
        return Appearance.isDark(context) ? DARK : LIGHT;
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable shape(Context context, int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radius));
        return drawable;
    }

    static LinearLayout column(Context context) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    static GradientDrawable border(Context context, int color, int radius, int stroke) {
        GradientDrawable drawable = shape(context, color, radius);
        drawable.setStroke(dp(context, 1), stroke);
        return drawable;
    }

    static RippleDrawable pressable(Context context, int color, int radius, int stroke) {
        return new RippleDrawable(ColorStateList.valueOf(colors(context).pick(0x200a226f, 0x30ccd9ff)),
                border(context, color, radius, stroke), shape(context, 0xffffffff, radius));
    }

    static TextView text(Context context, String label, int size, int color, boolean bold) {
        TextView text = new TextView(context);
        text.setText(label);
        text.setTextSize(size);
        text.setTextColor(color);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    static TextView action(Context context, String label, boolean primary, Runnable callback) {
        Appearance.Palette colors = colors(context);
        TextView button = text(context, label, 14, primary ? colors.pick(0xffffffff, 0xff11182c) : colors.accent, true);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(context, 48));
        button.setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8));
        button.setBackground(pressable(context, primary ? colors.accent : colors.surface, 12,
                primary ? colors.accent : colors.border));
        button.setAccessibilityDelegate(new android.view.View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(
                    android.view.View view, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(view, info);
                info.setClassName(android.widget.Button.class.getName());
            }
        });
        button.setFocusable(true);
        button.setOnClickListener(view -> callback.run());
        return button;
    }

    static ImageButton iconAction(Context context, int icon, int label, Runnable callback) {
        Appearance.Palette colors = colors(context);
        ImageButton button = new ImageButton(context);
        button.setImageResource(icon);
        button.setImageTintList(ColorStateList.valueOf(colors.text));
        button.setBackground(pressable(context, colors.surface, 12, colors.border));
        button.setContentDescription(context.getString(label));
        button.setOnClickListener(view -> callback.run());
        return button;
    }

    static Notification.Builder notification(Context context, String channelId, String title, String detail) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(channelId, title,
                NotificationManager.IMPORTANCE_LOW));
        Intent home = new Intent(context, MainActivity.class);
        return new Notification.Builder(context, channelId).setSmallIcon(R.drawable.ic_pen)
                .setContentTitle(title).setContentText(detail).setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(context, 0, home,
                        PendingIntent.FLAG_IMMUTABLE));
    }
}
