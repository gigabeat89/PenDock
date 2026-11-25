package th.pendock;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Bilingual (Thai / English) in-app usage guide covering all PenDock features. */
public class GuideActivity extends Activity {
    private Appearance.Palette colors;
    private boolean thai;
    private TextView headerTitle;
    private TextView thButton;
    private TextView enButton;
    private LinearLayout body;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(Appearance.localize(base));
    }

    @Override protected void onCreate(Bundle state) {
        setTheme(Appearance.isDark(this) ? R.style.AppThemeDark : R.style.AppTheme);
        super.onCreate(state);
        colors = Ui.colors(this);
        boolean defaultThai = getIntent() != null && getIntent().hasExtra("guide_thai")
                ? getIntent().getBooleanExtra("guide_thai", "th".equals(Appearance.language(this)))
                : "th".equals(Appearance.language(this));
        thai = state != null ? state.getBoolean("guide_thai", defaultThai) : defaultThai;
        getWindow().setStatusBarColor(colors.background);
        getWindow().setNavigationBarColor(colors.background);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(colors.background);
        FrameLayout shell = new FrameLayout(this);
        scroll.addView(shell, new ScrollView.LayoutParams(-1, -2));

        LinearLayout page = Ui.column(this);
        int width = Math.min(getResources().getConfiguration().screenWidthDp, 820);
        shell.addView(page, new FrameLayout.LayoutParams(Ui.dp(this, width), -2, Gravity.CENTER_HORIZONTAL));
        int padding = dp(18);
        page.setPadding(padding, padding, padding, padding * 2);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int touch = dp(48);
        header.addView(Ui.iconAction(this, R.drawable.ic_back, R.string.back, this::finish),
                new LinearLayout.LayoutParams(touch, touch));
        headerTitle = Ui.text(this, "", 22, colors.text, true);
        headerTitle.setPadding(dp(12), 0, dp(8), 0);
        headerTitle.setAccessibilityHeading(true);
        header.addView(headerTitle, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(languageToggle(), new LinearLayout.LayoutParams(-2, -2));
        page.addView(header, new LinearLayout.LayoutParams(-1, -2));

        body = Ui.column(this);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, -2);
        bodyParams.topMargin = dp(14);
        page.addView(body, bodyParams);

        renderGuide();
        setContentView(scroll);
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("guide_thai", thai);
    }

    private LinearLayout languageToggle() {
        LinearLayout group = new LinearLayout(this);
        group.setGravity(Gravity.CENTER_VERTICAL);
        group.setPadding(dp(3), dp(3), dp(3), dp(3));
        group.setBackground(Ui.border(this, colors.rail, 12, colors.border));

        thButton = langPill("ไทย", () -> setThai(true));
        enButton = langPill("EN", () -> setThai(false));
        group.addView(thButton, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams enParams = new LinearLayout.LayoutParams(-2, -2);
        enParams.setMarginStart(dp(4));
        group.addView(enButton, enParams);
        return group;
    }

    private TextView langPill(String text, Runnable onClick) {
        TextView pill = Ui.text(this, text, 13, colors.text, true);
        pill.setGravity(Gravity.CENTER);
        pill.setMinWidth(dp(50));
        pill.setMinHeight(dp(36));
        pill.setPadding(dp(12), dp(6), dp(12), dp(6));
        pill.setFocusable(true);
        pill.setOnClickListener(view -> onClick.run());
        return pill;
    }

    private void setThai(boolean useThai) {
        if (thai == useThai) return;
        thai = useThai;
        renderGuide();
    }

    private void updateToggleStyle() {
        applyPillStyle(thButton, thai);
        applyPillStyle(enButton, !thai);
    }

    private void applyPillStyle(TextView pill, boolean active) {
        pill.setBackground(Ui.pressable(this,
                active ? colors.accentWash : android.graphics.Color.TRANSPARENT, 9,
                active ? colors.accent : android.graphics.Color.TRANSPARENT));
        pill.setTextColor(active ? colors.accent : colors.muted);
        pill.setSelected(active);
    }

    private void renderGuide() {
        updateToggleStyle();
        headerTitle.setText(t("คู่มือการใช้งาน", "User Guide"));
        body.removeAllViews();

        // Intro hero banner
        LinearLayout intro = card();
        intro.addView(Ui.text(this,
                t("ยินดีต้อนรับสู่ PenDock", "Welcome to PenDock"),
                18, colors.text, true));
        addTop(intro, Ui.text(this,
                t("เปิดวงเมนูทางลัดข้างปลายปากกา M-Pencil ด้วยการแตะด้านข้างปากกาสองครั้ง — เปิดแอพแบบลอย แบ่งหน้าจอ วงเพื่อค้นหา หรือชี้เลเซอร์ได้จากทุกหน้าจอ",
                        "Double-tap the side of your M-Pencil to open a shortcut ring right next to the pen tip — launch floating or split-screen apps, use Circle to Search, or present with a laser pointer anywhere."),
                14, colors.muted, false), 6);
        PenDoubleTapDemoView demo = new PenDoubleTapDemoView(this, thai);
        LinearLayout.LayoutParams demoParams = new LinearLayout.LayoutParams(-1, dp(164));
        demoParams.topMargin = dp(12);
        intro.addView(demo, demoParams);
        TextView startTourBtn = Ui.action(this,
                t("เริ่มทัวร์แนะนำบนหน้าจอ (GUI)", "Start interactive GUI tour"), true, () -> {
                    Intent intent = new Intent(this, MainActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    intent.putExtra(MainActivity.EXTRA_START_TOUR, true);
                    intent.putExtra(MainActivity.EXTRA_TOUR_THAI, thai);
                    startActivity(intent);
                    finish();
                });
        addTop(intro, startTourBtn, 12);
        addTop(body, intro, 0);

        // Section 1: Quick start
        LinearLayout quick = card();
        quick.addView(sectionHeading(t("1. เริ่มต้นใช้งานใน 3 ขั้นตอน", "1. Quick Start in 3 Steps")));
        addDividerRow(quick, stepRow("1",
                t("เปิดสิทธิ์ที่จำเป็น", "Grant permissions"),
                t("เปิดสิทธิ์ แสดงทับแอพอื่น (Display over other apps) และเปิดบริการ Accessibility ในหมวด Permissions ตามเครื่องมือที่ต้องการใช้",
                        "Enable Display over other apps and turn on the Accessibility services under Permissions for the tools you use.")));
        addDividerRow(quick, stepRow("2",
                t("เลือกแอพและเครื่องมือลงวงเมนู", "Customize your ring"),
                t("แตะ Add app เพื่อติ๊กเลือกแอพและเครื่องมือได้สูงสุด 10 รายการ (เห็นผลบนพรีวิวทันที) และกดค้างที่แถวเพื่อลากจัดลำดับ",
                        "Tap Add app to check or uncheck up to 10 apps and built-in tools with live preview, then press and hold a row to drag-reorder.")));
        addDividerRow(quick, stepRow("3",
                t("เปิดวงเมนูแล้วเริ่มใช้งาน", "Turn on & double-tap"),
                t("แตะปุ่ม Power มุมขวาบน (หรือปุ่ม PenDock ใน Quick Settings) แล้วถือ M-Pencil ใกล้จอ แตะด้านข้างปากกา 2 ครั้งเพื่อเรียกวงเมนู",
                        "Tap the Power button at the top right (or the PenDock Quick Settings tile), hold M-Pencil near the screen, and double-tap its side.")));
        addTop(body, quick, 14);

        // Section 2: Using the ring & customization
        LinearLayout ring = card();
        ring.addView(sectionHeading(t("2. การใช้วงเมนูและการปรับแต่ง", "2. Ring Controls & Appearance")));
        addDividerRow(ring, iconRow(R.drawable.ic_menu_ring,
                t("โฮเวอร์ เลือก และดูแบตปากกา", "Hover, select & check pen battery"),
                t("จ่อปลายปากกาเหนือช่องเพื่อดูชื่อแอพแล้วแตะเพื่อเปิด จ่อตรงวงกลางเพื่อดูแบตเตอรี่ M-Pencil และแตะพื้นที่ว่างหรือดับเบิลแทปซ้ำเพื่อปิดวง",
                        "Hover over any slot to preview its name, then tap to open. Hover the center hub to view M-Pencil battery, and tap outside or double-tap again to dismiss.")));
        addDividerRow(ring, iconRow(R.drawable.ic_appearance,
                t("ปรับหน้าตาได้ 6 สไตล์ สี ธีม + Dim & Blur", "6 styles, ring color, ring theme, dim & blur"),
                t("ใต้ Ring preview สามารถเลือกรูปแบบวงเมนูได้ 6 แบบ (Petals, Orbit, Glass, Hexa, Dial, Minimal), ตั้งธีมวงเมนู (ตามแอพ/สว่าง/มืด) แยกจากธีมแอพ, เลือกสีวงเมนู, ปรับขนาด (เล็ก/ปกติ/ใหญ่), หรี่แสงพื้นหลัง และเบลอพื้นหลังได้ทันที",
                        "Directly below Ring preview, switch between 6 styles (Petals, Orbit, Glass, Hexa, Dial, Minimal), set an independent Ring theme (Follow app / Light / Dark), pick a Ring color, adjust ring size, and fine-tune background dim and blur.")));
        addDividerRow(ring, iconRow(R.drawable.ic_pen,
                t("โหมดปากกาและรูปแบบการเปิดแอพ", "Pen controls & launch modes"),
                t("เลือกได้ว่าจะให้วงเมนูกดได้เฉพาะปากกาหรือใช้นิ้ว/ทัชแพดได้, เปิดเฉพาะเมื่อปากกาใกล้จอ, เริ่มอัตโนมัติหลังรีบูต และตั้งค่าการเปิดแอพทางลัดเป็น Floating, Split screen หรือ Full screen",
                        "Configure pen-only input, near-screen trigger, reboot auto-start, shortcut launch mode (Floating, Split screen, or Full screen), and default floating window size.")));
        addTop(body, ring, 14);

        // Section 3: Built-in tools
        LinearLayout tools = card();
        tools.addView(sectionHeading(t("3. เครื่องมือในตัว (Built-in Tools)", "3. Built-in Ring Tools")));
        addDividerRow(tools, iconRow(R.drawable.ic_float,
                t("Floating window (หน้าต่างลอย)", "Floating window"),
                t("ย่อแอพที่เปิดเต็มจออยู่ลงเป็นหน้าต่างลอยอย่างนุ่มนวล (จดจำขนาดที่คุณเคย resize แยกตามแอพ) และเมื่อแอพกำลังลอยอยู่แล้วแตะไอคอนเดิมบน Home, Dock หรือในวงเมนู หน้าต่างนั้นจะกระพริบแสงสีขาวพร้อม dim รอบนอกเพื่อบอกตำแหน่ง",
                        "Smoothly shrinks the active full-screen app into a floating window (remembering your resized scale per app). Tapping an already-floating app on Home, the dock, or the ring highlights it with a soft white heartbeat glow and spotlight dim.")));
        addDividerRow(tools, iconRow(R.drawable.ic_split,
                t("Split screen (แบ่งหน้าจอ)", "Split screen"),
                t("ย่อแอพปัจจุบันไปครึ่งจอแล้วเปิดหน้าเลือกแอพคู่กัน (มีทั้งจากวงเมนู แอพล่าสุด และช่องค้นหาทันที) รองรับทั้งแนวนอน แนวตั้ง และการหมุนจอ กด Close หรือ Back เพื่อขยายแอพหลักกลับเต็มจอ",
                        "Shrinks the active app to one half and opens a second-app picker with pinned shortcuts, recent apps, and instant search. Adapts across landscape and portrait rotation; tap Close or press Back to expand back to full screen.")));
        addDividerRow(tools, iconRow(R.drawable.ic_circle_search,
                t("Circle to Search (วงเพื่อค้นหา)", "Circle to Search"),
                t("ใช้ปากกาวงพื้นที่ที่ต้องการบนหน้าจอ แล้วเลือกส่งเข้า Google Lens, แชร์, คัดลอก หรือบันทึกลง Pictures/PenDock แตะไอคอนฟันเฟืองที่แถว Circle to Search เพื่อเปิด/ปิดปุ่มเสริมได้ (ปิดครบ 3 ปุ่มจะส่งเข้า Lens ทันที)",
                        "Circle any region on screen with the stylus, then choose Google Lens, Share, Copy, or Save to Pictures/PenDock. Tap the gear icon on Circle to Search in your ring list to toggle Share, Copy, and Save (turn all off for instant Lens).")));
        addDividerRow(tools, iconRow(R.drawable.ic_laser,
                t("Laser pointer (ตัวชี้เลเซอร์)", "Laser pointer"),
                t("เปิดตัวชี้เลเซอร์เต็มจอสำหรับพรีเซนต์งาน มีจุดโฮเวอร์เรืองแสง เส้นลากจางหาย และสลับสีได้ (แดง / เขียว / ฟ้า) ดับเบิลแทปปากกาหรือกด Back/Close เพื่อออก",
                        "Opens a full-screen presentation laser pointer with a glowing hover dot, fading drag trail, and red / green / blue color switching. Double-tap the stylus or press Back / Close to exit.")));
        addDividerRow(tools, iconRow(R.drawable.ic_capture,
                t("Screenshot & Lock screen", "Screenshot & Lock screen"),
                t("สั่งแคปหน้าจอหรือล็อกหน้าจอได้ทันทีจากในวงเมนูโดยไม่ต้องเอื้อมกดปุ่มข้างเครื่อง",
                        "Trigger the system screenshot or lock screen directly from the ring without reaching for hardware buttons.")));
        addTop(body, tools, 14);

        // Section 4: Excluded apps, widget & MatePad tips
        LinearLayout tips = card();
        tips.addView(sectionHeading(t("4. แอพยกเว้น วิดเจ็ต และทิปสำหรับ MatePad", "4. Exclusions, Widget & MatePad Tips")));
        addDividerRow(tips, iconRow(R.drawable.ic_excluded,
                t("Excluded apps (แอพยกเว้น)", "Excluded apps"),
                t("เพิ่มแอพวาดรูปหรือจดโน้ตที่ไม่อยากให้วงเมนูเด้งทับตอนดับเบิลแทปปากกา (ระบบยกเว้น GoPaint, Notes และ Notepad ไว้ให้เป็นค่าเริ่มต้น)",
                        "Add drawing or note-taking apps where you want double-tap to stay native (GoPaint, Notes, and Notepad are excluded by default).")));
        addDividerRow(tips, iconRow(R.drawable.ic_pen,
                t("วิดเจ็ตแบตเตอรี่ปากกา (Pen Battery)", "Pen Battery Widget"),
                t("เพิ่มวิดเจ็ต Pen Battery (Resizable) บนหน้า Home กดค้างแล้วลากขอบเพื่อปรับขนาดเป็น 1×1, สี่เหลี่ยมใหญ่, แนวนอน หรือแนวตั้ง",
                        "Add Pen Battery (Resizable) from the Home widget picker. Press and hold to resize between 1×1, large square, wide, and tall layouts.")));
        addDividerRow(tips, iconRow(R.drawable.ic_info,
                t("กัน HarmonyOS ปิดแอพเบื้องหลัง", "Keep running on HarmonyOS"),
                t("ไปที่ แบตเตอรี่ → Launch manager ปิด Manage automatically ของ PenDock แล้วเปิด Run in background จากนั้นในหน้าแอพล่าสุดให้ปัดการ์ด PenDock ลงเพื่อล็อกไว้",
                        "In Battery → Launch manager, turn off Manage automatically for PenDock and enable Run in background. You can also swipe down on PenDock in Recent tasks to lock it.")));
        addTop(body, tips, 14);
    }

    private TextView sectionHeading(String title) {
        TextView view = Ui.text(this, title, 16, colors.text, true);
        view.setPadding(0, 0, 0, dp(6));
        view.setAccessibilityHeading(true);
        return view;
    }

    private LinearLayout stepRow(String number, String title, String detail) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.TOP);
        row.setPadding(0, dp(10), 0, dp(10));

        TextView badge = Ui.text(this, number, 13, colors.accent, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Ui.shape(this, colors.accentWash, 14));
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(dp(28), dp(28));
        badgeParams.topMargin = dp(2);
        row.addView(badge, badgeParams);

        LinearLayout textCol = Ui.column(this);
        textCol.addView(Ui.text(this, title, 15, colors.text, true));
        addTop(textCol, Ui.text(this, detail, 13, colors.muted, false), 3);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, -2, 1);
        textParams.setMarginStart(dp(12));
        row.addView(textCol, textParams);
        return row;
    }

    private LinearLayout iconRow(int iconRes, String title, String detail) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.TOP);
        row.setPadding(0, dp(10), 0, dp(10));

        FrameLayout badge = new FrameLayout(this);
        badge.setBackground(Ui.shape(this, colors.accentWash, 10));
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(colors.accent));
        badge.addView(icon, new FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER));
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(dp(34), dp(34));
        badgeParams.topMargin = dp(2);
        row.addView(badge, badgeParams);

        LinearLayout textCol = Ui.column(this);
        textCol.addView(Ui.text(this, title, 15, colors.text, true));
        addTop(textCol, Ui.text(this, detail, 13, colors.muted, false), 3);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, -2, 1);
        textParams.setMarginStart(dp(12));
        row.addView(textCol, textParams);
        return row;
    }

    private LinearLayout card() {
        LinearLayout card = Ui.column(this);
        int pad = dp(18);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(Ui.border(this, colors.surface, 18, colors.border));
        return card;
    }

    private void addDividerRow(LinearLayout container, View row) {
        if (container.getChildCount() > 1) {
            View divider = new View(this);
            divider.setBackgroundColor(colors.border);
            container.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        }
        container.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addTop(LinearLayout parent, View child, int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(topDp);
        parent.addView(child, params);
    }

    private String t(String th, String en) {
        return thai ? th : en;
    }

    private int dp(float value) {
        return Ui.dp(this, value);
    }
}
