package th.pendock;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Point;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.animation.DecelerateInterpolator;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

public class MainActivity extends Activity {
    private LinearLayout headerActions, content, previewPanel, exclusionsSection, permissionsSection;
    private FrameLayout previewScene;
    private boolean dualPane;
    private String appliedTheme, appliedLanguage;
    private ScrollView scroll;
    private Shortcuts shortcuts;
    private ExcludedApps exclusions;
    private Appearance.Palette colors;
    private boolean menuStatus;
    private LaserPointer laser;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(Appearance.localize(base));
    }

    @Override public void onCreate(Bundle state) {
        setTheme(Appearance.isDark(this) ? R.style.AppThemeDark : R.style.AppTheme);
        super.onCreate(state);
        appliedTheme = Appearance.theme(this);
        appliedLanguage = Appearance.language(this);
        colors = Ui.colors(this);
        shortcuts = new Shortcuts(this);
        exclusions = new ExcludedApps(this);
        getWindow().setStatusBarColor(colors.background);
        getWindow().setNavigationBarColor(colors.background);
        int width = getResources().getConfiguration().screenWidthDp;
        dualPane = width >= 820;
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(colors.background);
        FrameLayout shell = new FrameLayout(this);
        scroll.addView(shell, new ScrollView.LayoutParams(-1, -2));

        LinearLayout board = Ui.column(this);
        int gutter = dp(dualPane ? 24 : 12);
        int available = dp(width);
        int boardWidth = Math.min(available - gutter * 2, dp(1360));
        int inset = dp(dualPane ? 32 : 16);
        board.setBackground(Ui.border(this, colors.rail, 26, colors.border));
        board.setPadding(inset, dp(20), inset, dp(28));
        FrameLayout.LayoutParams boardParams = new FrameLayout.LayoutParams(boardWidth, -2,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        boardParams.topMargin = dp(16);
        boardParams.bottomMargin = dp(16);
        shell.addView(board, boardParams);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(56));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher_ring);
        header.addView(logo, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView brand = Ui.text(this, "PenDock", 23, colors.text, true);
        brand.setPadding(dp(10), 0, dp(8), 0);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        headerActions = new LinearLayout(this);
        header.addView(headerActions, new LinearLayout.LayoutParams(-2, -2));
        board.addView(header, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(dualPane ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        addSection(board, body, 24);
        LinearLayout leftColumn = body;
        LinearLayout rightColumn = body;
        if (dualPane) {
            leftColumn = Ui.column(this);
            rightColumn = Ui.column(this);
            body.addView(leftColumn, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1);
            right.setMarginStart(dp(20));
            body.addView(rightColumn, right);
        }
        content = pane();
        addSection(leftColumn, content, 0);
        previewPanel = pane();
        addSection(rightColumn, previewPanel, dualPane ? 0 : 16);

        exclusionsSection = Ui.column(this);
        addSection(leftColumn, exclusionsSection, 18);
        permissionsSection = Ui.column(this);
        addSection(rightColumn, permissionsSection, 18);

        FrameLayout root = new FrameLayout(this) {
            @Override public boolean dispatchHoverEvent(android.view.MotionEvent event) {
                laser.update(event);
                return super.dispatchHoverEvent(event);
            }
            @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
                laser.hide();
                return super.dispatchTouchEvent(event);
            }
        };
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        laser = new LaserPointer(root);
        setContentView(root);
    }

    @Override public void onResume() {
        super.onResume();
        if (!appliedTheme.equals(Appearance.theme(this)) || !appliedLanguage.equals(Appearance.language(this))) {
            recreate();
            return;
        }
        render();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused && laser != null) laser.hide();
        if (focused && content != null && menuStatus != DockService.running) render();
    }

    private void render() {
        content.removeAllViews();
        exclusionsSection.removeAllViews();
        permissionsSection.removeAllViews();
        menuStatus = DockService.running;
        renderHeader();
        renderMenuSettings();
        renderPreview();
        addSection(exclusionsSection, renderExclusions(), 0);
        addSection(permissionsSection, renderPermissions(), 0);
    }

    private void renderHeader() {
        headerActions.removeAllViews();
        int dockAction = menuStatus ? R.string.dock_off : R.string.dock_on;
        ImageButton dock = Ui.iconAction(this, R.drawable.ic_power, dockAction, this::toggleDock);
        dock.setTooltipText(getString(dockAction));
        dock.setImageTintList(ColorStateList.valueOf(dockStatusStroke()));
        dock.setBackground(Ui.pressable(this, dockStatusFill(), 12, dockStatusStroke()));
        headerActions.addView(dock, new LinearLayout.LayoutParams(dp(60), dp(48)));
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        settingsParams.setMarginStart(dp(8));
        headerActions.addView(Ui.iconAction(this, R.drawable.ic_settings, R.string.settings,
                () -> startActivity(new Intent(this, SettingsActivity.class))), settingsParams);
    }

    private int dockStatusStroke() {
        return menuStatus ? colors.pick(0xff167245, 0xff8ce4ab) : colors.pick(0xffad293c, 0xffff9eaa);
    }

    private int dockStatusFill() {
        return menuStatus ? colors.pick(0xffeaf6ee, 0xff20392a) : colors.pick(0xfffff0f1, 0xff40272e);
    }

    private void renderMenuSettings() {
        List<Shortcuts.Entry> selected = shortcuts.selected();
        Collections.reverse(selected);
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setMinimumHeight(dp(48));
        LinearLayout title = new LinearLayout(this);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.addView(paneTitle(R.string.pinned_apps), new LinearLayout.LayoutParams(-2, -2, 1));
        TextView count = Ui.text(this, getString(R.string.ring_item_count, selected.size(), Shortcuts.LIMIT), 13, colors.muted, false);
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(-2, -2);
        countParams.setMarginStart(dp(8));
        title.addView(count, countParams);
        FrameLayout headingSpace = new FrameLayout(this);
        headingSpace.addView(title, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1);
        titleParams.setMarginEnd(dp(10));
        heading.addView(headingSpace, titleParams);
        heading.addView(Ui.action(this, getString(R.string.add_app), false, this::pickApp));
        add(heading, 0);
        if (selected.isEmpty()) {
            add(Ui.text(this, getString(R.string.empty_ring), 14, colors.muted, false), 12);
        } else {
            if (selected.size() > 1) {
                add(Ui.text(this, getString(R.string.drag_hint), 13, colors.muted, false), 7);
            }
            add(shortcutList(selected), 10);
        }

        add(Ui.text(this, getString(R.string.pen_controls), 16, colors.text, true), 19);
        LinearLayout pen = card();
        LinearLayout penChoice = Ui.column(this);
        Switch penOnly = featureSwitch(R.string.pen_only, PenInput.isPenOnlyMenu(this));
        penChoice.addView(penOnly, new LinearLayout.LayoutParams(-1, -2));
        TextView inputHint = Ui.text(this, getString(R.string.pen_input_hint), 12, colors.muted, false);
        inputHint.setPadding(0, 0, 0, dp(8));
        inputHint.setVisibility(penOnly.isChecked() ? View.GONE : View.VISIBLE);
        penChoice.addView(inputHint);
        penOnly.setOnCheckedChangeListener((button, enabled) -> {
            PenInput.setPenOnlyMenu(this, enabled);
            inputHint.setVisibility(enabled ? View.GONE : View.VISIBLE);
        });
        addRow(pen, penChoice);
        Switch proximity = featureSwitch(R.string.pen_near_only, PenInput.requiresNearScreen(this));
        proximity.setOnCheckedChangeListener((button, enabled) -> PenInput.setRequiresNearScreen(this, enabled));
        addRow(pen, proximity);
        Switch laserToggle = featureSwitch(R.string.laser, LaserPointer.isEnabled(this));
        laserToggle.setOnCheckedChangeListener((button, enabled) -> {
            LaserPointer.setEnabled(this, enabled);
            laser.setEnabled(enabled);
            refreshPreview(shortcuts.selected());
        });
        addRow(pen, laserToggle);
        add(pen, 8);
    }

    private LinearLayout renderPermissions() {
        boolean overlay = Settings.canDrawOverlays(this);
        boolean screenshot = SystemScreenshot.isReady();
        boolean window = WindowModeMonitor.isReady();
        boolean circle = Build.VERSION.SDK_INT >= 30 && LensShare.isAvailable(this);
        boolean circleReady = circle && CircleCaptureService.isReady();
        int granted = (overlay ? 1 : 0) + (screenshot ? 1 : 0) + (window ? 1 : 0) + (circleReady ? 1 : 0);
        LinearLayout system = pane();
        addSectionHeading(system, R.string.permissions,
                getString(R.string.granted_count, granted, circle ? 4 : 3));
        addSection(system, Ui.text(this, getString(R.string.permission_intro), 13, colors.muted, false), 8);
        addSection(system, permissionRow(R.string.overlay_title, R.string.overlay_detail,
                overlay, this::openOverlaySettings), 8);
        addSection(system, permissionRow(R.string.system_actions_heading, R.string.screenshot_detail,
                screenshot, this::openAccessibility), 5);
        addSection(system, permissionRow(R.string.window_title, R.string.window_detail,
                window, this::openAccessibility), 5);
        if (circle) {
            addSection(system, permissionRow(R.string.circle_search, R.string.circle_detail,
                    circleReady, this::openAccessibility), 5);
        }
        if ("huawei".equalsIgnoreCase(Build.MANUFACTURER)) addHuaweiBackgroundHelp(system);
        if (new java.io.File(getFilesDir(), "draft.png").isFile()) {
            addSection(system, Ui.action(this, getString(R.string.draft), false,
                    () -> startActivity(new Intent(this, EditorActivity.class))), 8);
        }
        return system;
    }

    private void addHuaweiBackgroundHelp(LinearLayout options) {
        View divider = new View(this);
        divider.setBackgroundColor(colors.border);
        LinearLayout.LayoutParams separator = new LinearLayout.LayoutParams(-1, dp(1));
        separator.topMargin = dp(12);
        options.addView(divider, separator);
        addSection(options, huaweiInfoRow(R.string.huawei_background_title, R.string.huawei_background_example,
                () -> HuaweiSetupExamples.showLaunchManager(this), true), 6);
        addSection(options, Ui.text(this, getString(R.string.huawei_background_steps), 13, colors.muted, false), 3);
        addSection(options, Ui.action(this, getString(R.string.huawei_open_settings), false,
                this::openHuaweiBatterySettings), 8);
        addSection(options, huaweiInfoRow(R.string.huawei_recents_tip, R.string.huawei_recents_example,
                () -> HuaweiSetupExamples.showRecentTasks(this), false), 6);
    }

    private View huaweiInfoRow(int caption, int description, Runnable show, boolean heading) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(Ui.text(this, getString(caption), heading ? 14 : 12,
                        heading ? colors.text : colors.muted, heading),
                new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton info = Ui.iconAction(this, R.drawable.ic_info, description, show);
        info.setImageTintList(ColorStateList.valueOf(colors.accent));
        info.setBackground(Ui.pressable(this, Color.TRANSPARENT, 9, Color.TRANSPARENT));
        row.addView(info, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return row;
    }

    private void openHuaweiBatterySettings() {
        try {
            startActivity(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY));
        } catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private View permissionRow(int title, int detail, boolean granted, Runnable openSettings) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(51));
        LinearLayout labels = Ui.column(this);
        labels.addView(Ui.text(this, getString(title), 14, colors.text, true));
        labels.addView(Ui.text(this, getString(detail), 12, colors.muted, false));
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
        labelParams.setMarginEnd(dp(8));
        row.addView(labels, labelParams);
        if (granted) {
            int green = colors.pick(0xff1b624e, 0xffa0e1c8);
            TextView ready = Ui.text(this, "✓  " + getString(R.string.permission_granted), 12, green, true);
            ready.setPadding(dp(9), dp(5), dp(9), dp(5));
            ready.setBackground(Ui.shape(this, colors.pick(0xffe2f1e9, 0xff23443e), 9));
            row.addView(ready);
        } else {
            TextView grant = Ui.action(this, getString(R.string.permission_grant), true, openSettings);
            grant.setContentDescription(getString(R.string.permission_grant_named, getString(title)));
            row.addView(grant, new LinearLayout.LayoutParams(-2, -2));
        }
        return row;
    }

    private void addSectionHeading(LinearLayout panel, int title, String summary) {
        addSection(panel, paneTitle(title), 0);
        addSection(panel, Ui.text(this, summary, 12, colors.muted, false), 4);
    }

    private TextView removeAction(int label, int description, String appLabel, Runnable onClick) {
        TextView remove = Ui.action(this, getString(label), false, onClick);
        remove.setPadding(dp(8), 0, dp(8), 0);
        remove.setBackground(Ui.pressable(this, Color.TRANSPARENT, 9, Color.TRANSPARENT));
        remove.setContentDescription(getString(description, appLabel));
        return remove;
    }

    private LinearLayout shortcutList(List<Shortcuts.Entry> selected) {
        LinearLayout list = card();
        List<View> rows = new ArrayList<>();
        for (int index = 0; index < selected.size(); index++) {
            Shortcuts.Entry app = selected.get(index);
            LinearLayout row = appRow(app);
            row.setPadding(0, 0, 0, 0);
            TextView remove = removeAction(R.string.remove, R.string.remove_named, app.label, () -> {
                shortcuts.remove(app.id);
                render();
            });
            row.addView(remove);
            if (selected.size() > 1) {
                int source = index;
                ImageButton grip = new ImageButton(this);
                grip.setImageResource(R.drawable.ic_drag_handle);
                grip.setImageTintList(ColorStateList.valueOf(colors.muted));
                grip.setBackground(Ui.pressable(this, Color.TRANSPARENT, 9, Color.TRANSPARENT));
                grip.setContentDescription(getString(R.string.drag_named, app.label));
                grip.setFocusable(true);
                grip.setOnKeyListener((view, key, event) -> {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                    int destination = source + (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                    if ((key != KeyEvent.KEYCODE_DPAD_UP && key != KeyEvent.KEYCODE_DPAD_DOWN)
                            || destination < 0 || destination >= selected.size()) return false;
                    if (shortcuts.moveTo(app.id, selected.size() - 1 - destination)) {
                        render();
                        View moved = content.findViewWithTag(app.id);
                        if (moved != null) moved.requestFocus();
                    }
                    return true;
                });
                grip.setTag(app.id);
                grip.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                    @Override public void onInitializeAccessibilityNodeInfo(View view,
                            AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(view, info);
                        if (source > 0) info.addAction(new AccessibilityAction(
                                AccessibilityAction.ACTION_SCROLL_UP.getId(), getString(R.string.move_up, app.label)));
                        if (source + 1 < selected.size()) info.addAction(new AccessibilityAction(
                                AccessibilityAction.ACTION_SCROLL_DOWN.getId(), getString(R.string.move_down, app.label)));
                    }

                    @Override public boolean performAccessibilityAction(View view, int action, Bundle args) {
                        int destination;
                        if (action == AccessibilityAction.ACTION_SCROLL_UP.getId()) destination = source - 1;
                        else if (action == AccessibilityAction.ACTION_SCROLL_DOWN.getId()) destination = source + 1;
                        else return super.performAccessibilityAction(view, action, args);
                        if (!shortcuts.moveTo(app.id, selected.size() - 1 - destination)) return false;
                        render();
                        return true;
                    }
                });
                row.addView(grip, new LinearLayout.LayoutParams(dp(48), dp(48)));
                enableShortcutDrag(row, grip, remove, app, source);
            }
            rows.add(row);
            addRow(list, row);
        }
        list.setOnDragListener((view, event) -> handleShortcutDrag(list, rows, selected, event));
        return list;
    }

    private void enableShortcutDrag(View row, View grip, View remove, Shortcuts.Entry app, int index) {
        ShortcutDrag drag = new ShortcutDrag(index);
        View.OnTouchListener trackPress = (view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                drag.grabOffset = event.getY() + (view == row ? 0 : view.getTop());
            }
            return false;
        };
        View.OnLongClickListener lift = view -> row.startDragAndDrop(
                ClipData.newPlainText("", app.id), new View.DragShadowBuilder() {
                    @Override public void onProvideShadowMetrics(Point size, Point touch) {
                        // Move the actual row so it stays aligned with the rest of the list.
                        size.set(1, 1);
                        touch.set(0, 0);
                    }

                    @Override public void onDrawShadow(Canvas canvas) { }
                }, drag, 0);
        for (View target : new View[] {row, grip, remove}) {
            target.setOnTouchListener(trackPress);
            target.setOnLongClickListener(lift);
        }
    }

    private static final class ShortcutDrag {
        final int source;
        float grabOffset;
        int destination;
        boolean committed;
        float viewportY;
        int scrollSpeed;
        Runnable scrollStep;

        ShortcutDrag(int source) {
            this.source = source;
            destination = source;
        }
    }

    private boolean handleShortcutDrag(LinearLayout list, List<View> rows, List<Shortcuts.Entry> selected,
            DragEvent event) {
        if (!(event.getLocalState() instanceof ShortcutDrag)) return false;
        ShortcutDrag drag = (ShortcutDrag) event.getLocalState();
        View source = rows.get(drag.source);
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                drag.committed = false;
                source.animate().cancel();
                source.setTranslationZ(dp(6));
                source.setBackground(Ui.border(this, colors.accentWash, 10, colors.accent));
                return true;
            case DragEvent.ACTION_DRAG_LOCATION:
                updateShortcutDrag(rows, selected, drag, event.getY());
                scheduleShortcutScroll(list, rows, selected, drag, event.getY());
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
                stopShortcutScroll(list, drag);
                return true;
            case DragEvent.ACTION_DROP:
                stopShortcutScroll(list, drag);
                updateShortcutDrag(rows, selected, drag, event.getY());
                drag.committed = shortcuts.moveTo(selected.get(drag.source).id, selected.size() - 1 - drag.destination);
                if (drag.committed) {
                    int finalPosition = rows.get(drag.destination).getTop() - source.getTop();
                    slideRow(source, finalPosition).withEndAction(() -> {
                        if (list.isAttachedToWindow()) render();
                    }).start();
                }
                return true;
            case DragEvent.ACTION_DRAG_ENDED:
                stopShortcutScroll(list, drag);
                if (!drag.committed) {
                    source.setTranslationZ(0);
                    source.setBackground(null);
                    moveDragPreview(rows, selected, drag, drag.source);
                    slideRow(source, 0).start();
                }
                return true;
            default:
                return true;
        }
    }

    private void scheduleShortcutScroll(LinearLayout list, List<View> rows, List<Shortcuts.Entry> selected,
            ShortcutDrag drag, float localY) {
        drag.viewportY = localY + offsetInScroll(list);
        int edge = dp(64);
        int speed = drag.viewportY < edge ? -dp(8)
                : drag.viewportY > scroll.getHeight() - edge ? dp(8) : 0;
        if (speed == 0 || !scroll.canScrollVertically(Integer.signum(speed))) {
            stopShortcutScroll(list, drag);
            return;
        }
        drag.scrollSpeed = speed;
        if (drag.scrollStep != null) return;
        drag.scrollStep = () -> {
            // Stop when the end of the shortcut list reaches the edge, leaving other settings alone.
            float y = drag.viewportY - offsetInScroll(list);
            if (!list.isAttachedToWindow() || y <= 0 || y >= list.getHeight()
                    || !scroll.canScrollVertically(Integer.signum(drag.scrollSpeed))) {
                stopShortcutScroll(list, drag);
                return;
            }
            scroll.scrollBy(0, drag.scrollSpeed);
            updateShortcutDrag(rows, selected, drag, drag.viewportY - offsetInScroll(list));
            list.postOnAnimation(drag.scrollStep);
        };
        list.postOnAnimation(drag.scrollStep);
    }

    private int offsetInScroll(View child) {
        int[] position = new int[2];
        child.getLocationOnScreen(position);
        int top = position[1];
        scroll.getLocationOnScreen(position);
        return top - position[1];
    }

    private void stopShortcutScroll(View list, ShortcutDrag drag) {
        if (drag.scrollStep == null) return;
        list.removeCallbacks(drag.scrollStep);
        drag.scrollStep = null;
    }

    private void updateShortcutDrag(List<View> rows, List<Shortcuts.Entry> selected, ShortcutDrag drag, float y) {
        View source = rows.get(drag.source);
        float top = Math.max(rows.get(0).getTop(),
                Math.min(rows.get(rows.size() - 1).getTop(), y - drag.grabOffset));
        source.setTranslationY(top - source.getTop());
        float center = top + source.getHeight() / 2f;
        int destination = 0;
        for (View row : rows) {
            if (row == source) continue;
            float spacing = source.getHeight() / 2f;
            float threshold = row.getTop() + row.getHeight() / 2f;
            threshold += row.getTop() < source.getTop() ? spacing : -spacing;
            if (center >= threshold) destination++;
        }
        moveDragPreview(rows, selected, drag, destination);
    }

    private void moveDragPreview(List<View> rows, List<Shortcuts.Entry> selected, ShortcutDrag drag, int destination) {
        if (destination == drag.destination) return;
        drag.destination = destination;
        for (int index = 0; index < rows.size(); index++) {
            if (index == drag.source) continue;
            int slot = index > drag.source && index <= destination ? index - 1
                    : index < drag.source && index >= destination ? index + 1 : index;
            View row = rows.get(index);
            slideRow(row, rows.get(slot).getTop() - row.getTop()).start();
        }
        List<Shortcuts.Entry> ordered = new ArrayList<>(selected);
        ordered.add(destination, ordered.remove(drag.source));
        Collections.reverse(ordered);
        refreshPreview(ordered);
    }

    private ViewPropertyAnimator slideRow(View row, int translation) {
        return row.animate().translationY(translation).setDuration(130)
                .setInterpolator(new DecelerateInterpolator());
    }

    private LinearLayout renderExclusions() {
        List<Shortcuts.Entry> excluded = new java.util.ArrayList<>();
        for (String pkg : exclusions.packages()) excluded.add(shortcuts.describe(pkg));
        java.text.Collator order = java.text.Collator.getInstance(getResources().getConfiguration().getLocales().get(0));
        excluded.sort((first, second) -> order.compare(first.label, second.label));
        LinearLayout panel = pane();
        addSectionHeading(panel, R.string.excluded_tab, getString(R.string.excluded_count, excluded.size()));
        addSection(panel, Ui.text(this, getString(R.string.excluded_intro), 13, colors.muted, false), 8);
        LinearLayout list = card();
        for (Shortcuts.Entry app : excluded) {
            LinearLayout row = appRow(app);
            TextView remove = removeAction(R.string.unexclude, R.string.unexclude_named, app.label, () -> {
                exclusions.remove(app.id);
                render();
            });
            row.addView(remove);
            addRow(list, row);
        }
        if (excluded.isEmpty()) addSection(panel, Ui.text(this, getString(R.string.empty_excluded), 14, colors.muted, false), 14);
        else addSection(panel, list, 12);
        addSection(panel, Ui.action(this, getString(R.string.add_excluded), false,
                () -> pickApp(getString(R.string.add_excluded), shortcuts.availableExcept(exclusions.packages()),
                        app -> exclusions.add(app.id), false)), 10);
        return panel;
    }

    private void renderPreview() {
        previewPanel.removeAllViews();
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(paneTitle(R.string.preview_ring), new LinearLayout.LayoutParams(-2, -2));
        TextView status = Ui.text(this, getString(menuStatus ? R.string.dock_running : R.string.dock_stopped),
                12, dockStatusStroke(), true);
        status.setPadding(dp(9), dp(4), dp(9), dp(4));
        status.setBackground(Ui.border(this, dockStatusFill(), 12, dockStatusStroke()));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-2, -2);
        statusParams.setMarginStart(dp(9));
        heading.addView(status, statusParams);
        addSection(previewPanel, heading, 0);
        previewScene = new FrameLayout(this);
        previewScene.setBackground(Ui.shape(this, colors.rail, 16));
        previewScene.setClipToOutline(true);
        previewScene.addView(new PreviewWallpaper(this, colors.dark), new FrameLayout.LayoutParams(-1, -1));
        int height = getResources().getConfiguration().screenHeightDp;
        int sceneHeight = dualPane ? Math.max(305, Math.min(438, height * 42 / 100)) : 305;
        LinearLayout.LayoutParams stage = new LinearLayout.LayoutParams(-1, dp(sceneHeight));
        stage.topMargin = dp(10);
        previewPanel.addView(previewScene, stage);
        refreshPreview(shortcuts.selected());
    }

    private void refreshPreview(List<Shortcuts.Entry> selected) {
        if (previewScene == null) return;
        if (previewScene.getChildCount() > 1) previewScene.removeViewAt(1);
        previewScene.addView(RadialMenu.preview(this, selected), new FrameLayout.LayoutParams(-1, -1));
    }

    private LinearLayout pane() {
        LinearLayout pane = Ui.column(this);
        int inset = dp(dualPane ? 22 : 14);
        pane.setPadding(inset, inset, inset, inset);
        pane.setBackground(Ui.border(this, colors.surface, 20, colors.border));
        return pane;
    }

    private TextView paneTitle(int title) {
        TextView label = Ui.text(this, getString(title), 20, colors.text, true);
        label.setAccessibilityHeading(true);
        return label;
    }

    private LinearLayout card() {
        LinearLayout card = Ui.column(this);
        card.setPadding(dp(12), 0, dp(12), 0);
        card.setBackground(Ui.border(this, colors.surface, 13, colors.border));
        return card;
    }

    private void addRow(LinearLayout card, View row) {
        if (card.getChildCount() > 0) {
            View divider = new View(this);
            divider.setBackgroundColor(colors.border);
            card.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        }
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void openAccessibility() { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
    private int dp(float value) { return Ui.dp(this, value); }

    private Switch featureSwitch(int title, boolean enabled) {
        Switch control = new Switch(this);
        control.setText(title);
        control.setTextColor(colors.text);
        control.setTextSize(14);
        control.setMinHeight(dp(50));
        control.setChecked(enabled);
        int[][] states = {{android.R.attr.state_checked}, {}};
        control.setThumbTintList(ColorStateList.valueOf(colors.pick(0xffffffff, 0xfff1f4ff)));
        control.setTrackTintList(new ColorStateList(states, new int[] {
                colors.pick(0xff4264eb, 0xff536fde), colors.pick(0xffd4dbea, 0xff56617a)
        }));
        return control;
    }

    private LinearLayout appRow(Shortcuts.Entry app) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(this, 54));
        int padding = Ui.dp(this, 10);
        row.setPadding(padding, 0, padding, 0);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        if (app.tool != null) icon.setImageTintList(ColorStateList.valueOf(colors.accent));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34)));
        TextView label = Ui.text(this, app.label, 16, colors.text, false);
        label.setPadding(padding, 0, padding, 0);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private void pickApp() {
        pickApp(getString(R.string.add_ring_app), shortcuts.available(), shortcuts::add, true);
    }

    private void pickApp(String title, List<Shortcuts.Entry> available, Predicate<Shortcuts.Entry> addApp,
            boolean ringPicker) {
        Dialog dialog = new Dialog(this);
        dialog.setTitle(title);
        LinearLayout panel = Ui.column(this);
        int padding = dp(16);
        panel.setPadding(padding, padding, padding, padding);
        panel.setBackground(Ui.shape(this, colors.background, 18));
        panel.addView(Ui.text(this, title, 20, colors.text, true));

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint(getString(R.string.search_apps));
        search.setHintTextColor(colors.muted);
        search.setTextColor(colors.text);
        panel.addView(search, new LinearLayout.LayoutParams(-1, dp(52)));

        ArrayAdapter<Shortcuts.Entry> adapter = new ArrayAdapter<Shortcuts.Entry>(this, 0) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                return appRow(getItem(position));
            }
        };
        ListView results = new ListView(this);
        TextView appStatus = Ui.text(this, "", 14, colors.muted, false);
        appStatus.setPadding(0, dp(8), 0, dp(8));
        List<CheckBox> toolOptions = new ArrayList<>();
        if (ringPicker) {
            results.addHeaderView(toolChoices(toolOptions), null, false);
            results.addHeaderView(paneTitle(R.string.ring_picker_apps), null, false);
            results.addFooterView(appStatus, null, false);
        }
        results.setAdapter(adapter);
        panel.addView(results, new LinearLayout.LayoutParams(-1, 0, 1));
        if (!ringPicker) {
            appStatus.setText(R.string.no_apps);
            appStatus.setGravity(Gravity.CENTER);
            panel.addView(appStatus, new LinearLayout.LayoutParams(-1, 0, 1));
            results.setEmptyView(appStatus);
        }
        panel.addView(Ui.action(this, getString(R.string.close), false, dialog::dismiss));

        Runnable refreshResults = () -> {
            String term = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            boolean full = ringPicker && shortcuts.selected().size() >= Shortcuts.LIMIT;
            adapter.setNotifyOnChange(false);
            adapter.clear();
            if (!full) {
                for (Shortcuts.Entry app : available) {
                    if (app.label.toLowerCase(Locale.ROOT).contains(term)
                            || app.id.toLowerCase(Locale.ROOT).contains(term)) {
                        adapter.add(app);
                    }
                }
            }
            if (ringPicker) {
                for (CheckBox choice : toolOptions) choice.setEnabled(choice.isChecked() || !full);
                appStatus.setText(full ? getString(R.string.ring_item_limit, Shortcuts.LIMIT)
                        : adapter.isEmpty() ? getString(R.string.no_apps) : "");
                appStatus.setVisibility(appStatus.length() > 0 ? View.VISIBLE : View.GONE);
            }
            adapter.notifyDataSetChanged();
        };
        for (CheckBox choice : toolOptions) {
            Shortcuts.Entry entry = (Shortcuts.Entry) choice.getTag();
            choice.setOnClickListener(button -> {
                if (choice.isChecked()) {
                    if (!shortcuts.add(entry)) choice.setChecked(false);
                } else {
                    shortcuts.remove(entry.id);
                }
                render();
                refreshResults.run();
            });
        }
        results.setOnItemClickListener((parent, view, position, id) -> {
            Object row = parent.getItemAtPosition(position);
            if (!(row instanceof Shortcuts.Entry)) return;
            Shortcuts.Entry choice = (Shortcuts.Entry) row;
            if (!addApp.test(choice)) return;
            render();
            if (ringPicker) {
                available.remove(choice);
                refreshResults.run();
            } else {
                dialog.dismiss();
            }
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                refreshResults.run();
            }
            @Override public void afterTextChanged(Editable text) { }
        });
        refreshResults.run();
        dialog.setContentView(panel);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.show();
        int height = getResources().getDisplayMetrics().heightPixels;
        dialog.getWindow().setLayout(Math.min(Ui.dp(this, 480),
                getResources().getDisplayMetrics().widthPixels - padding * 2),
                Math.min(Ui.dp(this, 560), height * 4 / 5));
    }

    private LinearLayout toolChoices(List<CheckBox> toolOptions) {
        LinearLayout tools = Ui.column(this);
        tools.setPadding(0, 0, 0, dp(12));
        tools.addView(paneTitle(R.string.ring_picker_tools));
        List<Shortcuts.Entry> selected = shortcuts.selected();
        for (Shortcuts.Tool tool : Shortcuts.Tool.values()) {
            Shortcuts.Entry entry = shortcuts.toolEntry(tool);
            CheckBox choice = new CheckBox(this);
            choice.setText(entry.label);
            choice.setTextColor(colors.text);
            choice.setMinHeight(dp(46));
            entry.icon.setTint(colors.accent);
            entry.icon.setBounds(0, 0, dp(22), dp(22));
            choice.setCompoundDrawablesRelative(null, null, entry.icon, null);
            choice.setChecked(selected.stream().anyMatch(item -> item.tool == tool));
            choice.setTag(entry);
            toolOptions.add(choice);
            tools.addView(choice, new LinearLayout.LayoutParams(-1, -2));
        }
        return tools;
    }

    private void add(View view, int top) {
        addSection(content, view, top);
    }

    private void addSection(LinearLayout parent, View view, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        parent.addView(view, params);
    }

    private void toggleDock() {
        Intent dock = new Intent(this, DockService.class);
        if (DockService.running) {
            stopService(dock);
        } else if (Settings.canDrawOverlays(this)) {
            startForegroundService(dock);
        } else {
            openOverlaySettings();
        }
        content.postDelayed(this::render, 250);
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.fromParts("package", getPackageName(), null)));
        } catch (android.content.ActivityNotFoundException unavailable) {
            Toast.makeText(this, R.string.overlay_missing, Toast.LENGTH_LONG).show();
        }
    }
}
