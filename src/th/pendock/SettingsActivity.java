package th.pendock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends Activity {
    private static final String SUPPORT_EMAIL = "gigabeat.is@gmail.com";
    private Appearance.Palette colors;
    private final ExecutorService updates = Executors.newSingleThreadExecutor();
    private TextView checkUpdateButton;
    private TextView updateStatus;
    private boolean checkingUpdate;
    private String installedVersion = "";

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(Appearance.localize(base));
    }

    @Override protected void onCreate(Bundle state) {
        setTheme(Appearance.isDark(this) ? R.style.AppThemeDark : R.style.AppTheme);
        super.onCreate(state);
        colors = Ui.colors(this);
        getWindow().setStatusBarColor(colors.background);
        getWindow().setNavigationBarColor(colors.background);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(colors.background);
        FrameLayout shell = new FrameLayout(this);
        scroll.addView(shell, new ScrollView.LayoutParams(-1, -2));
        LinearLayout page = Ui.column(this);
        int width = Math.min(getResources().getConfiguration().screenWidthDp, 780);
        shell.addView(page, new FrameLayout.LayoutParams(Ui.dp(this, width), -2, Gravity.CENTER_HORIZONTAL));
        int padding = Ui.dp(this, 18);
        page.setPadding(padding, padding, padding, padding * 2);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int touchTarget = Ui.dp(this, 48);
        header.addView(Ui.iconAction(this, R.drawable.ic_back, R.string.back, this::finish),
                new LinearLayout.LayoutParams(touchTarget, touchTarget));
        TextView title = Ui.text(this, getString(R.string.settings), 23, colors.text, true);
        title.setPadding(Ui.dp(this, 12), 0, 0, 0);
        title.setAccessibilityHeading(true);
        header.addView(title);
        page.addView(header);
        add(page, aboutCard(), 14);

        LinearLayout preferences = card();
        addDropdown(preferences, R.string.theme, Appearance.THEME, Appearance.theme(this),
                new String[] {getString(R.string.theme_system), getString(R.string.theme_light), getString(R.string.theme_dark)},
                new String[] {Appearance.SYSTEM, Appearance.LIGHT, Appearance.DARK});
        View divider = new View(this);
        divider.setBackgroundColor(colors.border);
        preferences.addView(divider, new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));
        addDropdown(preferences, R.string.language, Appearance.LANGUAGE, Appearance.language(this),
                new String[] {"ภาษาไทย", "English"}, new String[] {"th", "en"});
        add(page, preferences, 14);
        add(page, ocrModelsCard(), 14);
        add(page, termsCard(), 14);
        add(page, contactCard(), 14);
        setContentView(scroll);
    }

    private LinearLayout aboutCard() {
        LinearLayout about = card();
        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher_ring);
        int size = Ui.dp(this, 56);
        brand.addView(logo, new LinearLayout.LayoutParams(size, size));
        LinearLayout label = Ui.column(this);
        label.setPadding(Ui.dp(this, 12), 0, 0, 0);
        label.addView(Ui.text(this, "PenDock", 20, colors.text, true));
        try {
            installedVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            label.addView(Ui.text(this, getString(R.string.app_version, installedVersion), 13, colors.muted, false));
        } catch (PackageManager.NameNotFoundException ignored) { }
        brand.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        about.addView(brand);
        add(about, Ui.text(this, getString(R.string.app_description), 14, colors.muted, false), 12);
        updateStatus = Ui.text(this, "", 13, colors.muted, false);
        updateStatus.setVisibility(View.GONE);
        add(about, updateStatus, 10);
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        TextView guideButton = Ui.action(this, getString(R.string.user_guide), false,
                () -> startActivity(new Intent(this, GuideActivity.class)));
        actions.addView(guideButton, new LinearLayout.LayoutParams(-2, -2));
        checkUpdateButton = Ui.action(this, getString(R.string.check_updates), false, this::checkForUpdates);
        LinearLayout.LayoutParams updateParams = new LinearLayout.LayoutParams(-2, -2);
        updateParams.setMarginStart(Ui.dp(this, 8));
        actions.addView(checkUpdateButton, updateParams);
        add(about, actions, 12);
        return about;
    }

    private void checkForUpdates() {
        if (checkingUpdate || updates.isShutdown()) return;
        checkingUpdate = true;
        checkUpdateButton.setEnabled(false);
        checkUpdateButton.setText(R.string.checking_updates);
        updateStatus.setVisibility(View.GONE);
        String current = installedVersion;
        updates.execute(() -> {
            try {
                UpdateChecker.Release latest = UpdateChecker.fetchLatest(current);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    finishUpdateCheck();
                    if (UpdateChecker.isNewer(latest.version, current)) {
                        showUpdateDialog(latest);
                    } else {
                        updateStatus.setTextColor(colors.pick(0xff1b624e, 0xffa0e1c8));
                        updateStatus.setText(getString(R.string.update_up_to_date,
                                current.isEmpty() ? latest.version : current));
                        updateStatus.setVisibility(View.VISIBLE);
                    }
                });
            } catch (IOException | RuntimeException failure) {
                android.util.Log.w("PenDock", "Update check failed", failure);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    finishUpdateCheck();
                    updateStatus.setTextColor(colors.pick(0xffad293c, 0xffff9eaa));
                    updateStatus.setText(R.string.update_check_failed);
                    updateStatus.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void finishUpdateCheck() {
        checkingUpdate = false;
        checkUpdateButton.setEnabled(true);
        checkUpdateButton.setText(R.string.check_updates);
    }

    private void showUpdateDialog(UpdateChecker.Release release) {
        String current = installedVersion.isEmpty() ? "—" : installedVersion;
        StringBuilder message = new StringBuilder(
                getString(R.string.update_available_summary, current, release.version));
        if (!release.notes.isEmpty()) {
            message.append("\n\n").append(release.notes);
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.update_available_title)
                .setMessage(message.toString())
                .setNegativeButton(R.string.close, null);
        if (release.apkUrl != null) {
            dialog.setPositiveButton(R.string.update_download_apk, (d, which) -> openUrl(release.apkUrl));
            dialog.setNeutralButton(R.string.update_open_github, (d, which) -> openUrl(release.pageUrl));
        } else {
            dialog.setPositiveButton(R.string.update_open_github, (d, which) -> openUrl(release.pageUrl));
        }
        dialog.show();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException unavailable) {
            Toast.makeText(this, R.string.browser_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onDestroy() {
        updates.shutdownNow();
        super.onDestroy();
    }

    private void addDropdown(LinearLayout preferences, int title, String key, String current,
            String[] labels, String[] values) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(this, 57));
        TextView label = Ui.text(this, getString(title), 16, colors.text, true);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        Spinner spinner = new Spinner(this, Spinner.MODE_DROPDOWN);
        spinner.setContentDescription(getString(title));
        spinner.setPopupBackgroundDrawable(Ui.border(this, colors.surface, 12, colors.border));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        int selected = java.util.Arrays.asList(values).indexOf(current);
        spinner.setSelection(Math.max(0, selected));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == Math.max(0, selected)) return;
                Appearance.preferences(SettingsActivity.this).edit().putString(key, values[position]).apply();
                recreate();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        int width = getResources().getConfiguration().screenWidthDp;
        row.addView(spinner, new LinearLayout.LayoutParams(Ui.dp(this, width < 400 ? 140 : 190), Ui.dp(this, 48)));
        preferences.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private LinearLayout ocrModelsCard() {
        LinearLayout card = card();
        card.addView(heading(R.string.ocr_models_title));
        add(card, Ui.text(this, getString(R.string.ocr_models_intro), 13, colors.muted, false), 6);

        // Default target language dropdown
        LinearLayout langRow = new LinearLayout(this);
        langRow.setGravity(Gravity.CENTER_VERTICAL);
        langRow.setMinimumHeight(Ui.dp(this, 52));
        langRow.addView(Ui.text(this, getString(R.string.ocr_target_language), 15, colors.text, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        Spinner targetSpinner = new Spinner(this, Spinner.MODE_DROPDOWN);
        targetSpinner.setPopupBackgroundDrawable(Ui.border(this, colors.surface, 12, colors.border));
        ArrayAdapter<String> targetAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, GoogleTranslator.TARGET_BADGES);
        targetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        targetSpinner.setAdapter(targetAdapter);
        String curTarget = OcrModelManager.targetLang(this);
        int selIdx = Math.max(0, java.util.Arrays.asList(GoogleTranslator.TARGET_CODES).indexOf(curTarget));
        targetSpinner.setSelection(selIdx);
        targetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                OcrModelManager.setTargetLang(SettingsActivity.this, GoogleTranslator.TARGET_CODES[position]);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        langRow.addView(targetSpinner, new LinearLayout.LayoutParams(Ui.dp(this, 130), Ui.dp(this, 44)));
        add(card, langRow, 8);

        TextView storageLabel = Ui.text(this, "", 12, colors.muted, false);
        add(card, storageLabel, 4);

        LinearLayout list = Ui.column(this);
        add(card, list, 8);

        Runnable[] rebuild = new Runnable[1];
        boolean[] busy = {false};
        rebuild[0] = () -> {
            long totalBytes = OcrModelManager.totalInstalledBytes(this);
            storageLabel.setText(getString(R.string.ocr_storage_used, OcrModelManager.formatMb(totalBytes)));
            storageLabel.setVisibility(totalBytes > 0 ? View.VISIBLE : View.GONE);
            list.removeAllViews();
            for (OcrModelManager.Pack pack : OcrModelManager.Pack.values()) {
                boolean ready = OcrModelManager.isPackReady(this, pack);
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
                row.setBackground(Ui.border(this, colors.rail, 12, colors.border));

                LinearLayout info = Ui.column(this);
                info.addView(Ui.text(this, getString(pack.labelRes), 14, colors.text, true));
                long bytes = ready ? OcrModelManager.packInstalledBytes(this, pack)
                        : OcrModelManager.estimatedDownloadBytes(this, pack);
                TextView detail = Ui.text(this,
                        (ready ? getString(R.string.ocr_installed) + " · " : "")
                                + OcrModelManager.formatMb(bytes),
                        12, colors.muted, false);
                info.addView(detail);
                row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

                TextView btn = Ui.action(this,
                        getString(ready ? R.string.ocr_delete : R.string.ocr_download), !ready, () -> { });
                btn.setOnClickListener(v -> {
                    if (busy[0]) return;
                    if (OcrModelManager.isPackReady(this, pack)) {
                        OcrModelManager.deletePack(this, pack);
                        rebuild[0].run();
                        return;
                    }
                    busy[0] = true;
                    btn.setEnabled(false);
                    btn.setText(getString(R.string.ocr_downloading, 0));
                    updates.execute(() -> {
                        try {
                            OcrModelManager.downloadPack(this, pack, (pct, done, total) ->
                                    runOnUiThread(() -> {
                                        if (!isFinishing() && !isDestroyed()) {
                                            btn.setText(getString(R.string.ocr_downloading, pct));
                                        }
                                    }));
                            runOnUiThread(() -> {
                                busy[0] = false;
                                if (!isFinishing() && !isDestroyed()) rebuild[0].run();
                            });
                        } catch (Exception err) {
                            android.util.Log.w("PenDock", "OCR download failed", err);
                            runOnUiThread(() -> {
                                busy[0] = false;
                                if (!isFinishing() && !isDestroyed()) {
                                    Toast.makeText(this, R.string.ocr_download_failed, Toast.LENGTH_LONG).show();
                                    rebuild[0].run();
                                }
                            });
                        }
                    });
                });
                row.addView(btn, new LinearLayout.LayoutParams(-2, -2));

                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                if (list.getChildCount() > 0) rp.topMargin = Ui.dp(this, 8);
                list.addView(row, rp);
            }
        };
        rebuild[0].run();
        return card;
    }

    private LinearLayout termsCard() {
        LinearLayout terms = card();
        terms.addView(heading(R.string.terms_title));
        term(terms, R.string.terms_use, R.string.terms_use_detail);
        term(terms, R.string.terms_license, R.string.terms_license_detail);
        term(terms, R.string.terms_compatibility, R.string.terms_compatibility_detail);
        term(terms, R.string.terms_data, R.string.terms_data_detail);
        add(terms, Ui.text(this, getString(R.string.terms_contact_detail), 14, colors.muted, false), 10);
        TextView notices = Ui.action(this, getString(R.string.license_action), false, this::showLicense);
        notices.setBackground(Ui.pressable(this, colors.surface, 12, colors.surface));
        add(terms, notices, 9);
        return terms;
    }

    private void showLicense() {
        StringBuilder license = new StringBuilder();
        try (InputStreamReader reader = new InputStreamReader(
                getResources().openRawResource(R.raw.pendock_license), StandardCharsets.UTF_8)) {
            char[] buffer = new char[1024];
            int length;
            while ((length = reader.read(buffer)) != -1) {
                license.append(buffer, 0, length);
            }
        } catch (IOException unavailable) {
            Toast.makeText(this, R.string.license_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle(R.string.license_action)
                .setMessage(license.toString().trim()).setPositiveButton(R.string.close, null).show();
    }

    private LinearLayout contactCard() {
        LinearLayout contact = card();
        contact.addView(heading(R.string.contact_developer));
        add(contact, Ui.text(this, getString(R.string.contact_detail), 14, colors.muted, false), 5);
        TextView email = Ui.text(this, SUPPORT_EMAIL, 14, colors.accent, false);
        email.setTextIsSelectable(true);
        add(contact, email, 6);
        add(contact, Ui.action(this, getString(R.string.contact_email), true, this::composeEmail), 10);
        return contact;
    }

    private void composeEmail() {
        Uri mail = Uri.parse("mailto:" + SUPPORT_EMAIL + "?subject=" + Uri.encode("PenDock"));
        try {
            startActivity(new Intent(Intent.ACTION_SENDTO, mail));
        } catch (ActivityNotFoundException unavailable) {
            Toast.makeText(this, R.string.email_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private void term(LinearLayout terms, int title, int description) {
        add(terms, Ui.text(this, getString(title), 15, colors.text, true), 14);
        add(terms, Ui.text(this, getString(description), 14, colors.muted, false), 3);
    }

    private TextView heading(int text) {
        TextView heading = Ui.text(this, getString(text), 18, colors.text, true);
        heading.setAccessibilityHeading(true);
        return heading;
    }

    private LinearLayout card() {
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.border(this, colors.surface, 17, colors.border));
        int padding = Ui.dp(this, 18);
        card.setPadding(padding, padding, padding, padding);
        return card;
    }

    private void add(LinearLayout parent, View child, int gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2);
        params.topMargin = Ui.dp(this, gap);
        parent.addView(child, params);
    }
}
