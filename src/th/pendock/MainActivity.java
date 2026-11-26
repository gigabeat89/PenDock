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
    static final String EXTRA_START_TOUR = "start_tour";
    static final String EXTRA_TOUR_THAI = "tour_thai";
    static final String EXTRA_FOCUS_PERMISSION = "focus_permission";
    static final String EXTRA_FOCUS_TOOL = "focus_tool";
    static final String PERM_OVERLAY = "overlay";
    static final String PERM_SYSTEM_ACTIONS = "system_actions";
    static final String PERM_WINDOW = "window";
    static final String PERM_CIRCLE = "circle";
    private static final String PREF_FIRST_TOUR_SHOWN = "first_tour_shown";

    private FrameLayout root;
    private MainTourOverlay tourOverlay;
    private View tourPowerButton, tourAddAppButton, tourPenControlsCard, tourAppearanceCard;
    private View tourOverlayRow, tourSystemActionsRow, tourWindowRow, tourCircleRow;
    private int pendingTourStep = -1;
    private boolean pendingTourThai;
    private String focusedPermission;
    private String focusedTool;
    private boolean pendingPermissionFocus;
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
        addSection(dualPane ? leftColumn : rightColumn, exclusionsSection, 18);
        permissionsSection = Ui.column(this);
        addSection(rightColumn, permissionsSection, 18);

        root = new FrameLayout(this);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        if (state != null && state.getInt("tour_step", -1) >= 0) {
            pendingTourStep = state.getInt("tour_step", -1);
            pendingTourThai = state.getBoolean("tour_thai", "th".equals(Appearance.language(this)));
        } else {
            consumeTourIntent(getIntent());
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        consumeTourIntent(intent);
    }

    private void consumeTourIntent(Intent intent) {
        if (intent == null) return;
        String perm = intent.getStringExtra(EXTRA_FOCUS_PERMISSION);
        if (perm != null && !perm.isEmpty()) {
            focusedPermission = perm;
            focusedTool = intent.getStringExtra(EXTRA_FOCUS_TOOL);
            pendingPermissionFocus = true;
            intent.removeExtra(EXTRA_FOCUS_PERMISSION);
            intent.removeExtra(EXTRA_FOCUS_TOOL);
            return;
        }
        if (!intent.getBooleanExtra(EXTRA_START_TOUR, false)) return;
        pendingTourStep = 0;
        pendingTourThai = intent.getBooleanExtra(EXTRA_TOUR_THAI, "th".equals(Appearance.language(this)));
        intent.removeExtra(EXTRA_START_TOUR);
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (tourOverlay != null) {
            outState.putInt("tour_step", tourOverlay.currentStep());
            outState.putBoolean("tour_thai", tourOverlay.isThai());
        }
    }

    @Override public void onBackPressed() {
        if (tourOverlay != null) {
            tourOverlay.dismiss();
            return;
        }
        super.onBackPressed();
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
        autoStartDockIfAllowed();
        if (focusedPermission != null && isPermissionGranted(focusedPermission)) {
            focusedPermission = null;
            focusedTool = null;
            pendingPermissionFocus = false;
            if (tourOverlay != null) {
                root.removeView(tourOverlay);
                tourOverlay = null;
            }
        }
        render();
        if (pendingPermissionFocus && focusedPermission != null) {
            pendingPermissionFocus = false;
            boolean useThai = "th".equals(Appearance.language(this));
            root.post(() -> startPermissionFocusGuide(focusedPermission, focusedTool, useThai));
        } else if (pendingTourStep >= 0) {
            int step = pendingTourStep;
            boolean useThai = pendingTourThai;
            pendingTourStep = -1;
            root.post(() -> startTour(step, useThai));
        } else if (tourOverlay == null && hasMissingPermissions() && !hasShownFirstTour()) {
            markFirstTourShown();
            boolean useThai = "th".equals(Appearance.language(this));
            root.post(() -> startTour(0, useThai));
        }
    }

    private boolean isPermissionGranted(String permKey) {
        if (PERM_OVERLAY.equals(permKey)) return Settings.canDrawOverlays(this);
        if (PERM_SYSTEM_ACTIONS.equals(permKey)) return SystemScreenshot.isReady();
        if (PERM_WINDOW.equals(permKey)) return WindowModeMonitor.isReady();
        if (PERM_CIRCLE.equals(permKey)) return Build.VERSION.SDK_INT < 30 || CircleCaptureService.isReady();
        return true;
    }

    private void startPermissionFocusGuide(String permKey, String toolKey, boolean useThai) {
        if (root == null || isFinishing() || isDestroyed() || permKey == null) return;
        if (isPermissionGranted(permKey)) return;
        View target = permissionRowForKey(permKey);
        if (target == null) target = permissionsSection;
        if (target == null) return;
        if (tourOverlay != null) {
            root.removeView(tourOverlay);
            tourOverlay = null;
        }
        String titleTh;
        String bodyTh;
        String titleEn;
        String bodyEn;
        if (PERM_CIRCLE.equals(permKey)) {
            boolean isTranslate = "translate".equals(toolKey);
            boolean isPin = "pin".equals(toolKey);
            titleTh = isTranslate
                    ? "ต้องเปิดสิทธิ์สำหรับ \"แปลหน้าจอ\""
                    : isPin ? "ต้องเปิดสิทธิ์สำหรับ \"ปักหมุดหน้าจอ\""
                    : "ต้องเปิดสิทธิ์สำหรับ \"Circle to Search\"";
            bodyTh = isTranslate
                    ? "เครื่องมือ แปลหน้าจอ ต้องใช้บริการ Accessibility สำหรับจับภาพหน้าจอ กดปุ่ม Grant access ในกรอบสีแดงนี้แล้วเปิด \"PenDock Screen Capture\" ได้เลย"
                    : isPin ? "เครื่องมือ ปักหมุดหน้าจอ ต้องใช้บริการ Accessibility สำหรับจับภาพหน้าจอ กดปุ่ม Grant access ในกรอบสีแดงนี้แล้วเปิด \"PenDock Screen Capture\" ได้เลย"
                    : "เครื่องมือ Circle to Search ต้องใช้บริการ Accessibility สำหรับจับภาพหน้าจอ กดปุ่ม Grant access ในกรอบสีแดงนี้แล้วเปิด \"PenDock Screen Capture\" ได้เลย";
            titleEn = isTranslate
                    ? "Permission Required for Screen Translate"
                    : isPin ? "Permission Required for Pin to Screen"
                    : "Permission Required for Circle to Search";
            bodyEn = isTranslate
                    ? "Screen Translate needs the screen-capture Accessibility service. Tap Grant access inside the highlighted row and enable \"PenDock Screen Capture\"."
                    : isPin ? "Pin to Screen needs the screen-capture Accessibility service. Tap Grant access inside the highlighted row and enable \"PenDock Screen Capture\"."
                    : "Circle to Search needs the screen-capture Accessibility service. Tap Grant access inside the highlighted row and enable \"PenDock Screen Capture\".";
        } else if (PERM_SYSTEM_ACTIONS.equals(permKey)) {
            boolean isLock = "lock".equals(toolKey);
            titleTh = isLock
                    ? "ต้องเปิดสิทธิ์สำหรับ \"ล็อกหน้าจอ\""
                    : "ต้องเปิดสิทธิ์สำหรับ \"จับภาพหน้าจอ\"";
            bodyTh = isLock
                    ? "เครื่องมือ ล็อกหน้าจอ ต้องใช้บริการ Accessibility สำหรับคำสั่งระบบ กดปุ่ม Grant access ในกรอบสีแดงนี้แล้วเปิดบริการของ PenDock ได้เลย"
                    : "เครื่องมือ จับภาพหน้าจอ ต้องใช้บริการ Accessibility สำหรับคำสั่งระบบ กดปุ่ม Grant access ในกรอบสีแดงนี้แล้วเปิดบริการของ PenDock ได้เลย";
            titleEn = isLock
                    ? "Permission Required for Lock Screen"
                    : "Permission Required for Screenshot";
            bodyEn = isLock
                    ? "Lock Screen needs the System Actions Accessibility service. Tap Grant access inside the highlighted row and enable it."
                    : "Screenshot needs the System Actions Accessibility service. Tap Grant access inside the highlighted row and enable it.";
        } else if (PERM_WINDOW.equals(permKey)) {
            titleTh = "ต้องเปิดสิทธิ์สำหรับหน้าต่างลอย / แบ่งหน้าจอ";
            bodyTh = "กดปุ่ม Grant access ในกรอบสีแดงนี้เพื่อเปิดบริการ Accessibility สำหรับตรวจจับหน้าต่างลอยและแบ่งหน้าจอ";
            titleEn = "Permission Required for Window Tracking";
            bodyEn = "Tap Grant access inside the highlighted row to enable the window-tracking Accessibility service.";
        } else {
            titleTh = "ต้องเปิดสิทธิ์ \"แสดงทับแอพอื่น\" ก่อน (จำเป็น)";
            bodyTh = "จำเป็นต้องเปิดสิทธิ์นี้ ไม่งั้นวงเมนู PenDock จะไม่สามารถใช้งานได้ กดปุ่ม Grant access ในกรอบสีแดงนี้ได้เลย";
            titleEn = "Grant \"Display over other apps\" First (Required)";
            bodyEn = "PenDock cannot open or work without this permission. Tap Grant access inside the highlighted row.";
        }
        List<MainTourOverlay.Step> single = Collections.singletonList(
                new MainTourOverlay.Step(target, titleTh, bodyTh, titleEn, bodyEn, false, true));
        tourOverlay = new MainTourOverlay(this, scroll, single, useThai, 0,
                () -> {
                    Intent guideIntent = new Intent(this, GuideActivity.class);
                    guideIntent.putExtra("guide_thai", tourOverlay != null ? tourOverlay.isThai() : useThai);
                    startActivity(guideIntent);
                },
                () -> {
                    if (tourOverlay != null && root != null) {
                        root.removeView(tourOverlay);
                        tourOverlay = null;
                    }
                    if (focusedPermission != null) {
                        focusedPermission = null;
                        focusedTool = null;
                        render();
                    }
                });
        root.addView(tourOverlay, new FrameLayout.LayoutParams(-1, -1));
    }

    private View permissionRowForKey(String permKey) {
        if (PERM_OVERLAY.equals(permKey)) return tourOverlayRow;
        if (PERM_SYSTEM_ACTIONS.equals(permKey)) return tourSystemActionsRow;
        if (PERM_WINDOW.equals(permKey)) return tourWindowRow;
        if (PERM_CIRCLE.equals(permKey)) return tourCircleRow;
        return null;
    }

    private void autoStartDockIfAllowed() {
        if (DockService.isEnabled(this) && !DockService.running && Settings.canDrawOverlays(this)) {
            DockService.start(this);
        }
    }

    private boolean hasMissingPermissions() {
        boolean overlay = Settings.canDrawOverlays(this);
        boolean screenshot = SystemScreenshot.isReady();
        boolean window = WindowModeMonitor.isReady();
        boolean circleOk = Build.VERSION.SDK_INT < 30 || CircleCaptureService.isReady();
        return !(overlay && screenshot && window && circleOk);
    }

    private boolean hasShownFirstTour() {
        return getSharedPreferences("tools", MODE_PRIVATE).getBoolean(PREF_FIRST_TOUR_SHOWN, false);
    }

    private void markFirstTourShown() {
        getSharedPreferences("tools", MODE_PRIVATE).edit().putBoolean(PREF_FIRST_TOUR_SHOWN, true).apply();
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
        if (focused) {
            autoStartDockIfAllowed();
            boolean effective = DockService.running || (DockService.isEnabled(this) && Settings.canDrawOverlays(this));
            if (content != null && menuStatus != effective) render();
        }
    }

    private void render() {
        int activeTourStep = -1;
        boolean activeTourThai = "th".equals(Appearance.language(this));
        if (tourOverlay != null) {
            activeTourStep = tourOverlay.currentStep();
            activeTourThai = tourOverlay.isThai();
            root.removeView(tourOverlay);
            tourOverlay = null;
        }
        content.removeAllViews();
        exclusionsSection.removeAllViews();
        permissionsSection.removeAllViews();
        menuStatus = DockService.running || (DockService.isEnabled(this) && Settings.canDrawOverlays(this));
        renderHeader();
        renderMenuSettings();
        renderPreview();
        addSection(exclusionsSection, renderExclusions(), 0);
        addSection(permissionsSection, renderPermissions(), 0);
        if (activeTourStep >= 0) {
            int step = activeTourStep;
            boolean useThai = activeTourThai;
            root.post(() -> startTour(step, useThai));
        }
    }

    private void renderHeader() {
        headerActions.removeAllViews();
        int dockAction = menuStatus ? R.string.dock_off : R.string.dock_on;
        ImageButton dock = Ui.iconAction(this, R.drawable.ic_power, dockAction, this::toggleDock);
        dock.setTooltipText(getString(dockAction));
        dock.setImageTintList(ColorStateList.valueOf(dockStatusStroke()));
        dock.setBackground(Ui.pressable(this, dockStatusFill(), 12, dockStatusStroke()));
        tourPowerButton = dock;
        headerActions.addView(dock, new LinearLayout.LayoutParams(dp(60), dp(48)));
        LinearLayout.LayoutParams guideParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        guideParams.setMarginStart(dp(8));
        ImageButton guide = Ui.iconAction(this, R.drawable.ic_guide, R.string.user_guide,
                () -> startTour(0, "th".equals(Appearance.language(this))));
        guide.setTooltipText(getString(R.string.user_guide));
        headerActions.addView(guide, guideParams);
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        settingsParams.setMarginStart(dp(8));
        headerActions.addView(Ui.iconAction(this, R.drawable.ic_settings, R.string.settings,
                () -> startActivity(new Intent(this, SettingsActivity.class))), settingsParams);
    }

    private void startTour(int startStep, boolean startThai) {
        if (root == null || isFinishing() || isDestroyed()) return;
        if (tourOverlay != null) {
            root.removeView(tourOverlay);
            tourOverlay = null;
        }
        List<MainTourOverlay.Step> steps = new ArrayList<>();
        boolean prioritizePermissions = hasMissingPermissions();
        if (prioritizePermissions && permissionsSection != null) {
            steps.add(buildPermissionsTourStep(1, true));
        }
        if (tourPowerButton != null) {
            int n = steps.size() + 1;
            steps.add(new MainTourOverlay.Step(
                    tourPowerButton,
                    n + ". เปิด / ปิดวงเมนู PenDock",
                    "วงเมนูจะเริ่มทำงานอัตโนมัติทันทีที่เปิดสิทธิ์แสดงทับแอพอื่น และแตะปุ่มนี้เพื่อเปิดหรือหยุดได้ทุกเมื่อ (หรือสลับจาก Quick Settings) เมื่อเปิดแล้วให้ถือ M-Pencil ใกล้จอและแตะด้านข้างปากกา 2 ครั้งเพื่อเรียกวงเมนูได้จากทุกหน้าจอ",
                    n + ". Turn PenDock On / Off",
                    "PenDock starts automatically once Display over other apps is granted. Tap here anytime to pause or resume (or use Quick Settings), then hold M-Pencil near the screen and double-tap its side anywhere to summon the ring.",
                    true));
        }
        if (tourAddAppButton != null) {
            int n = steps.size() + 1;
            steps.add(new MainTourOverlay.Step(
                    tourAddAppButton,
                    n + ". เพิ่มแอพและเครื่องมือลงวงเมนู",
                    "แตะปุ่ม Add app เพื่อติ๊กเลือกแอพและเครื่องมือเสริม (แปลหน้าจอ, Circle to Search, ภาพหน้าจอ, เลเซอร์พอยเตอร์, ปุ่มโฮม/ย้อนกลับ) ได้สูงสุด 10 รายการ และกดค้างที่แถวเพื่อลากจัดลำดับได้ทันที",
                    n + ". Add Apps & Built-in Tools",
                    "Tap Add app to check or uncheck up to 10 apps and built-in tools (Screen Translate, Circle to Search, Screenshot, Laser Pointer, Home/Back). Press and hold any row to drag-reorder."));
        }
        if (previewScene != null) {
            int n = steps.size() + 1;
            steps.add(new MainTourOverlay.Step(
                    previewScene,
                    n + ". พรีวิววงเมนูแบบเรียลไทม์",
                    "ดูหน้าตาวงเมนูจริงพร้อมลองจ่อปากกาหรือแตะทดสอบได้ทันที ทุกการเพิ่ม ลบ ลากเรียงลำดับ หรือเปลี่ยนสไตล์จะอัปเดตให้เห็นบนพื้นที่นี้สดๆ",
                    n + ". Live Interactive Ring Preview",
                    "Test hovering and tapping your ring right here. Every added shortcut, drag-reorder, or style tweak updates this live preview immediately."));
        }
        if (tourAppearanceCard != null) {
            int n = steps.size() + 1;
            steps.add(new MainTourOverlay.Step(
                    tourAppearanceCard,
                    n + ". ปรับแต่งหน้าตาวงเมนู",
                    "เลือกสไตล์วงเมนูได้ 6 แบบ ตั้งธีมวงเมนู (สว่าง/มืด) แยกจากธีมแอพ เลือกสีวงเมนู ขนาดวง ความมืดฉากหลัง (Dim) และความเบลอพื้นหลัง (Blur) ตามชอบ",
                    n + ". Ring Appearance",
                    "Pick from 6 ring styles, set a Ring theme independently from the app theme, choose a Ring color, and adjust ring size, background dimming, and live backdrop blur."));
        }
        if (tourPenControlsCard != null) {
            int n = steps.size() + 1;
            steps.add(new MainTourOverlay.Step(
                    tourPenControlsCard,
                    n + ". การควบคุมปากกา & รูปแบบการเปิดแอพ",
                    "เลือกได้ว่าต้องใช้ปลายปากกาเท่านั้นหรือแตะด้วยนิ้วได้ ต้องจ่อใกล้จอหรือไม่ เปิดอัตโนมัติตอนเปิดเครื่อง และตั้งค่าการเปิดแอพเป็น หน้าต่างลอย (Floating), แบ่งหน้าจอ (Split screen) หรือ เต็มจอ",
                    n + ". Pen Controls & Launch Mode",
                    "Configure pen-only vs touch input, near-screen trigger, auto-start on boot, and choose whether shortcuts open in Floating window, Split screen, or Full screen."));
        }
        if (exclusionsSection != null) {
            int n = steps.size() + 1;
            steps.add(new MainTourOverlay.Step(
                    exclusionsSection,
                    n + ". แอพที่ยกเว้น (Excluded apps)",
                    "ใส่แอพวาดรูปหรือจดโน้ตอย่าง GoPaint หรือ Notes ไว้ตรงนี้ เพื่อให้การแตะด้านข้างปากกา 2 ครั้งยังคงสลับยางลบ/ปากกาของแอพนั้นตามปกติโดยไม่เปิด PenDock ทับ",
                    n + ". Excluded Apps",
                    "Add drawing or note apps like GoPaint or Notes here so double-tapping M-Pencil keeps switching to the eraser inside those apps instead of opening PenDock."));
        }
        if (!prioritizePermissions && permissionsSection != null) {
            steps.add(buildPermissionsTourStep(steps.size() + 1, false));
        }
        if (steps.isEmpty()) return;
        tourOverlay = new MainTourOverlay(this, scroll, steps, startThai, startStep,
                () -> {
                    Intent guideIntent = new Intent(this, GuideActivity.class);
                    guideIntent.putExtra("guide_thai", tourOverlay != null ? tourOverlay.isThai() : startThai);
                    startActivity(guideIntent);
                },
                () -> {
                    if (tourOverlay != null && root != null) {
                        root.removeView(tourOverlay);
                        tourOverlay = null;
                    }
                });
        root.addView(tourOverlay, new FrameLayout.LayoutParams(-1, -1));
    }

    private MainTourOverlay.Step buildPermissionsTourStep(int stepNumber, boolean missingNow) {
        boolean overlayMissing = !Settings.canDrawOverlays(this);
        if (overlayMissing) {
            View target = tourOverlayRow != null ? tourOverlayRow : permissionsSection;
            return new MainTourOverlay.Step(
                    target,
                    stepNumber + ". ต้องเปิดสิทธิ์แสดงทับแอพอื่นก่อน (จำเป็น)",
                    "จำเป็นต้องเปิดสิทธิ์ \"แสดงทับแอพอื่น\" (Display over other apps) ก่อน ไม่เช่นนั้นวงเมนู PenDock จะไม่สามารถแสดงและใช้งานได้ กดปุ่ม Grant access ในกรอบนี้ได้เลย เมื่อเปิดแล้ววงเมนูจะเริ่มทำงานให้อัตโนมัติทันที",
                    stepNumber + ". Grant \"Display over other apps\" First (Required)",
                    "You must grant \"Display over other apps\" first — without it, PenDock cannot open or work. Tap Grant access right inside this highlight; once granted, PenDock starts automatically.",
                    false,
                    true);
        }
        if (missingNow) {
            return new MainTourOverlay.Step(
                    permissionsSection,
                    stepNumber + ". เปิดสิทธิ์การใช้งานเพิ่มเติม",
                    "กดปุ่ม Grant access ในกรอบนี้เพื่อเปิดบริการ Accessibility ที่เหลือ เพื่อให้ฟีเจอร์ ภาพหน้าจอ, แปลหน้าจอ / Circle to Search และโหมดหน้าต่างลอย/แบ่งหน้าจอทำงานได้ครบทุกอย่าง",
                    stepNumber + ". Enable Remaining Permissions",
                    "Tap Grant access inside this highlight to turn on the remaining Accessibility services so Screenshot, Screen Translate / Circle to Search, and Floating/Split modes all work smoothly.",
                    false,
                    true);
        }
        return new MainTourOverlay.Step(
                permissionsSection,
                stepNumber + ". สิทธิ์การใช้งานที่จำเป็น",
                "ตรวจสอบสิทธิ์ แสดงทับแอพอื่น และบริการ Accessibility ทั้งหมดในส่วนนี้ได้ตลอดเวลา (ส่วนธีม ภาษา จัดการโมเดล OCR และเช็กอัปเดตอยู่ที่ไอคอนฟันเฟืองมุมขวาบน)",
                stepNumber + ". Permissions & Settings",
                "Manage Display over other apps and Accessibility services here anytime. Use the gear icon at the top right for theme, language, OCR models, and updates.",
                false,
                true);
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
        LinearLayout ringItemsSection = Ui.column(this);
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
        TextView addBtn = Ui.action(this, getString(R.string.add_app), false, this::pickApp);
        heading.addView(addBtn);
        addSection(ringItemsSection, heading, 0);
        if (selected.isEmpty()) {
            addSection(ringItemsSection, Ui.text(this, getString(R.string.empty_ring), 14, colors.muted, false), 12);
        } else {
            if (selected.size() > 1) {
                addSection(ringItemsSection, Ui.text(this, getString(R.string.drag_hint), 13, colors.muted, false), 7);
            }
            addSection(ringItemsSection, shortcutList(selected), 10);
        }
        tourAddAppButton = ringItemsSection;
        add(ringItemsSection, 0);

        add(Ui.text(this, getString(R.string.pen_controls), 16, colors.text, true), 19);
        LinearLayout pen = card();
        tourPenControlsCard = pen;
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
        boolean scrollable = optionValues.length > 3;
        for (int i = 0; i < optionValues.length; i++) {
            String value = optionValues[i];
            TextView button = Ui.text(this, getString(optionLabels[i]), 13, colors.text, true);
            button.setGravity(Gravity.CENTER);
            button.setMinHeight(dp(42));
            button.setPadding(dp(scrollable ? 14 : 8), dp(6), dp(scrollable ? 14 : 8), dp(6));
            button.setSingleLine(true);
            button.setFocusable(true);
            button.setOnClickListener(view -> {
                if (value.equals(active[0])) return;
                active[0] = value;
                update[0].run();
                onSelected.accept(value);
            });
            LinearLayout.LayoutParams params = scrollable
                    ? new LinearLayout.LayoutParams(-2, -2, 1)
                    : new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) params.setMarginStart(dp(6));
            group.addView(button, params);
            buttons[i] = button;
        }
        update[0].run();
        if (scrollable) {
            android.widget.HorizontalScrollView scroller = new android.widget.HorizontalScrollView(this);
            scroller.setHorizontalScrollBarEnabled(false);
            scroller.setFillViewport(true);
            scroller.addView(group, new FrameLayout.LayoutParams(-2, -2));
            row.addView(scroller, groupParams);
        } else {
            row.addView(group, groupParams);
        }
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
        boolean isThai = "th".equals(Appearance.language(this));
        View overlayRow = permissionRow(R.string.overlay_title, R.string.overlay_detail,
                overlay, this::openOverlaySettings, !overlay,
                isThai ? "จำเป็นต้องเปิดสิทธิ์นี้ ไม่งั้นวงเมนู PenDock จะไม่สามารถใช้งานได้"
                        : "Required — PenDock cannot open or work without this permission.");
        tourOverlayRow = overlayRow;
        addSection(system, overlayRow, 8);

        boolean highlightSys = !screenshot && PERM_SYSTEM_ACTIONS.equals(focusedPermission);
        View sysRow = permissionRow(R.string.system_actions_heading, R.string.screenshot_detail,
                screenshot, this::openAccessibility, highlightSys,
                isThai ? "จำเป็นต้องเปิดสิทธิ์นี้เพื่อใช้งานเครื่องมือที่คุณเลือก"
                        : "Required to use the tool you just selected.");
        tourSystemActionsRow = sysRow;
        addSection(system, sysRow, 2);

        boolean highlightWin = !window && PERM_WINDOW.equals(focusedPermission);
        View winRow = permissionRow(R.string.window_title, R.string.window_detail,
                window, this::openAccessibility, highlightWin,
                isThai ? "จำเป็นต้องเปิดสิทธิ์นี้เพื่อใช้งานโหมดหน้าต่างลอย/แบ่งหน้าจอ"
                        : "Required for floating and split-screen window tracking.");
        tourWindowRow = winRow;
        addSection(system, winRow, 2);

        tourCircleRow = null;
        if (circle) {
            boolean highlightCircle = !circleReady && PERM_CIRCLE.equals(focusedPermission);
            View circleRow = permissionRow(R.string.circle_search, R.string.circle_detail,
                    circleReady, this::openAccessibility, highlightCircle,
                    isThai ? "จำเป็นต้องเปิดสิทธิ์นี้เพื่อใช้งาน แปลหน้าจอ / Circle to Search"
                            : "Required to use Screen Translate and Circle to Search.");
            tourCircleRow = circleRow;
            addSection(system, circleRow, 2);
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

    private View permissionRow(int title, int detail, boolean granted, Runnable openSettings,
            boolean highlightRequired, String requiredReason) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(51));
        row.setPadding(dp(12), dp(9), dp(12), dp(9));
        int warnText = colors.pick(0xffb4232c, 0xffff9ea8);
        if (highlightRequired && !granted) {
            int warnFill = colors.pick(0xfffff2f2, 0xff3b2328);
            int warnBorder = colors.pick(0xffe0525c, 0xffff8792);
            row.setBackground(Ui.border(this, warnFill, 13, warnBorder));
        }
        LinearLayout labels = Ui.column(this);
        if (highlightRequired && !granted) {
            LinearLayout titleLine = new LinearLayout(this);
            titleLine.setGravity(Gravity.CENTER_VERTICAL);
            titleLine.addView(Ui.text(this, getString(title), 14, colors.text, true),
                    new LinearLayout.LayoutParams(-2, -2));
            boolean isThai = "th".equals(Appearance.language(this));
            TextView reqBadge = Ui.text(this, isThai ? "จำเป็น" : "Required", 11, warnText, true);
            reqBadge.setPadding(dp(7), dp(2), dp(7), dp(2));
            reqBadge.setBackground(Ui.shape(this, colors.pick(0xffffdce0, 0xff5a2832), 8));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
            bp.setMarginStart(dp(8));
            titleLine.addView(reqBadge, bp);
            labels.addView(titleLine);
            TextView detailView = Ui.text(this, requiredReason, 12, warnText, false);
            LinearLayout.LayoutParams dpP = new LinearLayout.LayoutParams(-1, -2);
            dpP.topMargin = dp(2);
            labels.addView(detailView, dpP);
        } else {
            labels.addView(Ui.text(this, getString(title), 14, colors.text, true));
            labels.addView(Ui.text(this, getString(detail), 12, colors.muted, false));
        }
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
        styleSwitch(toggle);
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

        addSection(previewPanel, Ui.text(this, getString(R.string.ring_appearance), 16, colors.text, true), 18);
        LinearLayout appearance = card();
        tourAppearanceCard = appearance;
        addRow(appearance, choiceRow(R.string.ring_style, RingConfig.ringStyle(this),
                new int[] {R.string.style_petals, R.string.style_orbit, R.string.style_halo,
                        R.string.style_hexa, R.string.style_dial, R.string.style_minimal},
                new String[] {RingConfig.STYLE_PETALS, RingConfig.STYLE_ORBIT, RingConfig.STYLE_HALO,
                        RingConfig.STYLE_HEXA, RingConfig.STYLE_DIAL, RingConfig.STYLE_MINIMAL},
                style -> {
                    RingConfig.setRingStyle(this, style);
                    refreshPreview(shortcuts.selected());
                }));
        addRow(appearance, choiceRow(R.string.ring_theme, RingConfig.ringTheme(this),
                new int[] {R.string.ring_theme_app, R.string.theme_light, R.string.theme_dark},
                new String[] {RingConfig.THEME_APP, RingConfig.THEME_LIGHT, RingConfig.THEME_DARK},
                theme -> {
                    RingConfig.setRingTheme(this, theme);
                    if (previewMenu != null) previewMenu.reloadColors();
                    else refreshPreview(shortcuts.selected());
                }));
        addRow(appearance, ringColorRow());
        addRow(appearance, choiceRow(R.string.ring_size, RingConfig.ringSize(this),
                new int[] {R.string.size_small, R.string.size_normal, R.string.size_large},
                new String[] {RingConfig.SIZE_SMALL, RingConfig.SIZE_NORMAL, RingConfig.SIZE_LARGE},
                size -> {
                    RingConfig.setRingSize(this, size);
                    refreshPreview(shortcuts.selected());
                }));
        addRow(appearance, dimSliderRow());
        if (Build.VERSION.SDK_INT >= 31) addRow(appearance, blurSliderRow());
        addSection(previewPanel, appearance, 8);
    }

    private LinearLayout ringColorRow() {
        int[] currentHue = {RingConfig.ringHue(this)};

        LinearLayout row = Ui.column(this);
        row.setPadding(0, dp(10), 0, dp(12));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(Ui.text(this, getString(R.string.ring_color), 14, colors.text, false),
                new LinearLayout.LayoutParams(0, -2, 1));

        View previewDot = new View(this);
        LinearLayout.LayoutParams dotP = new LinearLayout.LayoutParams(dp(18), dp(18));
        dotP.setMarginEnd(dp(8));
        header.addView(previewDot, dotP);

        TextView defaultBtn = Ui.text(this, getString(R.string.color_default), 12, colors.accent, true);
        defaultBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        defaultBtn.setFocusable(true);
        header.addView(defaultBtn, new LinearLayout.LayoutParams(-2, -2));
        row.addView(header, new LinearLayout.LayoutParams(-1, -2));

        ColorSpectrumBar bar = new ColorSpectrumBar(this, currentHue[0]);
        Runnable syncUi = () -> {
            boolean isDef = currentHue[0] < 0;
            int dotFill = isDef ? colors.pick(0xfff3f5fc, 0xff252e46)
                    : RingConfig.ringPreviewColor(currentHue[0]);
            previewDot.setBackground(Ui.border(this, dotFill, 9, colors.border));
            defaultBtn.setBackground(Ui.pressable(this,
                    isDef ? colors.accentWash : colors.rail, 10,
                    isDef ? colors.accent : colors.border));
            defaultBtn.setTextColor(isDef ? colors.accent : colors.muted);
        };
        defaultBtn.setOnClickListener(v -> {
            if (currentHue[0] < 0) return;
            currentHue[0] = RingConfig.HUE_DEFAULT;
            bar.setHue(RingConfig.HUE_DEFAULT);
            RingConfig.setRingHue(this, RingConfig.HUE_DEFAULT);
            syncUi.run();
            if (previewMenu != null) previewMenu.reloadColors();
            else refreshPreview(shortcuts.selected());
        });
        bar.setOnHueChangedListener((hue, finished) -> {
            currentHue[0] = hue;
            RingConfig.setRingHue(this, hue);
            syncUi.run();
            if (previewMenu != null) previewMenu.reloadColors();
            else refreshPreview(shortcuts.selected());
        });
        syncUi.run();

        LinearLayout.LayoutParams barP = new LinearLayout.LayoutParams(-1, dp(42));
        barP.topMargin = dp(6);
        row.addView(bar, barP);
        return row;
    }

    private interface OnHueChangedListener {
        void onHueChanged(int hue, boolean finished);
    }

    /** Sleek 1-bar full-spectrum rainbow gradient slider for picking any ring color smoothly. */
    private final class ColorSpectrumBar extends View {
        private final android.graphics.Paint trackPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint borderPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint thumbFill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint thumbRim = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF trackRect = new android.graphics.RectF();
        private final int[] spectrum = {
                0xffe63946, 0xfff77f00, 0xffe9c46a, 0xff2a9d8f,
                0xff00b4d8, 0xff3b82f6, 0xff7c3aed, 0xffec4899, 0xffe63946
        };
        private int hue;
        private OnHueChangedListener listener;

        ColorSpectrumBar(Context context, int initialHue) {
            super(context);
            this.hue = initialHue;
            borderPaint.setStyle(android.graphics.Paint.Style.STROKE);
            borderPaint.setStrokeWidth(dp(1));
            borderPaint.setColor(colors.border);
            thumbRim.setStyle(android.graphics.Paint.Style.STROKE);
            thumbRim.setStrokeWidth(dp(3));
            thumbRim.setColor(0xffffffff);
            thumbFill.setShadowLayer(dp(4), 0, dp(1.5f), 0x44000000);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        void setHue(int newHue) {
            this.hue = newHue;
            invalidate();
        }

        void setOnHueChangedListener(OnHueChangedListener l) {
            this.listener = l;
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            float pad = dp(14);
            if (w > pad * 2) {
                trackPaint.setShader(new android.graphics.LinearGradient(
                        pad, 0, w - pad, 0, spectrum, null, Shader.TileMode.CLAMP));
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float pad = dp(14);
            float trackH = dp(14);
            float cy = h * 0.5f;
            trackRect.set(pad, cy - trackH * 0.5f, w - pad, cy + trackH * 0.5f);
            float r = trackH * 0.5f;
            canvas.drawRoundRect(trackRect, r, r, trackPaint);
            canvas.drawRoundRect(trackRect, r, r, borderPaint);

            if (hue >= 0) {
                float frac = (hue % 360) / 360f;
                float cx = pad + frac * (w - pad * 2f);
                float thumbR = dp(12);
                thumbFill.setColor(RingConfig.ringPreviewColor(hue));
                canvas.drawCircle(cx, cy, thumbR, thumbFill);
                canvas.drawCircle(cx, cy, thumbR - dp(1.2f), thumbRim);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE
                    || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                float pad = dp(14);
                float span = Math.max(1f, getWidth() - pad * 2f);
                float frac = Math.max(0f, Math.min(1f, (event.getX() - pad) / span));
                int newHue = Math.min(359, Math.round(frac * 359f));
                boolean finished = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
                if (newHue != hue || finished) {
                    hue = newHue;
                    invalidate();
                    if (listener != null) listener.onHueChanged(hue, finished);
                }
                return true;
            }
            return super.onTouchEvent(event);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00ffffff) | ((alpha & 255) << 24);
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
        styleSwitch(control);
        control.setChecked(enabled);
        return control;
    }

    private void styleSwitch(Switch control) {
        int[][] states = {{android.R.attr.state_checked}, {}};
        control.setThumbTintList(new ColorStateList(states, new int[] {
                colors.pick(0xff2863da, 0xff6e96ff),
                colors.pick(0xfff5f7fc, 0xffc6cee0)
        }));
        control.setTrackTintList(new ColorStateList(states, new int[] {
                colors.pick(0xff2863da, 0xff5a85f5),
                colors.pick(0xff9aa6be, 0xff5e6a85)
        }));
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
        pickApp(getString(R.string.add_ring_app),
                () -> shortcuts.availableExcept(java.util.Collections.emptySet()), shortcuts::add, true);
    }

    private void pickApp(String title, Supplier<List<Shortcuts.Entry>> loadAvailable, Predicate<Shortcuts.Entry> addApp,
            boolean ringPicker) {
        Dialog dialog = new Dialog(this);
        dialog.setTitle(title);
        LinearLayout panel = Ui.column(this);
        int padding = dp(16);
        panel.setPadding(padding, padding, padding, padding);
        panel.setBackground(Ui.shape(this, colors.background, 18));
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(Ui.text(this, title, 20, colors.text, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView countLabel = ringPicker ? Ui.text(this, "", 13, colors.muted, true) : null;
        if (countLabel != null) titleRow.addView(countLabel);
        panel.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint(getString(R.string.search_apps));
        search.setHintTextColor(colors.muted);
        search.setTextColor(colors.text);
        panel.addView(search, new LinearLayout.LayoutParams(-1, dp(52)));

        TextView limitHint = ringPicker ? Ui.text(this, getString(R.string.ring_item_limit, Shortcuts.LIMIT),
                12, colors.accent, false) : null;
        if (limitHint != null) {
            limitHint.setPadding(dp(4), 0, dp(4), dp(6));
            limitHint.setVisibility(View.GONE);
            panel.addView(limitHint, new LinearLayout.LayoutParams(-1, -2));
        }

        java.util.Set<String> chosenIds = new java.util.HashSet<>();
        ArrayAdapter<Shortcuts.Entry> adapter = new ArrayAdapter<Shortcuts.Entry>(this, 0) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                Shortcuts.Entry item = getItem(position);
                LinearLayout row;
                CheckBox toggle = null;
                if (recycled instanceof LinearLayout) {
                    row = (LinearLayout) recycled;
                    if (ringPicker && row.getChildCount() > 2 && row.getChildAt(2) instanceof CheckBox) {
                        toggle = (CheckBox) row.getChildAt(2);
                    }
                } else {
                    row = appRow(item);
                    if (ringPicker) {
                        toggle = new CheckBox(MainActivity.this);
                        toggle.setClickable(false);
                        toggle.setFocusable(false);
                        toggle.setFocusableInTouchMode(false);
                        row.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
                    }
                }
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
                if (ringPicker && toggle != null) {
                    boolean checked = chosenIds.contains(item.id);
                    boolean enabled = checked || chosenIds.size() < Shortcuts.LIMIT;
                    toggle.setChecked(checked);
                    toggle.setEnabled(enabled);
                    row.setAlpha(enabled ? 1f : 0.45f);
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
            chosenIds.clear();
            if (ringPicker) {
                for (Shortcuts.Entry entry : shortcuts.selected()) chosenIds.add(entry.id);
            }
            boolean full = ringPicker && chosenIds.size() >= Shortcuts.LIMIT;
            if (countLabel != null) {
                countLabel.setText(getString(R.string.ring_item_count, chosenIds.size(), Shortcuts.LIMIT));
                countLabel.setTextColor(full ? colors.accent : colors.muted);
            }
            if (limitHint != null) {
                limitHint.setVisibility(full ? View.VISIBLE : View.GONE);
            }
            adapter.setNotifyOnChange(false);
            adapter.clear();
            if (!loading[0]) {
                for (Shortcuts.Entry app : available) {
                    if (app.label.toLowerCase(Locale.ROOT).contains(term)
                            || app.id.toLowerCase(Locale.ROOT).contains(term)) {
                        adapter.add(app);
                    }
                }
            }
            if (ringPicker) {
                for (CheckBox choice : toolOptions) {
                    Shortcuts.Entry tag = (Shortcuts.Entry) choice.getTag();
                    boolean checked = tag != null && chosenIds.contains(tag.id);
                    choice.setChecked(checked);
                    choice.setEnabled(checked || !full);
                }
                appStatus.setText(!loading[0] && adapter.isEmpty() ? getString(R.string.no_apps) : "");
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
            if (ringPicker) {
                if (chosenIds.contains(choice.id)) {
                    shortcuts.remove(choice.id);
                } else if (!addApp.test(choice)) {
                    return;
                }
                render();
                refreshResults.run();
            } else {
                if (!addApp.test(choice)) return;
                render();
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
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        int dialogW = Math.min(Ui.dp(this, 480), screenW - padding * 2);
        int dialogH = Math.min(Ui.dp(this, 560), height * 4 / 5);
        dialog.getWindow().setLayout(dialogW, dialogH);
        if (dualPane && ringPicker) {
            android.view.WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
            lp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
            lp.x = Math.max(dp(36), (screenW / 2 - dialogW) / 2);
            lp.dimAmount = 0.25f;
            dialog.getWindow().setAttributes(lp);
        }
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
        if (DockService.running || (DockService.isEnabled(this) && Settings.canDrawOverlays(this))) {
            DockService.stop(this);
            render();
        } else if (Settings.canDrawOverlays(this)) {
            DockService.start(this);
            render();
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
