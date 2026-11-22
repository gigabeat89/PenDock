package th.pendock;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public abstract class BatteryWidget extends AppWidgetProvider {
    private static final ExecutorService updates = Executors.newSingleThreadExecutor();

    private enum Size {
        COMPACT(1, Compact.class), WIDE(1, Wide.class), EXTRA_WIDE(1, ExtraWide.class),
        TALL(2, Tall.class), EXTRA_TALL(3, ExtraTall.class);

        final int rows;
        final Class<? extends BatteryWidget> provider;

        Size(int rows, Class<? extends BatteryWidget> provider) {
            this.rows = rows;
            this.provider = provider;
        }
    }

    public static final class Compact extends BatteryWidget { public Compact() { super(Size.COMPACT); } }
    public static final class Wide extends BatteryWidget { public Wide() { super(Size.WIDE); } }
    public static final class ExtraWide extends BatteryWidget { public ExtraWide() { super(Size.EXTRA_WIDE); } }
    public static final class Tall extends BatteryWidget { public Tall() { super(Size.TALL); } }
    public static final class ExtraTall extends BatteryWidget { public ExtraTall() { super(Size.EXTRA_TALL); } }

    private final Size size;

    private BatteryWidget(Size size) { this.size = size; }

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        enqueue(context, manager, ids);
    }

    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
            int id, Bundle options) {
        enqueue(context, manager, new int[]{id});
    }

    private void enqueue(Context context, AppWidgetManager manager, int[] ids) {
        PendingResult result = goAsync();
        updates.execute(() -> {
            try {
                update(context, manager, size, ids, PenBattery.read(context));
            } finally {
                if (result != null) result.finish();
            }
        });
    }

    static void refreshAll(Context context) {
        updates.execute(() -> {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            if (manager == null) return;
            PenBattery battery = null;
            for (Size size : Size.values()) {
                int[] ids = manager.getAppWidgetIds(new ComponentName(context, size.provider));
                if (ids.length == 0) continue;
                if (battery == null) battery = PenBattery.read(context);
                update(context, manager, size, ids, battery);
            }
        });
    }

    private static void update(Context context, AppWidgetManager manager, Size size, int[] ids, PenBattery battery) {
        if (ids.length == 0) return;
        boolean dark = Appearance.isDark(Appearance.localize(context));
        RemoteViews views = card(context, size, battery, dark);
        manager.updateAppWidget(ids, views);
    }

    private static RemoteViews card(Context context, Size size, PenBattery battery, boolean dark) {
        boolean compact = size == Size.COMPACT;
        boolean tall = size.rows > 1;
        int layout = compact ? R.layout.battery_widget
                : tall ? R.layout.battery_widget_tall : R.layout.battery_widget_wide;
        RemoteViews views = new RemoteViews(context.getPackageName(), layout);
        views.setInt(R.id.battery_widget_card, "setBackgroundResource",
                dark ? R.drawable.widget_card_dark : R.drawable.widget_card_light);
        BatteryWidgetArt art = new BatteryWidgetArt(battery, dark);
        boolean available = battery.available();
        views.setTextViewText(R.id.battery_caption, available ? battery.model : "Unavailable");
        views.setTextColor(R.id.battery_caption, art.caption);
        if (compact) {
            views.setImageViewBitmap(R.id.battery_ring, art.ring(battery));
        } else {
            views.setViewVisibility(R.id.battery_caption, available ? View.VISIBLE : View.GONE);
            views.setViewVisibility(R.id.battery_status, available ? View.GONE : View.VISIBLE);
            views.setTextViewText(R.id.battery_status, "Unavailable");
            views.setTextColor(R.id.battery_status, art.caption);
            views.setTextViewText(R.id.battery_value, available ? battery.level + "%" : "—");
            views.setTextColor(R.id.battery_value, art.ink);
            views.setViewVisibility(R.id.battery_charging, battery.charging ? View.VISIBLE : View.GONE);
            if (battery.charging) views.setImageViewBitmap(R.id.battery_charging, art.bolt());
            views.setViewVisibility(R.id.battery_meter, available ? View.VISIBLE : View.GONE);
            if (available) views.setImageViewBitmap(R.id.battery_meter, art.meter(battery.level));
            views.setImageViewBitmap(R.id.battery_pen, BatteryWidgetArt.pen(context, tall));
        }
        String description = battery.available()
                ? battery.model + ", " + battery.level + " percent" + (battery.charging ? ", charging" : "")
                : "Pen battery unavailable";
        views.setContentDescription(R.id.battery_widget_card, description);
        Intent open = new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        views.setOnClickPendingIntent(R.id.battery_widget_card,
                PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE));
        return views;
    }
}
