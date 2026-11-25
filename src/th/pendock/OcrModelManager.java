package th.pendock;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/** Manages on-demand downloading, SHA-256 verification, and storage of PaddleOCR PP-OCRv5 ONNX models. */
final class OcrModelManager {
    private static final String BASE_URL =
            "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.10.0/onnx/PP-OCRv5/";
    private static final String DET_FILE = "ch_PP-OCRv5_det_mobile.onnx";
    private static final String DET_URL = BASE_URL + "det/" + DET_FILE;
    private static final String DET_SHA256 =
            "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae";
    private static final long DET_EXPECTED_BYTES = 4_800_000L;

    private static final String PREF_PACK = "ocr_pack";
    private static final String PREF_TARGET_LANG = "translate_target_lang";
    private static final String PREF_MODE = "translate_mode";

    enum Pack {
        EN("en", "EN", R.string.ocr_pack_en,
                "en_PP-OCRv5_rec_mobile.onnx",
                BASE_URL + "rec/en_PP-OCRv5_rec_mobile.onnx",
                "c3461add59bb4323ecba96a492ab75e06dda42467c9e3d0c18db5d1d21924be8",
                7_850_000L, "en"),
        CJK("cjk", "中/日/EN", R.string.ocr_pack_cjk,
                "ch_PP-OCRv5_rec_mobile.onnx",
                BASE_URL + "rec/ch_PP-OCRv5_rec_mobile.onnx",
                "5825fc7ebf84ae7a412be049820b4d86d77620f204a041697b0494669b1742c5",
                16_500_000L, "auto"),
        KO("ko", "KR", R.string.ocr_pack_ko,
                "korean_PP-OCRv5_rec_mobile.onnx",
                BASE_URL + "rec/korean_PP-OCRv5_rec_mobile.onnx",
                "cd6e2ea50f6943ca7271eb8c56a877a5a90720b7047fe9c41a2e541a25773c9b",
                13_800_000L, "ko"),
        TH("th", "ไทย", R.string.ocr_pack_th,
                "th_PP-OCRv5_rec_mobile.onnx",
                BASE_URL + "rec/th_PP-OCRv5_rec_mobile.onnx",
                "de541dd83161c241ff426f7ecfd602a0ba77d686cf3ab9a6c255ea82fd08006e",
                7_900_000L, "th");

        final String id;
        final String badge;
        final int labelRes;
        final String fileName;
        final String url;
        final String sha256;
        final long expectedBytes;
        final String sourceLangHint;

        Pack(String id, String badge, int labelRes, String fileName, String url, String sha256,
                long expectedBytes, String sourceLangHint) {
            this.id = id;
            this.badge = badge;
            this.labelRes = labelRes;
            this.fileName = fileName;
            this.url = url;
            this.sha256 = sha256;
            this.expectedBytes = expectedBytes;
            this.sourceLangHint = sourceLangHint;
        }

        static Pack byId(String id) {
            for (Pack pack : values()) {
                if (pack.id.equals(id)) return pack;
            }
            return EN;
        }
    }

    interface ProgressListener {
        void onProgress(int percent, long downloadedBytes, long totalBytes);
    }

    private OcrModelManager() { }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("tools", Context.MODE_PRIVATE);
    }

    static File ocrDir(Context context) {
        File dir = new File(context.getFilesDir(), "ocr");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    static File detFile(Context context) {
        return new File(ocrDir(context), DET_FILE);
    }

    static File recFile(Context context, Pack pack) {
        return new File(ocrDir(context), pack.fileName);
    }

    static boolean isDetectorReady(Context context) {
        File f = detFile(context);
        return f.isFile() && f.length() > 100_000L;
    }

    static boolean isPackReady(Context context, Pack pack) {
        if (!isDetectorReady(context)) return false;
        File f = recFile(context, pack);
        return f.isFile() && f.length() > 100_000L;
    }

    static boolean anyPackReady(Context context) {
        for (Pack pack : Pack.values()) {
            if (isPackReady(context, pack)) return true;
        }
        return false;
    }

    static Pack selectedPack(Context context) {
        Pack chosen = Pack.byId(prefs(context).getString(PREF_PACK, Pack.EN.id));
        if (isPackReady(context, chosen)) return chosen;
        for (Pack pack : Pack.values()) {
            if (isPackReady(context, pack)) return pack;
        }
        return chosen;
    }

    static void setSelectedPack(Context context, Pack pack) {
        prefs(context).edit().putString(PREF_PACK, pack.id).apply();
    }

    static String targetLang(Context context) {
        String lang = prefs(context).getString(PREF_TARGET_LANG, "th");
        return lang == null || lang.isEmpty() ? "th" : lang;
    }

    static void setTargetLang(Context context, String lang) {
        prefs(context).edit().putString(PREF_TARGET_LANG, lang).apply();
    }

    static String lastMode(Context context) {
        String mode = prefs(context).getString(PREF_MODE, "box");
        if ("point".equals(mode) || "full".equals(mode)) return mode;
        return "box";
    }

    static void setLastMode(Context context, String mode) {
        prefs(context).edit().putString(PREF_MODE, mode).apply();
    }

    static long estimatedDownloadBytes(Context context, Pack pack) {
        long bytes = isPackReady(context, pack) ? 0L : pack.expectedBytes;
        if (!isDetectorReady(context)) bytes += DET_EXPECTED_BYTES;
        return bytes;
    }

    static long packInstalledBytes(Context context, Pack pack) {
        File f = recFile(context, pack);
        return f.isFile() ? f.length() : 0L;
    }

    static long totalInstalledBytes(Context context) {
        long total = 0L;
        File dir = ocrDir(context);
        File[] files = dir.listFiles();
        if (files == null) return 0L;
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".onnx")) total += f.length();
        }
        return total;
    }

    static String formatMb(long bytes) {
        return String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f));
    }

    static void deletePack(Context context, Pack pack) {
        ScreenOcrEngine.releaseIfLoaded(pack);
        File f = recFile(context, pack);
        if (f.exists()) f.delete();
        boolean anyLeft = false;
        for (Pack p : Pack.values()) {
            File rf = recFile(context, p);
            if (rf.isFile() && rf.length() > 100_000L) {
                anyLeft = true;
                break;
            }
        }
        if (!anyLeft) {
            ScreenOcrEngine.releaseAll();
            File det = detFile(context);
            if (det.exists()) det.delete();
        }
    }

    /** Downloads detector (if needed) and the requested language pack, verifying SHA-256 digests. */
    static void downloadPack(Context context, Pack pack, ProgressListener listener) throws IOException {
        boolean needDet = !isDetectorReady(context);
        boolean needRec = !(recFile(context, pack).isFile() && recFile(context, pack).length() > 100_000L);
        long expectedTotal = (needDet ? DET_EXPECTED_BYTES : 0L) + (needRec ? pack.expectedBytes : 0L);
        long[] completedBase = {0L};

        if (needDet) {
            downloadFile(DET_URL, detFile(context), DET_SHA256, DET_EXPECTED_BYTES, (done, total) -> {
                long grandTotal = Math.max(expectedTotal, total + (needRec ? pack.expectedBytes : 0L));
                notifyProgress(listener, done, grandTotal);
            });
            completedBase[0] = detFile(context).length();
        }
        if (needRec) {
            long detActual = needDet ? completedBase[0] : 0L;
            downloadFile(pack.url, recFile(context, pack), pack.sha256, pack.expectedBytes, (done, total) -> {
                long grandTotal = Math.max(detActual + total, detActual + pack.expectedBytes);
                notifyProgress(listener, detActual + done, grandTotal);
            });
        }
        notifyProgress(listener, Math.max(1L, expectedTotal), Math.max(1L, expectedTotal));
        setSelectedPack(context, pack);
    }

    private interface FileProgress {
        void onBytes(long done, long total);
    }

    private static void notifyProgress(ProgressListener listener, long done, long total) {
        if (listener == null) return;
        int pct = total > 0 ? (int) Math.min(99, Math.max(0, (done * 100L) / total)) : 0;
        if (done >= total && total > 0) pct = 100;
        listener.onProgress(pct, done, total);
    }

    private static void downloadFile(String urlString, File dest, String expectedSha256,
            long fallbackTotal, FileProgress progress) throws IOException {
        File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
        if (tmp.exists()) tmp.delete();
        HttpURLConnection conn = openWithRedirects(urlString);
        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code + " downloading " + dest.getName());
            }
            long contentLength = conn.getContentLengthLong();
            long total = contentLength > 0 ? contentLength : fallbackTotal;
            MessageDigest sha;
            try {
                sha = MessageDigest.getInstance("SHA-256");
            } catch (Exception impossible) {
                throw new IOException(impossible);
            }
            byte[] buf = new byte[32768];
            long written = 0L;
            long lastNotify = 0L;
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(tmp)) {
                int read;
                while ((read = in.read(buf)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("Download cancelled");
                    }
                    out.write(buf, 0, read);
                    sha.update(buf, 0, read);
                    written += read;
                    long now = System.currentTimeMillis();
                    if (now - lastNotify >= 60L) {
                        lastNotify = now;
                        if (progress != null) progress.onBytes(written, Math.max(total, written));
                    }
                }
                out.getFD().sync();
            }
            String actualHex = toHex(sha.digest());
            if (expectedSha256 != null && !expectedSha256.isEmpty()
                    && !expectedSha256.equalsIgnoreCase(actualHex)) {
                tmp.delete();
                throw new IOException("Checksum mismatch for " + dest.getName());
            }
            if (dest.exists() && !dest.delete()) {
                tmp.delete();
                throw new IOException("Could not replace " + dest.getName());
            }
            if (!tmp.renameTo(dest)) {
                tmp.delete();
                throw new IOException("Could not finalize " + dest.getName());
            }
            if (progress != null) progress.onBytes(written, written);
        } catch (IOException error) {
            if (tmp.exists()) tmp.delete();
            throw error;
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection openWithRedirects(String urlString) throws IOException {
        String current = urlString;
        for (int hops = 0; hops < 6; hops++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(12_000);
            conn.setReadTimeout(25_000);
            conn.setRequestProperty("User-Agent", "PenDock-Android/2.0");
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isEmpty()) throw new IOException("Empty redirect");
                current = new URL(new URL(current), loc).toString();
                continue;
            }
            return conn;
        }
        throw new IOException("Too many redirects");
    }

    private static String toHex(byte[] bytes) {
        char[] hex = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            out[i * 2] = hex[v >>> 4];
            out[i * 2 + 1] = hex[v & 0x0f];
        }
        return new String(out);
    }
}
