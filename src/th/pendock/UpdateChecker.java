package th.pendock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class UpdateChecker {
    static final String RELEASES_URL = "https://github.com/gigabeat89/PenDock/releases";
    private static final String LATEST_API = "https://api.github.com/repos/gigabeat89/PenDock/releases/latest";
    private static final String ALLOWED_PREFIX = "https://github.com/gigabeat89/PenDock/";
    private static final int MAX_BYTES = 256 * 1024;

    private UpdateChecker() { }

    static final class Release {
        final String version;
        final String pageUrl;
        final String apkUrl;
        final String notes;

        Release(String version, String pageUrl, String apkUrl, String notes) {
            this.version = version;
            this.pageUrl = pageUrl;
            this.apkUrl = apkUrl;
            this.notes = notes;
        }
    }

    static Release fetchLatest(String currentVersion) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_API).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "PenDock/" + (currentVersion == null ? "0" : currentVersion));
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub API returned HTTP " + code);
            }
            String json = readBody(connection.getInputStream());
            return parseRelease(json);
        } finally {
            connection.disconnect();
        }
    }

    private static String readBody(InputStream stream) throws IOException {
        try (InputStream input = stream) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_BYTES) throw new IOException("Response too large");
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static Release parseRelease(String payload) throws IOException {
        try {
            JSONObject root = new JSONObject(payload);
            String tag = root.optString("tag_name", "").trim();
            String version = normalize(tag);
            if (version.isEmpty()) throw new IOException("Missing release version");
            String htmlUrl = root.optString("html_url", "").trim();
            String pageUrl = htmlUrl.startsWith(ALLOWED_PREFIX) ? htmlUrl : RELEASES_URL;
            String apkUrl = null;
            JSONArray assets = root.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset == null) continue;
                    String name = asset.optString("name", "").toLowerCase(java.util.Locale.ROOT);
                    String url = asset.optString("browser_download_url", "").trim();
                    if (name.endsWith(".apk") && url.startsWith(ALLOWED_PREFIX)) {
                        apkUrl = url;
                        break;
                    }
                }
            }
            String notes = root.optString("body", "").trim();
            if (notes.length() > 1200) notes = notes.substring(0, 1200).trim() + "…";
            return new Release(version, pageUrl, apkUrl, notes);
        } catch (JSONException malformed) {
            throw new IOException("Invalid release metadata", malformed);
        }
    }

    static String normalize(String version) {
        if (version == null) return "";
        String cleaned = version.trim();
        if (cleaned.startsWith("v") || cleaned.startsWith("V")) cleaned = cleaned.substring(1).trim();
        return cleaned;
    }

    static boolean isNewer(String latest, String current) {
        int[] latestParts = parseNumbers(latest);
        int[] currentParts = parseNumbers(current);
        int length = Math.max(latestParts.length, currentParts.length);
        for (int i = 0; i < length; i++) {
            int l = i < latestParts.length ? latestParts[i] : 0;
            int c = i < currentParts.length ? currentParts[i] : 0;
            if (l != c) return l > c;
        }
        return false;
    }

    private static int[] parseNumbers(String raw) {
        String normalized = normalize(raw);
        if (normalized.isEmpty()) return new int[0];
        int dash = normalized.indexOf('-');
        if (dash >= 0) normalized = normalized.substring(0, dash);
        String[] tokens = normalized.split("\\.");
        int[] values = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            try {
                values[i] = Integer.parseInt(tokens[i].trim());
            } catch (NumberFormatException ignored) {
                values[i] = 0;
            }
        }
        return values;
    }
}
