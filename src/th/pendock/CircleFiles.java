package th.pendock;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

public class CircleFiles extends DocumentsProvider {
    private static final String ROOT = "circles";
    private static final String DIRECTORY = "circle-images";
    private static final long RETENTION_MS = 24L * 60L * 60L * 1000L;
    private static final int MAX_CROPS = 8;
    private static final String[] ROOT_COLUMNS = {
        Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS,
        Root.COLUMN_ICON, Root.COLUMN_MIME_TYPES
    };
    private static final String[] FILE_COLUMNS = {
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED
    };

    static Uri save(Context context, Bitmap bitmap) throws IOException {
        File directory = directory(context);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create PenDock image directory");
        }
        File temporary = File.createTempFile("capture-", ".tmp", directory);
        File image = new File(directory, temporary.getName().replace(".tmp", ".png"));
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new IOException("Cannot encode the selected image");
                }
            }
            if (!temporary.renameTo(image)) throw new IOException("Cannot finish saving the image");
        } finally {
            temporary.delete();
        }
        File[] files = images(context);
        for (int i = 0; i < files.length; i++) {
            if (i >= MAX_CROPS || System.currentTimeMillis() - files[i].lastModified() > RETENTION_MS) {
                context.revokeUriPermission(DocumentsContract.buildDocumentUri(authority(context),
                        files[i].getName()), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                context.revokeUriPermission(CircleShareProvider.uriFor(context, files[i].getName()),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
                files[i].delete();
            }
        }
        context.getContentResolver().notifyChange(
                DocumentsContract.buildChildDocumentsUri(authority(context), ROOT), null);
        return CircleShareProvider.uriFor(context, image.getName());
    }

    static File resolveValid(Context context, String id) {
        if (id == null || !id.matches("capture-[0-9]+\\.png")) return null;
        File image = new File(directory(context), id);
        if (!image.isFile() || System.currentTimeMillis() - image.lastModified() > RETENTION_MS) return null;
        return image;
    }

    private static String authority(Context context) { return context.getPackageName() + ".circles"; }

    private static File directory(Context context) {
        return new File(context.getCacheDir(), DIRECTORY);
    }

    private static File[] images(Context context) {
        File[] files = directory(context).listFiles(file -> file.getName().matches("capture-[0-9]+\\.png"));
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        return files;
    }

    @Override public boolean onCreate() { return true; }

    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor result = new MatrixCursor(projection == null ? ROOT_COLUMNS : projection);
        result.newRow().add(Root.COLUMN_ROOT_ID, ROOT).add(Root.COLUMN_DOCUMENT_ID, ROOT)
                .add(Root.COLUMN_TITLE, "PenDock")
                .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_RECENTS)
                .add(Root.COLUMN_ICON, R.drawable.ic_circle_search)
                .add(Root.COLUMN_MIME_TYPES, "image/png");
        return result;
    }

    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(projection == null ? FILE_COLUMNS : projection);
        if (ROOT.equals(id)) {
            result.newRow().add(Document.COLUMN_DOCUMENT_ID, ROOT).add(Document.COLUMN_DISPLAY_NAME, "PenDock")
                    .add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR).add(Document.COLUMN_FLAGS, 0);
        } else {
            addFile(result, file(id), "PenDock.png");
        }
        return result;
    }

    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder)
            throws FileNotFoundException {
        return fileList(parent, projection);
    }

    @Override public Cursor queryRecentDocuments(String root, String[] projection)
            throws FileNotFoundException {
        return fileList(root, projection);
    }

    private Cursor fileList(String root, String[] projection) throws FileNotFoundException {
        if (!ROOT.equals(root)) throw new FileNotFoundException(root);
        MatrixCursor result = new MatrixCursor(projection == null ? FILE_COLUMNS : projection);
        int position = 0;
        for (File image : images(getContext())) {
            if (System.currentTimeMillis() - image.lastModified() > RETENTION_MS) continue;
            String label = position == 0 ? Appearance.text(getContext(), R.string.latest_image) : "PenDock " + (position + 1) + ".png";
            addFile(result, image, label);
            position++;
        }
        return result;
    }

    private void addFile(MatrixCursor rows, File image, String label) {
        rows.newRow().add(Document.COLUMN_DOCUMENT_ID, image.getName()).add(Document.COLUMN_DISPLAY_NAME, label)
                .add(Document.COLUMN_MIME_TYPE, "image/png").add(Document.COLUMN_FLAGS, 0)
                .add(Document.COLUMN_SIZE, image.length()).add(Document.COLUMN_LAST_MODIFIED, image.lastModified());
    }

    private File file(String id) throws FileNotFoundException {
        if (id == null || !id.matches("capture-[0-9]+\\.png")) throw new FileNotFoundException();
        File image = new File(directory(getContext()), id);
        if (!image.isFile() || System.currentTimeMillis() - image.lastModified() > RETENTION_MS) {
            throw new FileNotFoundException();
        }
        return image;
    }

    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
