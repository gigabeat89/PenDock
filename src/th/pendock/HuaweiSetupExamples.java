package th.pendock;

import android.app.Activity;
import android.app.Dialog;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class HuaweiSetupExamples {
    private final Activity activity;
    private final Appearance.Palette colors;

    private HuaweiSetupExamples(Activity activity) {
        this.activity = activity;
        colors = Ui.colors(activity);
    }

    static void showLaunchManager(Activity activity) {
        HuaweiSetupExamples examples = new HuaweiSetupExamples(activity);
        examples.show(R.string.huawei_background_example, examples.launchManager());
    }

    static void showRecentTasks(Activity activity) {
        HuaweiSetupExamples examples = new HuaweiSetupExamples(activity);
        examples.show(R.string.huawei_recents_example, examples.recentTasks());
    }

    private LinearLayout launchManager() {
        LinearLayout content = Ui.column(activity);
        content.setPadding(dp(20), dp(12), dp(20), dp(8));

        LinearLayout automatic = card(colors.rail);
        LinearLayout brand = new LinearLayout(activity);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(activity);
        logo.setImageResource(R.drawable.ic_launcher_ring);
        brand.addView(logo, new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView app = Ui.text(activity, "PenDock", 16, colors.text, true);
        app.setPadding(dp(8), 0, 0, 0);
        brand.addView(app);
        automatic.addView(brand, new LinearLayout.LayoutParams(-1, -2));
        automatic.addView(toggleRow(R.string.launch_example_automatic, false));
        content.addView(automatic, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout manual = card(colors.surface);
        TextView title = Ui.text(activity, activity.getString(R.string.launch_example_manual), 16, colors.text, true);
        title.setAccessibilityHeading(true);
        title.setPadding(0, 0, 0, dp(5));
        manual.addView(title);
        manual.addView(toggleRow(R.string.launch_example_auto_launch, true));
        manual.addView(toggleRow(R.string.launch_example_secondary_launch, true));
        manual.addView(toggleRow(R.string.launch_example_background, true));
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(-1, -2);
        second.topMargin = dp(12);
        content.addView(manual, second);

        return content;
    }

    private LinearLayout recentTasks() {
        LinearLayout content = Ui.column(activity);
        content.setPadding(dp(20), dp(12), dp(20), dp(8));
        LinearLayout scene = card(colors.rail);
        scene.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView screen = Ui.text(activity, activity.getString(R.string.recents_title), 14, colors.muted, true);
        scene.addView(screen);

        LinearLayout tile = card(colors.surface);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(activity);
        logo.setImageResource(R.drawable.ic_launcher_ring);
        header.addView(logo, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView name = Ui.text(activity, "PenDock", 16, colors.text, true);
        name.setPadding(dp(8), 0, 0, 0);
        header.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView lock = new ImageView(activity);
        lock.setImageResource(R.drawable.ic_lock);
        lock.setColorFilter(colors.accent);
        lock.setContentDescription(activity.getString(R.string.recents_locked));
        header.addView(lock, new LinearLayout.LayoutParams(dp(21), dp(21)));
        tile.addView(header, new LinearLayout.LayoutParams(-1, -2));

        TextView gesture = Ui.text(activity, "↓", 38, colors.accent, true);
        gesture.setGravity(Gravity.CENTER);
        gesture.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.addView(gesture, new LinearLayout.LayoutParams(-1, dp(70)));
        TextView hint = Ui.text(activity, activity.getString(R.string.recents_swipe_down), 14, colors.text, false);
        hint.setGravity(Gravity.CENTER);
        tile.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(-1, -2);
        tileParams.setMargins(dp(26), dp(14), dp(26), dp(12));
        scene.addView(tile, tileParams);
        content.addView(scene, new LinearLayout.LayoutParams(-1, -2));
        return content;
    }

    private void show(int titleRes, LinearLayout content) {
        FrameLayout illustration = new FrameLayout(activity);
        illustration.addView(content, new FrameLayout.LayoutParams(-1, -2));
        int watermarkSize = Math.min(54, activity.getResources().getConfiguration().screenWidthDp / 7);
        TextView watermark = Ui.text(activity, activity.getString(R.string.launch_example_watermark),
                watermarkSize, colors.accent, false);
        watermark.setTypeface(watermark.getTypeface(), android.graphics.Typeface.BOLD);
        watermark.setGravity(Gravity.CENTER);
        watermark.setSingleLine(true);
        watermark.setLetterSpacing(.08f);
        watermark.setRotation(-16);
        watermark.setAlpha(.22f);
        watermark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        illustration.addView(watermark, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout panel = Ui.column(activity);
        panel.setBackground(Ui.border(activity, colors.background, 22, colors.border));
        panel.setClipToOutline(true);
        TextView title = Ui.text(activity, activity.getString(titleRes), 20, colors.text, true);
        title.setAccessibilityHeading(true);
        title.setPadding(dp(20), dp(18), dp(20), 0);
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(illustration, new ScrollView.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2, 1));
        LinearLayout.LayoutParams close = new LinearLayout.LayoutParams(-2, -2);
        close.gravity = Gravity.END;
        close.setMargins(dp(12), 0, dp(16), dp(12));
        panel.addView(Ui.action(activity, activity.getString(R.string.close), false, dialog::dismiss), close);
        dialog.setContentView(panel);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.show();
        int width = Math.min(dp(540), activity.getResources().getDisplayMetrics().widthPixels - dp(28));
        dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout card(int fill) {
        LinearLayout card = Ui.column(activity);
        card.setPadding(dp(12), dp(12), dp(12), dp(8));
        card.setBackground(Ui.border(activity, fill, 16, colors.border));
        return card;
    }

    private LinearLayout toggleRow(int label, boolean on) {
        LinearLayout row = new LinearLayout(activity);
        row.setMinimumHeight(dp(44));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), 0, dp(8), 0);
        TextView text = Ui.text(activity, activity.getString(label), 14, colors.text, false);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));

        String state = activity.getString(on ? R.string.launch_example_on : R.string.launch_example_off);
        TextView status = Ui.text(activity, state, 12, colors.muted, false);
        status.setPadding(dp(6), 0, dp(7), 0);
        row.addView(status);

        FrameLayout track = new FrameLayout(activity);
        track.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        track.setBackground(Ui.shape(activity, on ? colors.accent : colors.pick(0xff8290a9, 0xff56617a), 12));
        View thumb = new View(activity);
        thumb.setBackground(Ui.shape(activity, 0xffffffff, 9));
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(dp(16), dp(16),
                Gravity.CENTER_VERTICAL | (on ? Gravity.END : Gravity.START));
        position.setMarginStart(dp(3));
        position.setMarginEnd(dp(3));
        track.addView(thumb, position);
        row.addView(track, new LinearLayout.LayoutParams(dp(36), dp(22)));
        return row;
    }

    private int dp(int value) { return Ui.dp(activity, value); }
}
