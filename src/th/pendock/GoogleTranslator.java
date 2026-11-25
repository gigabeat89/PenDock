package th.pendock;

import android.util.LruCache;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;

/** Lightweight HTTP Google Translate client with LRU caching and batching (no GMS or API key required). */
final class GoogleTranslator {
    static final String[] TARGET_CODES = {"th", "en", "ja", "zh-CN", "ko"};
    static final String[] TARGET_BADGES = {"ไทย", "EN", "日本語", "中文", "한국어"};

    private static final String SEP = "\n⟪PD⟫\n";
    private static final LruCache<String, String> CACHE = new LruCache<>(320);

    private GoogleTranslator() { }

    static String targetBadge(String code) {
        for (int i = 0; i < TARGET_CODES.length; i++) {
            if (TARGET_CODES[i].equals(code)) return TARGET_BADGES[i];
        }
        return "ไทย";
    }

    static String nextTarget(String current) {
        for (int i = 0; i < TARGET_CODES.length; i++) {
            if (TARGET_CODES[i].equals(current)) {
                return TARGET_CODES[(i + 1) % TARGET_CODES.length];
            }
        }
        return TARGET_CODES[0];
    }

    static String translate(String text, String sourceLang, String targetLang) throws IOException {
        if (text == null) return "";
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return "";
        String sl = (sourceLang == null || sourceLang.isEmpty()) ? "auto" : sourceLang;
        String tl = (targetLang == null || targetLang.isEmpty()) ? "th" : targetLang;
        String key = sl + ">" + tl + "|" + trimmed;
        String cached = CACHE.get(key);
        if (cached != null) return cached;

        String result = requestTranslate(trimmed, sl, tl);
        if (!result.isEmpty()) CACHE.put(key, result);
        return result;
    }

    static String getCached(String text, String sourceLang, String targetLang) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return "";
        String sl = (sourceLang == null || sourceLang.isEmpty()) ? "auto" : sourceLang;
        String tl = (targetLang == null || targetLang.isEmpty()) ? "th" : targetLang;
        return CACHE.get(sl + ">" + tl + "|" + trimmed);
    }

    /** Translates a list of blocks efficiently in batched HTTP requests with automatic fallback. */
    static List<String> translateBatch(List<String> texts, String sourceLang, String targetLang)
            throws IOException {
        int n = texts.size();
        List<String> output = new ArrayList<>(n);
        List<Integer> missingIndices = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String t = texts.get(i) == null ? "" : texts.get(i).trim();
            String cached = getCached(t, sourceLang, targetLang);
            if (cached != null) {
                output.add(cached);
            } else {
                output.add("");
                if (!t.isEmpty()) missingIndices.add(i);
            }
        }
        if (missingIndices.isEmpty()) return output;

        // Chunk missing texts into batches of <= 10 items and <= 1800 chars so URLs stay compact.
        int cursor = 0;
        while (cursor < missingIndices.size()) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
            List<Integer> batchIdx = new ArrayList<>();
            StringBuilder joined = new StringBuilder();
            while (cursor < missingIndices.size() && batchIdx.size() < 10) {
                int idx = missingIndices.get(cursor);
                String item = texts.get(idx).trim();
                if (!batchIdx.isEmpty() && joined.length() + item.length() + SEP.length() > 1600) break;
                if (!batchIdx.isEmpty()) joined.append(SEP);
                joined.append(item);
                batchIdx.add(idx);
                cursor++;
            }
            if (batchIdx.size() == 1) {
                int idx = batchIdx.get(0);
                String translated = translate(texts.get(idx), sourceLang, targetLang);
                output.set(idx, translated);
            } else {
                String combined = requestTranslate(joined.toString(),
                        (sourceLang == null || sourceLang.isEmpty()) ? "auto" : sourceLang,
                        (targetLang == null || targetLang.isEmpty()) ? "th" : targetLang);
                String[] parts = combined.split("\\s*⟪\\s*PD\\s*⟫\\s*", -1);
                if (parts.length == batchIdx.size()) {
                    for (int k = 0; k < batchIdx.size(); k++) {
                        int idx = batchIdx.get(k);
                        String translated = parts[k].trim();
                        output.set(idx, translated);
                        String sl = (sourceLang == null || sourceLang.isEmpty()) ? "auto" : sourceLang;
                        String tl = (targetLang == null || targetLang.isEmpty()) ? "th" : targetLang;
                        if (!translated.isEmpty()) CACHE.put(sl + ">" + tl + "|" + texts.get(idx).trim(), translated);
                    }
                } else {
                    // Delimiter was altered by translation — fall back cleanly item by item.
                    for (int idx : batchIdx) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
                        output.set(idx, translate(texts.get(idx), sourceLang, targetLang));
                    }
                }
            }
        }
        return output;
    }

    private static String requestTranslate(String query, String sl, String tl) throws IOException {
        IOException lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return postTranslate(query, sl, tl);
            } catch (IOException err) {
                lastError = err;
                if (Thread.currentThread().isInterrupted()) throw err;
                try { Thread.sleep(220); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw err;
                }
            }
        }
        throw lastError;
    }

    private static String postTranslate(String query, String sl, String tl) throws IOException {
        String urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx"
                + "&sl=" + URLEncoder.encode(sl, "UTF-8")
                + "&tl=" + URLEncoder.encode(tl, "UTF-8")
                + "&dt=t";
        byte[] payload = ("q=" + URLEncoder.encode(query, "UTF-8")).getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(12_000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setFixedLengthStreamingMode(payload.length);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 12; MatePad) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36");
        try {
            try (java.io.OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            return parseResponse(body.toString());
        } finally {
            conn.disconnect();
        }
    }

    private static String parseResponse(String json) throws IOException {
        try {
            JSONArray root = new JSONArray(json);
            JSONArray sentences = root.optJSONArray(0);
            if (sentences == null) return "";
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < sentences.length(); i++) {
                JSONArray seg = sentences.optJSONArray(i);
                if (seg != null && !seg.isNull(0)) {
                    out.append(seg.optString(0, ""));
                }
            }
            return out.toString().trim();
        } catch (Exception e) {
            throw new IOException("Could not parse translate response", e);
        }
    }
}
