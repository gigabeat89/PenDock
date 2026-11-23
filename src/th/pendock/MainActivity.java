package th.pendock;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
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
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class MainActivity extends Activity {
    private LinearLayout headerActions, content, previewPanel, exclusionsSection, permissionsSection;
    private FrameLayout previewScene;
    private PreviewWallpaper previewWallpaper;
    private RadialMenu previewMenu;
    private boolean dualPane;
    private String appliedTheme, appliedLanguage;
    private ScrollView scroll;
    private Shortcuts shortcuts;
    private ExcludedApps exclusions;
    private Appearance.Palette colors;
    private boolean menuStatus;
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final Runnable dockStateChanged = () -> {
        if (!isFinishing() && !isDestroyed() && content != null && menuStatus != DockService.running) render();
    };

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
        addSection(rightColumn, exclusionsSection, 18);
        permissionsSection = Ui.column(this);
        addSection(rightColumn, permissionsSection, 18);

        setContentView(scroll);
    }

    @Override protected void onStart() {
        super.onStart();
        DockService.addStateListener(dockStateChanged);
    }

    @Override public void onResume() {
        super.onResume();
        if (!appliedTheme.equals(Appearance.theme(this)) || !appliedLanguage.equals(Appearance.language(this))) {
            recreate();
            return;
        }
        render();
    }

    @Override protected void onStop() {
        DockService.removeStateListener(dockStateChanged);
        super.onStop();
    }

    @Override protected void onDestroy() {
        background.shutdownNow();
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
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
        Switch autoStart = featureSwitch(R.string.auto_start_boot, RingConfig.isAutoStart(this));
        autoStart.setOnCheckedChangeListener((button, enabled) -> RingConfig.setAutoStart(this, enabled));
        addRow(pen, autoStart);
        addRow(pen, choiceRow(R.string.ring_size, RingConfig.ringSize(this),
                new int[] {R.string.size_small, R.string.size_normal, R.string.size_large},
                new String[] {RingConfig.SIZE_SMALL, RingConfig.SIZE_NORMAL, RingConfig.SIZE_LARGE},
                size -> {
                    RingConfig.setRingSize(this, size);
                    refreshPreview(shortcuts.selected());
                }));
        addRow(pen, dimSliderRow());
        if (Build.VERSION.SDK_INT >= 31) addRow(pen, blurSliderRow());
        LinearLayout windowSizeGroup = Ui.column(this);
        View windowDivider = new View(this);
        windowDivider.setBackgroundColor(colors.border);
        windowSizeGroup.addView(windowDivider, new LinearLayout.LayoutParams(-1, dp(1)));
        windowSizeGroup.addView(choiceRow(R.string.window_size, RingConfig.windowSize(this),
                new int[] {R.string.window_compact, R.string.window_standard, R.string.window_large},
                new String[] {RingConfig.WINDOW_COMPACT, RingConfig.WINDOW_NORMAL, RingConfig.WINDOW_LARGE},
                size -> RingConfig.setWindowSize(this, size)), new LinearLayout.LayoutParams(-1, -2));
        windowSizeGroup.setVisibility(RingConfig.isFloatingLaunch(this) ? View.VISIBLE : View.GONE);

        addRow(pen, choiceRow(R.string.launch_mode, RingConfig.launchMode(this),
                new int[] {R.string.launch_floating, R.string.launch_split, R.string.launch_fullscreen},
                new String[] {RingConfig.LAUNCH_FLOATING, RingConfig.LAUNCH_SPLIT, RingConfig.LAUNCH_FULLSCREEN},
                mode -> {
                    RingConfig.setLaunchMode(this, mode);
                    windowSizeGroup.setVisibility(RingConfig.LAUNCH_FLOATING.equals(mode) ? View.VISIBLE : View.GONE);
                }));
        pen.addView(windowSizeGroup, new LinearLayout.LayoutParams(-1, -2));
        add(pen, 8);
    }

    private LinearLayout dimSliderRow() {
        LinearLayout row = Ui.column(this);
        row.setPadding(0, dp(10), 0, dp(10));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(Ui.text(this, getString(R.string.ring_dim), 14, colors.text, false),
                new LinearLayout.LayoutParams(0, -2, 1));
        int initial = RingConfig.ringDimPercent(this);
        TextView valueLabel = Ui.text(this, dimLabel(initial), 13,
                initial > 0 ? colors.accent : colors.muted, true);
        header.addView(valueLabel);
        row.addView(header, new LinearLayout.LayoutParams(-1, -2));

        SeekBar slider = new SeekBar(this);
        slider.setMax(RingConfig.MAX_DIM_PERCENT / 5);
        slider.setProgress(Math.round(initial / 5f));
        slider.setThumbTintList(ColorStateList.valueOf(colors.accent));
        slider.setProgressTintList(ColorStateList.valueOf(colors.accent));
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(colors.border));
        slider.setContentDescription(getString(R.string.ring_dim));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int step, boolean fromUser) {
                int percent = step * 5;
                RingConfig.setRingDimPercent(MainActivity.this, percent);
                valueLabel.setText(dimLabel(percent));
                valueLabel.setTextColor(percent > 0 ? colors.accent : colors.muted);
                if (previewMenu != null) previewMenu.setDimAlpha(percent / 100f);
            }

            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        LinearLayout.LayoutParams sliderParams = new LinearLayout.LayoutParams(-1, -2);
        sliderParams.topMargin = dp(6);
        row.addView(slider, sliderParams);
        return row;
    }

    private String dimLabel(int percent) {
        return percent <= 0 ? getString(R.string.ring_dim_off) : percent + "%";
    }

    private LinearLayout blurSliderRow() {
        LinearLayout row = Ui.column(this);
        row.setPadding(0, dp(10), 0, dp(10));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(Ui.text(this, getString(R.string.ring_blur), 14, colors.text, false),
                new LinearLayout.LayoutParams(0, -2, 1));
        int initial = RingConfig.ringBlurDp(this);
        TextView valueLabel = Ui.text(this, blurLabel(initial), 13,
                initial > 0 ? colors.accent : colors.muted, true);
        header.addView(valueLabel);
        row.addView(header, new LinearLayout.LayoutParams(-1, -2));

        SeekBar slider = new SeekBar(this);
        slider.setMax(RingConfig.MAX_BLUR_DP / 2);
        slider.setProgress(Math.round(initial / 2f));
        slider.setThumbTintList(ColorStateList.valueOf(colors.accent));
        slider.setProgressTintList(ColorStateList.valueOf(colors.accent));
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(colors.border));
        slider.setContentDescription(getString(R.string.ring_blur));

        boolean needsCaptureHint = !getSystemService(android.view.WindowManager.class).isCrossWindowBlurEnabled()
                && !CircleCaptureService.isReady();
        TextView hint = Ui.text(this, getString(R.string.ring_blur_permission_hint), 12, colors.muted, false);
        hint.setPadding(0, dp(4), 0, 0);
        hint.setVisibility(needsCaptureHint && initial > 0 ? View.VISIBLE : View.GONE);

        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int step, boolean fromUser) {
                int blurDp = step * 2;
                RingConfig.setRingBlurDp(MainActivity.this, blurDp);
                valueLabel.setText(blurLabel(blurDp));
                valueLabel.setTextColor(blurDp > 0 ? colors.accent : colors.muted);
                hint.setVisibility(needsCaptureHint && blurDp > 0 ? View.VISIBLE : View.GONE);
                applyPreviewBlur(blurDp);
            }

            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        LinearLayout.LayoutParams sliderParams = new LinearLayout.LayoutParams(-1, -2);
        sliderParams.topMargin = dp(6);
        row.addView(slider, sliderParams);
        row.addView(hint);
        return row;
    }

    private String blurLabel(int blurDp) {
        return blurDp <= 0 ? getString(R.string.ring_dim_off)
                : Math.round(blurDp * 100f / RingConfig.MAX_BLUR_DP) + "%";
    }

    private void applyPreviewBlur(int blurDp) {
        if (previewWallpaper == null || Build.VERSION.SDK_INT < 31) return;
        if (blurDp <= 0) {
            previewWallpaper.setRenderEffect(null);
        } else {
            float radius = dp(blurDp);
            previewWallpaper.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
        }
    }

    private LinearLayout choiceRow(int title, String current, int[] optionLabels, String[] optionValues,
            Consumer<String> onSelected) {
        LinearLayout row = Ui.column(this);
        row.setPadding(0, dp(10), 0, dp(12));
        row.addView(Ui.text(this, getString(title), 14, colors.text, false));
        LinearLayout group = new LinearLayout(this);
        group.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams groupParams = new LinearLayout.LayoutParams(-1, -2);
        groupParams.topMargin = dp(8);
        TextView[] buttons = new TextView[optionValues.length];
        Runnable[] update = new Runnable[1];
        String[] active = {current};
        update[0] = () -> {
            for (int i = 0; i < buttons.length; i++) {
                boolean selected = optionValues[i].equals(active[0]);
                buttons[i].setBackground(Ui.pressable(this,
                        selected ? colors.accentWash : colors.rail, 10,
                        selected ? colors.accent : colors.border));
                buttons[i].setTextColor(selected ? colors.accent : colors.text);
                buttons[i].setSelected(selected);
            }
        };
        for (int i = 0; i < optionValues.length; i++) {
            String value = optionValues[i];
            TextView button = Ui.text(this, getString(optionLabels[i]), 13, colors.text, true);
            button.setGravity(Gravity.CENTER);
            button.setMinHeight(dp(42));
            button.setPadding(dp(8), dp(6), dp(8), dp(6));
            button.setSingleLine(true);
            button.setFocusable(true);
            button.setOnClickListener(view -> {
                if (value.equals(active[0])) return;
                active[0] = value;
                update[0].run();
                onSelected.accept(value);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) params.setMarginStart(dp(6));
            group.addView(button, params);
            buttons[i] = button;
        }
        update[0].run();
        row.addView(group, groupParams);
        return row;
    }

    private LinearLayout renderPermissions() {
        boolean overlay = Settings.canDrawOverlays(this);
        boolean screenshot = SystemScreenshot.isReady();
        boolean window = WindowModeMonitor.isReady();
        boolean circle = Build.VERSION.SDK_INT >= 30;
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
            ImageButton options = null;
            if (Shortcuts.Tool.CIRCLE_SEARCH.id.equals(app.id)) {
                options = Ui.iconAction(this, R.drawable.ic_settings, R.string.circle_buttons,
                        this::showCircleOptionsDialog);
                options.setImageTintList(ColorStateList.valueOf(colors.accent));
                options.setBackground(Ui.pressable(this, Color.TRANSPARENT, 9, Color.TRANSPARENT));
                row.addView(options, new LinearLayout.LayoutParams(dp(44), dp(48)));
            }
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
                enableShortcutDrag(row, app, source, grip, remove, options);
            }
            rows.add(row);
            addRow(list, row);
        }
        list.setOnDragListener((view, event) -> handleShortcutDrag(list, rows, selected, event));
        return list;
    }

    private void showCircleOptionsDialog() {
        LinearLayout body = Ui.column(this);
        body.setPadding(dp(20), dp(12), dp(20), dp(4));

        LinearLayout list = card();
        addRow(list, circleDefaultRow());

        TextView hint = Ui.text(this, "", 12, colors.muted, false);
        hint.setPadding(dp(4), dp(10), dp(4), 0);
        Runnable refreshHint = () -> {
            boolean anyOn = RingConfig.isCircleShareShown(this)
                    || RingConfig.isCircleCopyShown(this)
                    || RingConfig.isCircleSaveShown(this);
            hint.setText(getString(anyOn ? R.string.circle_buttons_hint : R.string.circle_buttons_lens_only));
        };

        addRow(list, circleToggleRow(R.drawable.ic_share, R.string.circle_action_share,
                RingConfig.isCircleShareShown(this), enabled -> {
                    RingConfig.setCircleShareShown(this, enabled);
                    refreshHint.run();
                }));
        addRow(list, circleToggleRow(R.drawable.ic_copy, R.string.circle_action_copy,
                RingConfig.isCircleCopyShown(this), enabled -> {
                    RingConfig.setCircleCopyShown(this, enabled);
                    refreshHint.run();
                }));
        addRow(list, circleToggleRow(R.drawable.ic_save, R.string.circle_action_save,
                RingConfig.isCircleSaveShown(this), enabled -> {
                    RingConfig.setCircleSaveShown(this, enabled);
                    refreshHint.run();
                }));
        refreshHint.run();

        body.addView(list, new LinearLayout.LayoutParams(-1, -2));
        body.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle(R.string.circle_buttons)
                .setView(body)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private LinearLayout circleDefaultRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(54));
        row.addView(circleOptionBadge(R.drawable.ic_lens, true));
        TextView label = Ui.text(this, getString(R.string.circle_action_lens), 15, colors.text, true);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
        labelParams.setMarginStart(dp(12));
        row.addView(label, labelParams);
        TextView badge = Ui.text(this, getString(R.string.circle_lens_default), 12, colors.accent, true);
        badge.setPadding(dp(10), dp(4), dp(10), dp(4));
        badge.setBackground(Ui.shape(this, colors.accentWash, 10));
        row.addView(badge);
        return row;
    }

    private LinearLayout circleToggleRow(int iconRes, int labelRes, boolean initial,
            Consumer<Boolean> onChanged) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(54));
        FrameLayout badge = circleOptionBadge(iconRes, initial);
        ImageView icon = (ImageView) badge.getChildAt(0);
        row.addView(badge);
        TextView label = Ui.text(this, getString(labelRes), 15, colors.text, true);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
        labelParams.setMarginStart(dp(12));
        row.addView(label, labelParams);
        Switch toggle = new Switch(this);
        toggle.setChecked(initial);
        toggle.setContentDescription(getString(labelRes));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            badge.setBackground(Ui.shape(this, checked ? colors.accentWash : colors.rail, 10));
            icon.setImageTintList(ColorStateList.valueOf(checked ? colors.accent : colors.muted));
            onChanged.accept(checked);
        });
        row.setOnClickListener(view -> toggle.toggle());
        row.addView(toggle);
        return row;
    }

    private FrameLayout circleOptionBadge(int iconRes, boolean active) {
        FrameLayout badge = new FrameLayout(this);
        badge.setBackground(Ui.shape(this, active ? colors.accentWash : colors.rail, 10));
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(active ? colors.accent : colors.muted));
        badge.addView(icon, new FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER));
        badge.setLayoutParams(new LinearLayout.LayoutParams(dp(34), dp(34)));
        return badge;
    }

    private void enableShortcutDrag(View row, Shortcuts.Entry app, int index, View... handles) {
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
        row.setOnTouchListener(trackPress);
        row.setOnLongClickListener(lift);
        for (View target : handles) {
            if (target == null) continue;
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
                () -> pickApp(getString(R.string.add_excluded),
                        () -> shortcuts.availableExcept(exclusions.packages()),
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
        previewWallpaper = new PreviewWallpaper(this, colors.dark);
        previewScene.addView(previewWallpaper, new FrameLayout.LayoutParams(-1, -1));
        applyPreviewBlur(RingConfig.ringBlurDp(this));
        int height = getResources().getConfiguration().screenHeightDp;
        int sceneHeight = dualPane ? Math.max(345, Math.min(445, height * 44 / 100)) : 345;
        LinearLayout.LayoutParams stage = new LinearLayout.LayoutParams(-1, dp(sceneHeight));
        stage.topMargin = dp(10);
        previewPanel.addView(previewScene, stage);
        refreshPreview(shortcuts.selected());
    }

    private void refreshPreview(List<Shortcuts.Entry> selected) {
        if (previewScene == null) return;
        if (previewScene.getChildCount() > 1) previewScene.removeViewAt(1);
        previewMenu = RadialMenu.preview(this, selected);
        previewScene.addView(previewMenu, new FrameLayout.LayoutParams(-1, -1));
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
        pickApp(getString(R.string.add_ring_app), shortcuts::available, shortcuts::add, true);
    }

    private void pickApp(String title, Supplier<List<Shortcuts.Entry>> loadAvailable, Predicate<Shortcuts.Entry> addApp,
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
                Shortcuts.Entry item = getItem(position);
                LinearLayout row = recycled instanceof LinearLayout ? (LinearLayout) recycled : appRow(item);
                ImageView iconView = (ImageView) row.getChildAt(0);
                TextView labelView = (TextView) row.getChildAt(1);
                labelView.setText(item.label);
                iconView.setImageTintList(item.tool != null ? ColorStateList.valueOf(colors.accent) : null);
                iconView.setTag(item.id);
                Drawable ready = item.icon != null ? item.icon : shortcuts.cachedIcon(item.id);
                iconView.setImageDrawable(ready);
                if (ready == null && !background.isShutdown()) {
                    background.execute(() -> {
                        Drawable loaded = shortcuts.loadIcon(item.id);
                        runOnUiThread(() -> {
                            if (dialog.isShowing() && item.id.equals(iconView.getTag())) {
                                iconView.setImageDrawable(loaded);
                            }
                        });
                    });
                }
                return row;
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
            appStatus.setGravity(Gravity.CENTER);
            panel.addView(appStatus, new LinearLayout.LayoutParams(-1, 0, 1));
            results.setEmptyView(appStatus);
        }
        panel.addView(Ui.action(this, getString(R.string.close), false, dialog::dismiss));

        List<Shortcuts.Entry> available = new ArrayList<>();
        boolean[] loading = {true};
        Runnable refreshResults = () -> {
            String term = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            boolean full = ringPicker && shortcuts.selected().size() >= Shortcuts.LIMIT;
            adapter.setNotifyOnChange(false);
            adapter.clear();
            if (!full && !loading[0]) {
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
                        : !loading[0] && adapter.isEmpty() ? getString(R.string.no_apps) : "");
                appStatus.setVisibility(appStatus.length() > 0 ? View.VISIBLE : View.GONE);
            } else {
                appStatus.setText(loading[0] ? "" : getString(R.string.no_apps));
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
        if (!background.isShutdown()) {
            background.execute(() -> {
                List<Shortcuts.Entry> loaded = loadAvailable.get();
                runOnUiThread(() -> {
                    if (!dialog.isShowing() || isFinishing() || isDestroyed()) return;
                    available.clear();
                    available.addAll(loaded);
                    loading[0] = false;
                    refreshResults.run();
                });
            });
        }
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
        if (DockService.running) {
            DockService.stop(this);
        } else if (Settings.canDrawOverlays(this)) {
            DockService.start(this);
        } else {
            openOverlaySettings();
        }
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
