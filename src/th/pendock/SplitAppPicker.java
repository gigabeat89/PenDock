package th.pendock;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

final class SplitAppPicker extends FrameLayout {
    private final Appearance.Palette colors;
    private final Runnable cancel;
    private boolean detached;

    SplitAppPicker(Context context, Shortcuts shortcuts, List<Shortcuts.Entry> pinnedEntries,
            List<String> recentPackages, Set<String> excludedPackages, Set<String> floatingPackages,
            ExecutorService background, int seamGuard, boolean landscape,
            Runnable onClose, Runnable cancel,
            Consumer<Shortcuts.Entry> onPick,
            Consumer<Shortcuts.Entry> onFloatingPick) {
        super(context);
        this.colors = Ui.colors(context);
        this.cancel = cancel;
        setFocusableInTouchMode(true);
        setDescendantFocusability(FOCUS_BEFORE_DESCENDANTS);
        setClickable(true);
        setSoundEffectsEnabled(false);

        int outerMargin = dp(10);
        LinearLayout panel = Ui.column(context);
        int padding = dp(16);
        panel.setPadding(padding, padding, padding, padding);
        panel.setBackground(Ui.border(context, colors.background, 22, colors.border));
        panel.setElevation(dp(8));

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(context, context.getString(R.string.split_pick_title), 18, colors.text, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView closeButton = Ui.action(context, context.getString(R.string.close), false, onClose);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-2, -2);
        closeParams.setMarginStart(dp(8));
        header.addView(closeButton, closeParams);
        panel.addView(header, new LinearLayout.LayoutParams(-1, -2));

        List<Shortcuts.Entry> quickApps = new ArrayList<>();
        for (Shortcuts.Entry entry : pinnedEntries) {
            if (entry.tool == null && !excludedPackages.contains(entry.id)) {
                quickApps.add(entry);
            }
        }
        if (!quickApps.isEmpty()) {
            panel.addView(buildStripSection(context, R.string.split_pinned, quickApps, onPick),
                    new LinearLayout.LayoutParams(-1, -2));
        }

        LinearLayout recentSlot = Ui.column(context);
        recentSlot.setVisibility(GONE);
        panel.addView(recentSlot, new LinearLayout.LayoutParams(-1, -2));

        if (!recentPackages.isEmpty()) {
            List<Shortcuts.Entry> fastRecent = new ArrayList<>();
            boolean allFast = true;
            for (String pkg : recentPackages) {
                if (excludedPackages.contains(pkg) || pkg.equals(context.getPackageName())) continue;
                Shortcuts.Entry cached = shortcuts.cachedDescribe(pkg);
                if (cached == null) {
                    allFast = false;
                    break;
                }
                fastRecent.add(cached);
            }
            if (allFast) {
                if (!fastRecent.isEmpty()) {
                    recentSlot.addView(buildStripSection(context, R.string.split_recent, fastRecent, onPick),
                            new LinearLayout.LayoutParams(-1, -2));
                    recentSlot.setVisibility(VISIBLE);
                }
            } else if (!background.isShutdown()) {
                background.execute(() -> {
                    List<Shortcuts.Entry> recentEntries = new ArrayList<>();
                    for (String pkg : recentPackages) {
                        if (excludedPackages.contains(pkg) || pkg.equals(context.getPackageName())) continue;
                        if (context.getPackageManager().getLaunchIntentForPackage(pkg) == null) continue;
                        recentEntries.add(shortcuts.describe(pkg));
                    }
                    if (recentEntries.isEmpty()) return;
                    post(() -> {
                        if (detached) return;
                        recentSlot.removeAllViews();
                        recentSlot.addView(buildStripSection(context, R.string.split_recent, recentEntries, onPick),
                                new LinearLayout.LayoutParams(-1, -2));
                        recentSlot.setVisibility(VISIBLE);
                    });
                });
            }
        }

        EditText search = new EditText(context);
        search.setSingleLine(true);
        search.setHint(context.getString(R.string.search_apps));
        search.setHintTextColor(colors.muted);
        search.setTextColor(colors.text);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(48));
        searchParams.topMargin = dp(10);
        panel.addView(search, searchParams);

        ArrayAdapter<Shortcuts.Entry> adapter = new ArrayAdapter<Shortcuts.Entry>(context, 0) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                Shortcuts.Entry item = getItem(position);
                LinearLayout row = recycled instanceof LinearLayout ? (LinearLayout) recycled : buildRow(context);
                ImageView iconView = (ImageView) row.getChildAt(0);
                TextView labelView = (TextView) row.getChildAt(1);
                labelView.setText(item.label);
                iconView.setTag(item.id);
                Drawable ready = item.icon != null ? item.icon : shortcuts.cachedIcon(item.id);
                iconView.setImageDrawable(ready);
                if (ready == null && !background.isShutdown()) {
                    background.execute(() -> {
                        Drawable loaded = shortcuts.loadIcon(item.id);
                        post(() -> {
                            if (!detached && item.id.equals(iconView.getTag())) {
                                iconView.setImageDrawable(loaded);
                            }
                        });
                    });
                }
                return row;
            }
        };

        ListView results = new ListView(context);
        results.setDividerHeight(dp(1));
        results.setAdapter(adapter);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(-1, 0, 1);
        listParams.topMargin = dp(4);
        panel.addView(results, listParams);

        TextView empty = Ui.text(context, "", 14, colors.muted, false);
        empty.setGravity(Gravity.CENTER);
        panel.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1));
        results.setEmptyView(empty);

        if (floatingPackages != null && !floatingPackages.isEmpty() && onFloatingPick != null) {
            LinearLayout floatingSlot = Ui.column(context);
            floatingSlot.setVisibility(GONE);
            panel.addView(floatingSlot, new LinearLayout.LayoutParams(-1, -2));

            List<String> floatPkgs = new ArrayList<>();
            for (String pkg : floatingPackages) {
                if (pkg != null && !pkg.isEmpty() && !pkg.equals(context.getPackageName())) {
                    floatPkgs.add(pkg);
                }
            }
            if (!floatPkgs.isEmpty()) {
                List<Shortcuts.Entry> fastFloat = new ArrayList<>();
                boolean allFastFloat = true;
                for (String pkg : floatPkgs) {
                    Shortcuts.Entry cached = shortcuts.cachedDescribe(pkg);
                    if (cached == null) {
                        allFastFloat = false;
                        break;
                    }
                    fastFloat.add(cached);
                }
                if (allFastFloat && !fastFloat.isEmpty()) {
                    populateFloatingFooter(context, floatingSlot, fastFloat, onFloatingPick);
                } else if (!background.isShutdown()) {
                    background.execute(() -> {
                        List<Shortcuts.Entry> loadedFloat = new ArrayList<>();
                        for (String pkg : floatPkgs) {
                            if (context.getPackageManager().getLaunchIntentForPackage(pkg) == null) continue;
                            loadedFloat.add(shortcuts.describe(pkg));
                        }
                        if (loadedFloat.isEmpty()) return;
                        post(() -> {
                            if (detached) return;
                            populateFloatingFooter(context, floatingSlot, loadedFloat, onFloatingPick);
                        });
                    });
                }
            }
        }

        List<Shortcuts.Entry> cachedAll = shortcuts.cachedAvailableExcept(excludedPackages);
        List<Shortcuts.Entry> allApps = cachedAll != null ? new ArrayList<>(cachedAll) : new ArrayList<>();
        boolean[] loading = {cachedAll == null};
        Runnable refresh = () -> {
            String term = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            adapter.setNotifyOnChange(false);
            adapter.clear();
            if (!loading[0]) {
                for (Shortcuts.Entry app : allApps) {
                    if (app.label.toLowerCase(Locale.ROOT).contains(term)
                            || app.id.toLowerCase(Locale.ROOT).contains(term)) {
                        adapter.add(app);
                    }
                }
            }
            empty.setText(loading[0] ? "" : context.getString(R.string.no_apps));
            adapter.notifyDataSetChanged();
        };

        results.setOnItemClickListener((parent, view, position, id) -> {
            Shortcuts.Entry item = adapter.getItem(position);
            if (item != null) onPick.accept(item);
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { refresh.run(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        refresh.run();

        if (loading[0] && !background.isShutdown()) {
            Set<String> excluded = new HashSet<>(excludedPackages);
            background.execute(() -> {
                List<Shortcuts.Entry> loaded = shortcuts.availableExcept(excluded);
                post(() -> {
                    if (detached) return;
                    allApps.clear();
                    allApps.addAll(loaded);
                    loading[0] = false;
                    refresh.run();
                });
            });
        }

        LayoutParams cardParams = new LayoutParams(-1, -1);
        cardParams.setMargins(
                landscape ? outerMargin + seamGuard : outerMargin,
                landscape ? outerMargin : outerMargin + seamGuard,
                outerMargin,
                outerMargin);
        addView(panel, cardParams);
    }

    private void populateFloatingFooter(Context context, LinearLayout slot,
            List<Shortcuts.Entry> apps, Consumer<Shortcuts.Entry> onFloatingPick) {
        slot.removeAllViews();
        View divider = new View(context);
        divider.setBackgroundColor(colors.border);
        LinearLayout.LayoutParams divP = new LinearLayout.LayoutParams(-1, dp(1));
        divP.topMargin = dp(8);
        slot.addView(divider, divP);

        TextView heading = Ui.text(context, context.getString(R.string.split_floating_now), 11, colors.muted, true);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = dp(8);
        slot.addView(heading, hp);

        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < apps.size(); i++) {
            Shortcuts.Entry entry = apps.get(i);
            LinearLayout chip = new LinearLayout(context);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(10), dp(6), dp(12), dp(6));
            chip.setBackground(Ui.pressable(context, colors.accentWash, 12, colors.accent));
            chip.setFocusable(true);
            chip.setOnClickListener(v -> onFloatingPick.accept(entry));

            ImageView icon = new ImageView(context);
            icon.setImageDrawable(entry.icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            chip.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));

            TextView label = Ui.text(context, entry.label, 12, colors.accent, true);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginStart(dp(6));
            chip.addView(label, lp);

            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) cp.setMarginStart(dp(8));
            row.addView(chip, cp);
        }
        scroll.addView(row, new LayoutParams(-2, -2));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(6);
        slot.addView(scroll, sp);
        slot.setVisibility(VISIBLE);
    }

    private LinearLayout buildStripSection(Context context, int headingRes,
            List<Shortcuts.Entry> apps, Consumer<Shortcuts.Entry> onPick) {
        LinearLayout section = Ui.column(context);
        TextView heading = Ui.text(context, context.getString(headingRes), 12, colors.muted, true);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(-1, -2);
        headingParams.topMargin = dp(10);
        section.addView(heading, headingParams);

        HorizontalScrollView stripScroll = new HorizontalScrollView(context);
        stripScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = new LinearLayout(context);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < apps.size(); i++) {
            Shortcuts.Entry entry = apps.get(i);
            LinearLayout tile = Ui.column(context);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            tile.setPadding(dp(10), dp(8), dp(10), dp(8));
            tile.setBackground(Ui.pressable(context, colors.surface, 14, colors.border));
            tile.setFocusable(true);
            tile.setOnClickListener(view -> onPick.accept(entry));

            ImageView icon = new ImageView(context);
            icon.setImageDrawable(entry.icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            tile.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));

            TextView name = Ui.text(context, entry.label, 11, colors.text, false);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(dp(64), -2);
            nameParams.topMargin = dp(4);
            tile.addView(name, nameParams);

            LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) tileParams.setMarginStart(dp(8));
            strip.addView(tile, tileParams);
        }
        stripScroll.addView(strip);
        LinearLayout.LayoutParams stripParams = new LinearLayout.LayoutParams(-1, -2);
        stripParams.topMargin = dp(6);
        section.addView(stripScroll, stripParams);
        return section;
    }

    private LinearLayout buildRow(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(50));
        int pad = dp(8);
        row.setPadding(pad, dp(4), pad, dp(4));
        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        row.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView label = Ui.text(context, "", 15, colors.text, false);
        label.setPadding(dp(10), 0, dp(6), 0);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    @Override protected void onDetachedFromWindow() {
        detached = true;
        super.onDetachedFromWindow();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) cancel.run();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private int dp(float value) { return Ui.dp(getContext(), value); }
}
