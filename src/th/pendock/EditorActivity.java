package th.pendock;

import android.app.Activity;
import android.content.Context;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.AtomicFile;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public class EditorActivity extends Activity {
    private static final int[] COLORS = {0xff192635, 0xff176cb0, 0xffe5475d, 0xffffc54f, 0xfffafafa};
    private static final int[] LABELS = {R.string.ink_black, R.string.ink_blue, R.string.ink_red,
            R.string.ink_yellow, R.string.ink_white};
    private Appearance.Palette colors;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(Appearance.localize(base));
    }
    private static final ExecutorService FILES = Executors.newSingleThreadExecutor();
    private AtomicFile draft;
    private InkView sheet;
    private TextView eraser;
    private TextView save;

    @Override public void onCreate(Bundle state) {
        setTheme(Appearance.isDark(this) ? R.style.AppThemeDark : R.style.AppTheme);
        super.onCreate(state);
        colors = Ui.colors(this);
        draft = new AtomicFile(new File(getFilesDir(), "draft.png"));
        Bitmap previous = null;
        try (java.io.FileInputStream input = draft.openRead()) {
            previous = BitmapFactory.decodeStream(input);
        } catch (IOException ignored) { }
        sheet = new InkView(this, previous);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(colors.background);
        int padding = Ui.dp(this, 12);
        root.setPadding(padding, padding, padding, padding);

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(Ui.action(this, getString(R.string.back), false, this::finish));
        TextView title = Ui.text(this, getString(R.string.quick_note), 20, colors.text, true);
        title.setPadding(padding, 0, padding, 0);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        save = Ui.action(this, getString(R.string.save_image), true, this::export);
        heading.addView(save);
        root.addView(heading, new LinearLayout.LayoutParams(-1, -2));

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.addView(Ui.action(this, getString(R.string.undo), false, sheet::undo));
        eraser = Ui.action(this, getString(R.string.eraser), false, this::toggleEraser);
        addTool(tools, eraser);
        addTool(tools, Ui.action(this, getString(R.string.clear_ink), false, () ->
                new android.app.AlertDialog.Builder(this).setMessage(getString(R.string.clear_ink_question))
                        .setNegativeButton(getString(R.string.cancel), null)
                        .setPositiveButton(getString(R.string.clear), (dialog, option) -> sheet.clear()).show()));
        for (int index = 0; index < COLORS.length; index++) {
            final int color = COLORS[index];
            TextView swatch = Ui.action(this, "●", false, () -> {
                sheet.color = color;
                sheet.erase = false;
                updateEraser();
            });
            swatch.setMinWidth(Ui.dp(this, 48));
            swatch.setTextSize(21);
            swatch.setTextColor(color == COLORS[0] ? 0xff101010 : color);
            if (color == COLORS[0]) swatch.setBackground(Ui.shape(this, colors.border, 14));
            swatch.setContentDescription(getString(R.string.ink_color, getString(LABELS[index])));
            addTool(tools, swatch);
        }
        scroll.addView(tools);
        LinearLayout.LayoutParams toolbarParams = new LinearLayout.LayoutParams(-1, -2);
        toolbarParams.topMargin = Ui.dp(this, 8);
        toolbarParams.bottomMargin = Ui.dp(this, 8);
        root.addView(scroll, toolbarParams);
        root.addView(sheet, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void addTool(LinearLayout tools, TextView button) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.leftMargin = Ui.dp(this, 6);
        tools.addView(button, params);
    }

    private void toggleEraser() {
        sheet.erase = !sheet.erase;
        updateEraser();
    }

    private void updateEraser() {
        eraser.setBackground(Ui.shape(this, sheet.erase ? colors.accentWash : colors.surface, 14));
        eraser.setTextColor(sheet.erase ? colors.background : colors.text);
        eraser.setSelected(sheet.erase);
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (sheet != null && PenInput.isButtonPress(event)) {
            toggleEraser();
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override protected void onStop() {
        super.onStop();
        if (sheet == null || !sheet.dirty) return;
        // Commit before another editor instance can read the draft.
        Bitmap snapshot = sheet.copyInk();
        if (snapshot == null) return;
        FileOutputStream output = null;
        try {
            output = draft.startWrite();
            if (!snapshot.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IOException("PNG encoding failed");
            }
            draft.finishWrite(output);
            sheet.dirty = false;
        } catch (IOException failure) {
            if (output != null) draft.failWrite(output);
            Toast.makeText(this, getString(R.string.draft_failed), Toast.LENGTH_LONG).show();
        } finally {
            snapshot.recycle();
        }
    }

    private void export() {
        Bitmap image = sheet.compose();
        if (image == null) return;
        save.setEnabled(false);
        save.setText(getString(R.string.saving));
        String album = "Pictures/PenDock";
        FILES.execute(() -> {
            android.net.Uri uri = null;
            boolean complete = false;
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, "PenDock-" + System.currentTimeMillis() + ".png");
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, album);
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("No media URI");
                try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                    if (output == null || !image.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        throw new IOException("PNG encoding failed");
                    }
                }
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                complete = getContentResolver().update(uri, ready, null, null) > 0;
            } catch (IOException | RuntimeException failure) {
                android.util.Log.w("PenDock", "Export failed", failure);
            } finally {
                image.recycle();
                if (!complete && uri != null) {
                    try { getContentResolver().delete(uri, null, null); }
                    catch (RuntimeException ignored) { }
                }
            }
            boolean saved = complete;
            runOnUiThread(() -> {
                save.setEnabled(true);
                save.setText(getString(R.string.save_image));
                Toast.makeText(this, saved ? getString(R.string.saved_album) : getString(R.string.save_failed),
                        Toast.LENGTH_SHORT).show();
            });
        });
    }
}
