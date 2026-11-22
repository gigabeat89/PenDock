package th.pendock;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public abstract class BatteryWidget extends AppWidgetProvider {
    private static final ExecutorService updates = Executors.newSingleThreadExecutor();

    private enum Card {
        COMPACT(R.layout.battery_widget),
        WIDE(R.layout.battery_widget_wide),
        TALL(R.layout.battery_widget_tall),
        SQUARE(R.layout.battery_widget_square);

        final int layout;

        Card(int layout) { this.layout = layout; }

        static Card forBounds(double width, double height) {
            if (width >= 96 && width >= height * 1.45) return WIDE;
            if (height >= 96 && height >= width * 1.45) return TALL;
            return width >= 96 && height >= 96 ? SQUARE : COMPACT;
        }
    }

    public static final class Resizable extends BatteryWidget { }

    private BatteryWidget() { }

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
                update(context, manager, ids);
            } finally {
                if (result != null) result.finish();
            }
        });
    }

    static void refreshAll(Context context) {
        updates.execute(() -> {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            if (manager == null) return;
            int[] ids = manager.getAppWidgetIds(new ComponentName(context, Resizable.class));
            update(context, manager, ids);
        });
    }

    private static void update(Context context, AppWidgetManager manager, int[] ids) {
        if (ids.length == 0) return;
        PenBattery battery = PenBattery.read(context);
        boolean dark = Appearance.isDark(Appearance.localize(context));
        Map<Card, RemoteViews> cards = new EnumMap<>(Card.class);
        for (int id : ids) {
            Bundle options = manager.getAppWidgetOptions(id);
            RemoteViews views = resizedCard(context, options, cards, battery, dark);
            manager.updateAppWidget(id, views);
        }
    }

    private static RemoteViews resizedCard(Context context, Bundle options, Map<Card, RemoteViews> cards,
            PenBattery battery, boolean dark) {
        if (Build.VERSION.SDK_INT >= 31) {
            List<SizeF> sizes = options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES);
            if (sizes != null && !sizes.isEmpty() && sizes.size() <= 16) {
                Map<SizeF, RemoteViews> layouts = new LinkedHashMap<>();
                for (SizeF size : sizes) {
                    if (size == null || size.getWidth() <= 0 || size.getHeight() <= 0) continue;
                    Card shape = Card.forBounds(size.getWidth(), size.getHeight());
                    layouts.put(size, cards.computeIfAbsent(shape, key -> card(context, key, battery, dark)));
                }
                if (!layouts.isEmpty()) return new RemoteViews(layouts);
            }
        }
        // Huawei Launcher can omit the exact sizes and report different dp for each orientation.
        // Combine both reported sizes so a single cell stays compact in either orientation.
        double width = typicalDimension(options, AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
                AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH);
        double height = typicalDimension(options, AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
                AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);
        Card shape = width > 0 && height > 0 ? Card.forBounds(width, height) : Card.COMPACT;
        return cards.computeIfAbsent(shape, key -> card(context, key, battery, dark));
    }

    private static double typicalDimension(Bundle options, String minKey, String maxKey) {
        int min = options.getInt(minKey);
        int max = options.getInt(maxKey);
        return min > 0 && max > 0 ? Math.sqrt((double) min * max) : Math.max(min, max);
    }

    private static RemoteViews card(Context context, Card shape, PenBattery battery, boolean dark) {
        RemoteViews views = new RemoteViews(context.getPackageName(), shape.layout);
        views.setInt(R.id.battery_widget_card, "setBackgroundResource",
                dark ? R.drawable.widget_card_dark : R.drawable.widget_card_light);
        BatteryWidgetArt art = new BatteryWidgetArt(battery, dark);
        boolean available = battery.available();
        views.setTextViewText(R.id.battery_caption, available ? battery.model : "Unavailable");
        views.setTextColor(R.id.battery_caption, art.caption);
        if (shape == Card.COMPACT) {
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
            views.setImageViewBitmap(R.id.battery_pen, BatteryWidgetArt.pen(context, shape == Card.TALL));
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
