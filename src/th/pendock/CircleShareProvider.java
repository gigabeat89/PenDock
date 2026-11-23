package th.pendock;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Standard grantable ContentProvider for sharing cropped Circle to Search images. */
public class CircleShareProvider extends ContentProvider {
    private static final String[] DEFAULT_COLUMNS = {
        OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, "mime_type"
    };

    static Uri uriFor(Context context, String fileName) {
        return new Uri.Builder()
                .scheme("content")
                .authority(context.getPackageName() + ".share")
                .appendPath(fileName)
                .build();
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        return fileName(uri) == null ? null : "image/png";
    }

    @Override public String[] getStreamTypes(Uri uri, String mimeTypeFilter) {
        if (fileName(uri) == null) return null;
        if (mimeTypeFilter == null || "*/*".equals(mimeTypeFilter)
                || "image/*".equals(mimeTypeFilter) || "image/png".equals(mimeTypeFilter)) {
            return new String[] {"image/png"};
        }
        return null;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        File file = resolve(uri);
        String[] cols = projection == null ? DEFAULT_COLUMNS : projection;
        if (file == null) return new MatrixCursor(cols);
        MatrixCursor cursor = new MatrixCursor(cols, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String col : cols) {
            if (OpenableColumns.DISPLAY_NAME.equals(col)) row.add("PenDock.png");
            else if (OpenableColumns.SIZE.equals(col)) row.add(file.length());
            else if ("mime_type".equals(col)) row.add("image/png");
            else row.add(null);
        }
        return cursor;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode == null || !mode.startsWith("r")) throw new FileNotFoundException("Read-only");
        File file = resolve(uri);
        if (file == null) throw new FileNotFoundException("Image not found");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        return openAssetFile(uri, mode, null);
    }

    @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (mode == null || !mode.startsWith("r")) throw new FileNotFoundException("Read-only");
        File file = resolve(uri);
        if (file == null) throw new FileNotFoundException("Image not found");
        ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        return new AssetFileDescriptor(fd, 0, file.length());
    }

    @Override public AssetFileDescriptor openTypedAssetFile(Uri uri, String mimeTypeFilter, Bundle opts,
            CancellationSignal signal) throws FileNotFoundException {
        return openAssetFile(uri, "r", signal);
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }

    private String fileName(Uri uri) {
        if (uri == null) return null;
        String segment = uri.getLastPathSegment();
        if (segment == null || !segment.matches("capture-[0-9]+\\.png")) return null;
        return segment;
    }

    private File resolve(Uri uri) {
        String name = fileName(uri);
        Context context = getContext();
        if (name == null || context == null) return null;
        return CircleFiles.resolveValid(context, name);
    }
}
