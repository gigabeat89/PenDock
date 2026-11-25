package th.pendock;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import java.io.File;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Pure-Java PaddleOCR PP-OCRv5 engine powered by ONNX Runtime (DBNet detector + SVTR/CRNN recognizer). */
final class ScreenOcrEngine {
    static final class WordSpan {
        final Rect bounds;
        final String text;

        WordSpan(Rect bounds, String text) {
            this.bounds = bounds;
            this.text = text;
        }
    }

    static final class TextLine {
        final Rect bounds;
        final String text;
        final float confidence;
        final List<WordSpan> words;

        TextLine(Rect bounds, String text, float confidence, List<WordSpan> words) {
            this.bounds = bounds;
            this.text = text;
            this.confidence = confidence;
            this.words = words;
        }
    }

    static final class TextBlock {
        final Rect bounds;
        final String text;
        final List<TextLine> lines;

        TextBlock(Rect bounds, String text, List<TextLine> lines) {
            this.bounds = bounds;
            this.text = text;
            this.lines = lines;
        }
    }

    private static final float[] DET_MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] DET_STD = {0.229f, 0.224f, 0.225f};
    private static final float DET_THRESH = 0.46f;
    private static final float DET_BOX_THRESH = 0.55f;
    private static final int REC_HEIGHT = 48;
    private static final int REC_MAX_WIDTH = 2048;

    private static OrtEnvironment env;
    private static OrtSession detSession;
    private static OrtSession recSession;
    private static OcrModelManager.Pack loadedPack;
    private static String[] loadedDict;
    private static String detInputName;
    private static String recInputName;

    private ScreenOcrEngine() { }

    static synchronized void releaseIfLoaded(OcrModelManager.Pack pack) {
        if (loadedPack == pack) {
            closeRec();
        }
    }

    static synchronized void releaseAll() {
        closeRec();
        if (detSession != null) {
            try { detSession.close(); } catch (Exception ignored) { }
            detSession = null;
        }
    }

    private static void closeRec() {
        if (recSession != null) {
            try { recSession.close(); } catch (Exception ignored) { }
            recSession = null;
        }
        loadedPack = null;
        loadedDict = null;
    }

    private static synchronized void ensureLoaded(Context context, OcrModelManager.Pack pack)
            throws OrtException {
        if (!OcrModelManager.isPackReady(context, pack)) {
            throw new IllegalStateException("OCR pack not downloaded: " + pack.id);
        }
        if (env == null) env = OrtEnvironment.getEnvironment();
        int threads = Math.min(4, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        if (detSession == null) {
            File detFile = OcrModelManager.detFile(context);
            try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
                opts.setIntraOpNumThreads(threads);
                opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                detSession = env.createSession(detFile.getAbsolutePath(), opts);
                detInputName = detSession.getInputNames().iterator().next();
            }
        }
        if (recSession == null || loadedPack != pack) {
            closeRec();
            File recFile = OcrModelManager.recFile(context, pack);
            try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
                opts.setIntraOpNumThreads(threads);
                opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                recSession = env.createSession(recFile.getAbsolutePath(), opts);
                recInputName = recSession.getInputNames().iterator().next();
                Optional<String> raw = recSession.getMetadata().getCustomMetadataValue("character");
                if (raw.isPresent()) {
                    String meta = raw.get();
                    // Strip trailing newline without dropping meaningful whitespace entries like full-width space.
                    while (meta.endsWith("\n") || meta.endsWith("\r")) {
                        meta = meta.substring(0, meta.length() - 1);
                    }
                    loadedDict = meta.isEmpty() ? new String[0] : meta.split("\\r?\\n", -1);
                } else {
                    loadedDict = new String[0];
                }
                loadedPack = pack;
            }
        }
    }

    /** Recognizes all text lines inside a cropped box (offset back into screen coordinates). */
    static synchronized List<TextLine> recognizeRegion(Context context, OcrModelManager.Pack pack,
            Bitmap fullScreen, Rect region) throws OrtException {
        Rect safe = clampRect(region, fullScreen.getWidth(), fullScreen.getHeight());
        if (safe.width() < 6 || safe.height() < 6) return Collections.emptyList();
        Bitmap crop = Bitmap.createBitmap(fullScreen, safe.left, safe.top, safe.width(), safe.height());
        try {
            List<TextLine> local = recognizeBitmap(context, pack, crop, 1280, true);
            List<TextLine> mapped = new ArrayList<>(local.size());
            for (TextLine line : local) {
                Rect shifted = new Rect(line.bounds);
                shifted.offset(safe.left, safe.top);
                List<WordSpan> shiftedWords = new ArrayList<>(line.words.size());
                for (WordSpan w : line.words) {
                    Rect wr = new Rect(w.bounds);
                    wr.offset(safe.left, safe.top);
                    shiftedWords.add(new WordSpan(wr, w.text));
                }
                mapped.add(new TextLine(shifted, line.text, line.confidence, shiftedWords));
            }
            return mapped;
        } finally {
            crop.recycle();
        }
    }

    /** Recognizes all text lines across the entire screenshot (for Point/Hover and Full-screen modes). */
    static synchronized List<TextLine> recognizeFullScreen(Context context, OcrModelManager.Pack pack,
            Bitmap screen) throws OrtException {
        return recognizeBitmap(context, pack, screen, 1280, false);
    }

    private static List<TextLine> recognizeBitmap(Context context, OcrModelManager.Pack pack,
            Bitmap source, int maxSide, boolean singleLineFallback) throws OrtException {
        ensureLoaded(context, pack);
        List<Rect> boxes = detectBoxes(source, maxSide);
        if (boxes.isEmpty() && singleLineFallback && source.getHeight() <= source.getWidth() * 2) {
            boxes.add(new Rect(0, 0, source.getWidth(), source.getHeight()));
        }
        List<TextLine> results = new ArrayList<>(boxes.size());
        for (Rect box : boxes) {
            if (Thread.currentThread().isInterrupted()) break;
            TextLine line = recognizeCrop(source, box);
            if (line != null && !line.text.trim().isEmpty() && line.confidence >= 0.45f) {
                results.add(line);
            }
        }
        results = mergeHorizontalLines(results);
        sortReadingOrder(results);
        return results;
    }

    /** Runs DBNet text detection and extracts clean expanded bounding boxes in original bitmap coordinates. */
    private static List<Rect> detectBoxes(Bitmap source, int maxSide) throws OrtException {
        int origW = source.getWidth();
        int origH = source.getHeight();
        float ratio = 1f;
        int longest = Math.max(origW, origH);
        if (longest > maxSide) {
            ratio = maxSide / (float) longest;
        }
        int resizedW = Math.max(32, Math.round(origW * ratio / 32f) * 32);
        int resizedH = Math.max(32, Math.round(origH * ratio / 32f) * 32);
        Bitmap resized = (resizedW == origW && resizedH == origH)
                ? source : Bitmap.createScaledBitmap(source, resizedW, resizedH, true);
        int[] pixels = new int[resizedW * resizedH];
        resized.getPixels(pixels, 0, resizedW, 0, 0, resizedW, resizedH);
        if (resized != source) resized.recycle();

        int area = resizedW * resizedH;
        float[] input = new float[3 * area];
        // PaddleOCR DBNet expects BGR order normalized with ImageNet mean/std.
        for (int i = 0; i < area; i++) {
            int p = pixels[i];
            float b = ((p) & 0xff) / 255f;
            float g = ((p >> 8) & 0xff) / 255f;
            float r = ((p >> 16) & 0xff) / 255f;
            input[i] = (b - DET_MEAN[0]) / DET_STD[0];
            input[area + i] = (g - DET_MEAN[1]) / DET_STD[1];
            input[2 * area + i] = (r - DET_MEAN[2]) / DET_STD[2];
        }

        float[] prob;
        int outH;
        int outW;
        long[] shape = {1L, 3L, resizedH, resizedW};
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape);
             OrtSession.Result res = detSession.run(Collections.singletonMap(detInputName, tensor))) {
            OnnxValue val = res.get(0);
            long[] outShape = ((TensorInfo) val.getInfo()).getShape();
            outH = (int) outShape[outShape.length - 2];
            outW = (int) outShape[outShape.length - 1];
            FloatBuffer fb = ((OnnxTensor) val).getFloatBuffer();
            prob = new float[outH * outW];
            fb.get(prob);
        }

        return extractBoxesFromProbMap(prob, outW, outH, origW, origH);
    }

    private static List<Rect> extractBoxesFromProbMap(float[] prob, int w, int h, int origW, int origH) {
        boolean[] visited = new boolean[w * h];
        int[] queue = new int[w * h];
        float scaleX = origW / (float) w;
        float scaleY = origH / (float) h;
        List<Rect> boxes = new ArrayList<>();

        for (int y = 0; y < h; y++) {
            int rowOff = y * w;
            for (int x = 0; x < w; x++) {
                int idx = rowOff + x;
                if (visited[idx] || prob[idx] < DET_THRESH) continue;

                int head = 0, tail = 0;
                queue[tail++] = idx;
                visited[idx] = true;
                int minX = x, maxX = x, minY = y, maxY = y;
                int count = 0;
                float probSum = 0f;

                while (head < tail) {
                    int cur = queue[head++];
                    int cx = cur % w;
                    int cy = cur / w;
                    count++;
                    probSum += prob[cur];
                    if (cx < minX) minX = cx;
                    if (cx > maxX) maxX = cx;
                    if (cy < minY) minY = cy;
                    if (cy > maxY) maxY = cy;

                    if (cx > 0 && !visited[cur - 1] && prob[cur - 1] >= DET_THRESH) {
                        visited[cur - 1] = true;
                        queue[tail++] = cur - 1;
                    }
                    if (cx + 1 < w && !visited[cur + 1] && prob[cur + 1] >= DET_THRESH) {
                        visited[cur + 1] = true;
                        queue[tail++] = cur + 1;
                    }
                    if (cy > 0 && !visited[cur - w] && prob[cur - w] >= DET_THRESH) {
                        visited[cur - w] = true;
                        queue[tail++] = cur - w;
                    }
                    if (cy + 1 < h && !visited[cur + w] && prob[cur + w] >= DET_THRESH) {
                        visited[cur + w] = true;
                        queue[tail++] = cur + w;
                    }
                }

                int bw = maxX - minX + 1;
                int bh = maxY - minY + 1;
                if (count < 4 || bw < 3 || bh < 2) continue;
                float meanProb = probSum / count;
                if (meanProb < DET_BOX_THRESH) continue;

                // Standard DBNet unclip expansion; split vertically if tightly spaced lines touched.
                List<int[]> subRanges = splitTouchingLines(prob, w, minX, maxX, minY, maxY);
                for (int[] yr : subRanges) {
                    int sMinY = yr[0];
                    int sMaxY = yr[1];
                    int sMinX = maxX;
                    int sMaxX = minX;
                    for (int sy = sMinY; sy <= sMaxY; sy++) {
                        int off = sy * w;
                        for (int sx = minX; sx <= maxX; sx++) {
                            if (prob[off + sx] >= DET_THRESH) {
                                if (sx < sMinX) sMinX = sx;
                                if (sx > sMaxX) sMaxX = sx;
                            }
                        }
                    }
                    if (sMaxX < sMinX) continue;
                    int sbw = sMaxX - sMinX + 1;
                    int sbh = sMaxY - sMinY + 1;
                    if (sbw < 3 || sbh < 2) continue;

                    float areaF = sbw * (float) sbh;
                    float perim = 2f * (sbw + sbh);
                    float expand = (areaF * 1.45f) / Math.max(1f, perim);
                    float padX = Math.max(2f, expand * 1.15f);
                    float padY = Math.max(1.5f, expand * 0.90f);

                    int l = Math.max(0, Math.round((sMinX - padX) * scaleX));
                    int t = Math.max(0, Math.round((sMinY - padY) * scaleY));
                    int r = Math.min(origW, Math.round((sMaxX + 1 + padX) * scaleX));
                    int b = Math.min(origH, Math.round((sMaxY + 1 + padY) * scaleY));
                    if (r - l >= 6 && b - t >= 6) {
                        boxes.add(new Rect(l, t, r, b));
                    }
                }
            }
        }
        return boxes;
    }

    /** Splits a fused multi-line component along horizontal probability valleys so each text line is separate. */
    private static List<int[]> splitTouchingLines(float[] prob, int w,
            int minX, int maxX, int minY, int maxY) {
        int bh = maxY - minY + 1;
        int bw = maxX - minX + 1;
        List<int[]> result = new ArrayList<>();
        // Only check components tall enough to potentially contain 2+ tightly spaced lines.
        if (bh < 16 || bw < bh) {
            result.add(new int[] {minY, maxY});
            return result;
        }
        float[] rowPeak = new float[bh];
        float globalMax = 0f;
        for (int dy = 0; dy < bh; dy++) {
            int off = (minY + dy) * w;
            float sum = 0f;
            int active = 0;
            for (int x = minX; x <= maxX; x++) {
                float p = prob[off + x];
                if (p >= DET_THRESH) {
                    sum += p;
                    active++;
                }
            }
            float score = active > 0 ? (sum / Math.max(1, bw)) : 0f;
            rowPeak[dy] = score;
            if (score > globalMax) globalMax = score;
        }
        if (globalMax <= 0.01f) {
            result.add(new int[] {minY, maxY});
            return result;
        }
        float valleyThresh = globalMax * 0.22f;
        int curStart = -1;
        for (int dy = 0; dy < bh; dy++) {
            if (rowPeak[dy] >= valleyThresh) {
                if (curStart < 0) curStart = dy;
            } else if (curStart >= 0) {
                if (dy - curStart >= 3) {
                    result.add(new int[] {minY + curStart, minY + dy - 1});
                }
                curStart = -1;
            }
        }
        if (curStart >= 0 && bh - curStart >= 3) {
            result.add(new int[] {minY + curStart, maxY});
        }
        if (result.isEmpty()) result.add(new int[] {minY, maxY});
        return result;
    }

    /** Recognizes one detected text line crop with SVTR/CRNN + CTC decoding and word span estimation. */
    private static TextLine recognizeCrop(Bitmap source, Rect box) throws OrtException {
        Rect safe = clampRect(box, source.getWidth(), source.getHeight());
        if (safe.width() < 4 || safe.height() < 4) return null;

        int targetH = REC_HEIGHT;
        int targetW = Math.max(8, Math.min(REC_MAX_WIDTH,
                Math.round(targetH * (safe.width() / (float) safe.height()))));
        Bitmap crop = Bitmap.createBitmap(source, safe.left, safe.top, safe.width(), safe.height());
        Bitmap resized = Bitmap.createScaledBitmap(crop, targetW, targetH, true);
        crop.recycle();

        int[] pixels = new int[targetW * targetH];
        resized.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH);
        resized.recycle();

        int area = targetW * targetH;
        float[] input = new float[3 * area];
        for (int i = 0; i < area; i++) {
            int p = pixels[i];
            float b = (((p) & 0xff) / 255f - 0.5f) / 0.5f;
            float g = (((p >> 8) & 0xff) / 255f - 0.5f) / 0.5f;
            float r = (((p >> 16) & 0xff) / 255f - 0.5f) / 0.5f;
            input[i] = b;
            input[area + i] = g;
            input[2 * area + i] = r;
        }

        long[] shape = {1L, 3L, targetH, targetW};
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape);
             OrtSession.Result res = recSession.run(Collections.singletonMap(recInputName, tensor))) {
            OnnxValue val = res.get(0);
            long[] outShape = ((TensorInfo) val.getInfo()).getShape();
            int steps = (int) outShape[1];
            int classes = (int) outShape[2];
            FloatBuffer fb = ((OnnxTensor) val).getFloatBuffer();
            float[] probs = new float[steps * classes];
            fb.get(probs);
            return decodeCtc(probs, steps, classes, safe);
        }
    }

    private static TextLine decodeCtc(float[] probs, int steps, int classes, Rect box) {
        StringBuilder full = new StringBuilder();
        StringBuilder wordBuf = new StringBuilder();
        List<WordSpan> words = new ArrayList<>();
        int wordStartT = -1;
        int wordEndT = -1;
        int prevIdx = 0;
        float scoreSum = 0f;
        int charCount = 0;
        int dictLen = loadedDict == null ? 0 : loadedDict.length;

        for (int t = 0; t < steps; t++) {
            int offset = t * classes;
            int bestIdx = 0;
            float bestVal = probs[offset];
            for (int c = 1; c < classes; c++) {
                float v = probs[offset + c];
                if (v > bestVal) {
                    bestVal = v;
                    bestIdx = c;
                }
            }
            if (bestIdx != 0 && bestIdx != prevIdx) {
                String ch;
                if (bestIdx - 1 < dictLen) {
                    ch = loadedDict[bestIdx - 1];
                } else if (bestIdx == dictLen + 1) {
                    ch = " ";
                } else {
                    ch = "";
                }
                if (!ch.isEmpty()) {
                    full.append(ch);
                    scoreSum += bestVal;
                    charCount++;
                    if (" ".equals(ch) || "\u3000".equals(ch)) {
                        flushWord(words, wordBuf, wordStartT, wordEndT, steps, box);
                        wordStartT = -1;
                    } else {
                        if (wordStartT < 0) wordStartT = t;
                        wordEndT = t;
                        wordBuf.append(ch);
                    }
                }
            }
            prevIdx = bestIdx;
        }
        flushWord(words, wordBuf, wordStartT, wordEndT, steps, box);

        String text = full.toString().trim();
        float conf = charCount > 0 ? scoreSum / charCount : 0f;
        return new TextLine(box, text, conf, words);
    }

    private static void flushWord(List<WordSpan> words, StringBuilder buf,
            int startT, int endT, int totalSteps, Rect lineBox) {
        if (buf.length() == 0 || startT < 0 || totalSteps <= 0) {
            buf.setLength(0);
            return;
        }
        String word = buf.toString().trim();
        buf.setLength(0);
        if (word.isEmpty()) return;
        float f0 = Math.max(0f, (startT - 0.5f) / totalSteps);
        float f1 = Math.min(1f, (endT + 1.5f) / totalSteps);
        int l = lineBox.left + Math.round(lineBox.width() * f0);
        int r = Math.max(l + 8, lineBox.left + Math.round(lineBox.width() * f1));
        words.add(new WordSpan(new Rect(l, lineBox.top, Math.min(lineBox.right, r), lineBox.bottom), word));
    }

    /** Groups nearby vertically stacked lines into cohesive paragraphs for cleaner full-screen translation. */
    static List<TextBlock> groupIntoBlocks(List<TextLine> lines) {
        if (lines == null || lines.isEmpty()) return Collections.emptyList();
        List<TextLine> sorted = new ArrayList<>(lines);
        sortReadingOrder(sorted);

        List<List<TextLine>> groups = new ArrayList<>();
        for (TextLine line : sorted) {
            List<TextLine> target = null;
            for (int i = groups.size() - 1; i >= 0; i--) {
                List<TextLine> candidate = groups.get(i);
                TextLine last = candidate.get(candidate.size() - 1);
                if (canMergeIntoParagraph(last, line)) {
                    target = candidate;
                    break;
                }
            }
            if (target != null) {
                target.add(line);
            } else {
                List<TextLine> fresh = new ArrayList<>();
                fresh.add(line);
                groups.add(fresh);
            }
        }

        List<TextBlock> blocks = new ArrayList<>(groups.size());
        for (List<TextLine> group : groups) {
            Rect union = new Rect(group.get(0).bounds);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < group.size(); i++) {
                TextLine l = group.get(i);
                union.union(l.bounds);
                if (i > 0) {
                    char prevEnd = sb.length() > 0 ? sb.charAt(sb.length() - 1) : ' ';
                    char nextStart = l.text.charAt(0);
                    if (!isCjk(prevEnd) && !isCjk(nextStart)) sb.append(' ');
                }
                sb.append(l.text);
            }
            blocks.add(new TextBlock(union, sb.toString(), group));
        }
        return blocks;
    }

    private static boolean canMergeIntoParagraph(TextLine top, TextLine bottom) {
        int h1 = top.bounds.height();
        int h2 = bottom.bounds.height();
        if (h1 <= 0 || h2 <= 0) return false;
        float ratio = Math.max(h1, h2) / (float) Math.min(h1, h2);
        if (ratio > 1.45f) return false;
        int gap = bottom.bounds.top - top.bounds.bottom;
        if (gap < -Math.min(h1, h2) * 0.35f || gap > Math.max(h1, h2) * 0.85f) return false;
        int overlapLeft = Math.max(top.bounds.left, bottom.bounds.left);
        int overlapRight = Math.min(top.bounds.right, bottom.bounds.right);
        int overlap = overlapRight - overlapLeft;
        int minWidth = Math.min(top.bounds.width(), bottom.bounds.width());
        if (overlap <= 0 || overlap < minWidth * 0.35f) return false;
        // Require roughly aligned left edge or center so side-by-side columns don't merge.
        int leftDiff = Math.abs(top.bounds.left - bottom.bounds.left);
        int centerDiff = Math.abs(top.bounds.centerX() - bottom.bounds.centerX());
        return leftDiff <= Math.max(h1, h2) * 2.2f || centerDiff <= Math.max(h1, h2) * 1.8f;
    }

    static String joinLines(List<TextLine> lines) {
        if (lines == null || lines.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).text.trim();
            if (t.isEmpty()) continue;
            if (sb.length() > 0) {
                char prevEnd = sb.charAt(sb.length() - 1);
                char nextStart = t.charAt(0);
                if (!isCjk(prevEnd) && !isCjk(nextStart)) sb.append(' ');
            }
            sb.append(t);
        }
        return sb.toString();
    }

    private static boolean isCjk(char c) {
        return (c >= 0x3040 && c <= 0x30ff) || (c >= 0x4e00 && c <= 0x9fff)
                || (c >= 0x3400 && c <= 0x4dbf) || (c >= 0xac00 && c <= 0xd7af);
    }

    /**
     * Merges horizontally adjacent OCR fragments that belong to the same visual text line
     * (e.g. large headlines where DBNet detects individual words as separate boxes).
     */
    private static List<TextLine> mergeHorizontalLines(List<TextLine> lines) {
        if (lines == null || lines.size() <= 1) return lines;
        List<TextLine> byY = new ArrayList<>(lines);
        byY.sort(Comparator.comparingInt((TextLine l) -> l.bounds.centerY())
                .thenComparingInt(l -> l.bounds.left));

        // 1. Cluster fragments into horizontal visual rows using vertical overlap.
        List<List<TextLine>> rows = new ArrayList<>();
        List<Rect> rowBounds = new ArrayList<>();
        for (TextLine line : byY) {
            int bestRow = -1;
            int bestOverlap = 0;
            int h = Math.max(1, line.bounds.height());
            for (int i = 0; i < rows.size(); i++) {
                Rect rb = rowBounds.get(i);
                int rh = Math.max(1, rb.height());
                float heightRatio = Math.max(h, rh) / (float) Math.min(h, rh);
                if (heightRatio > 1.8f) continue;
                int ov = Math.min(rb.bottom, line.bounds.bottom) - Math.max(rb.top, line.bounds.top);
                int minH = Math.min(h, rh);
                if (ov >= minH * 0.45f && ov > bestOverlap) {
                    bestOverlap = ov;
                    bestRow = i;
                }
            }
            if (bestRow >= 0) {
                rows.get(bestRow).add(line);
                rowBounds.get(bestRow).union(line.bounds);
            } else {
                List<TextLine> fresh = new ArrayList<>();
                fresh.add(line);
                rows.add(fresh);
                rowBounds.add(new Rect(line.bounds));
            }
        }

        // 2. Within each row, sort left-to-right and stitch nearby fragments into full lines.
        List<TextLine> merged = new ArrayList<>(lines.size());
        for (List<TextLine> row : rows) {
            row.sort(Comparator.comparingInt(l -> l.bounds.left));
            TextLine acc = row.get(0);
            for (int i = 1; i < row.size(); i++) {
                TextLine next = row.get(i);
                int refH = Math.max(1, Math.max(acc.bounds.height(), next.bounds.height()));
                int gap = next.bounds.left - acc.bounds.right;
                int vOv = Math.min(acc.bounds.bottom, next.bounds.bottom)
                        - Math.max(acc.bounds.top, next.bounds.top);
                int minH = Math.max(1, Math.min(acc.bounds.height(), next.bounds.height()));
                if (gap <= refH * 1.6f && vOv >= minH * 0.35f) {
                    acc = combineHorizontal(acc, next);
                } else {
                    merged.add(acc);
                    acc = next;
                }
            }
            merged.add(acc);
        }
        return merged;
    }

    private static TextLine combineHorizontal(TextLine left, TextLine right) {
        Rect union = new Rect(left.bounds);
        union.union(right.bounds);
        String lt = left.text == null ? "" : left.text.trim();
        String rt = right.text == null ? "" : right.text.trim();
        String combinedText;
        if (lt.isEmpty()) {
            combinedText = rt;
        } else if (rt.isEmpty()) {
            combinedText = lt;
        } else {
            char prevEnd = lt.charAt(lt.length() - 1);
            char nextStart = rt.charAt(0);
            combinedText = (!isCjk(prevEnd) && !isCjk(nextStart)) ? (lt + " " + rt) : (lt + rt);
        }
        List<WordSpan> words = new ArrayList<>(left.words.size() + right.words.size() + 2);
        if (left.words.isEmpty() && !lt.isEmpty()) {
            words.add(new WordSpan(left.bounds, lt));
        } else {
            words.addAll(left.words);
        }
        if (right.words.isEmpty() && !rt.isEmpty()) {
            words.add(new WordSpan(right.bounds, rt));
        } else {
            words.addAll(right.words);
        }
        float conf = (left.confidence + right.confidence) * 0.5f;
        return new TextLine(union, combinedText, conf, words);
    }

    private static void sortReadingOrder(List<TextLine> lines) {
        if (lines == null || lines.size() <= 1) return;
        // Group into visual rows first so ordering is strictly transitive regardless of font size.
        List<TextLine> byTop = new ArrayList<>(lines);
        byTop.sort(Comparator.comparingInt((TextLine l) -> l.bounds.top)
                .thenComparingInt(l -> l.bounds.left));
        List<List<TextLine>> rows = new ArrayList<>();
        List<Integer> rowTop = new ArrayList<>();
        List<Integer> rowBottom = new ArrayList<>();
        for (TextLine line : byTop) {
            int placed = -1;
            int h = Math.max(1, line.bounds.height());
            for (int i = 0; i < rows.size(); i++) {
                int rTop = rowTop.get(i);
                int rBot = rowBottom.get(i);
                int ov = Math.min(rBot, line.bounds.bottom) - Math.max(rTop, line.bounds.top);
                int minH = Math.max(1, Math.min(h, rBot - rTop));
                if (ov >= minH * 0.5f) {
                    placed = i;
                    break;
                }
            }
            if (placed >= 0) {
                rows.get(placed).add(line);
                rowTop.set(placed, Math.min(rowTop.get(placed), line.bounds.top));
                rowBottom.set(placed, Math.max(rowBottom.get(placed), line.bounds.bottom));
            } else {
                List<TextLine> fresh = new ArrayList<>();
                fresh.add(line);
                rows.add(fresh);
                rowTop.add(line.bounds.top);
                rowBottom.add(line.bounds.bottom);
            }
        }
        lines.clear();
        for (List<TextLine> row : rows) {
            row.sort(Comparator.comparingInt(l -> l.bounds.left));
            lines.addAll(row);
        }
    }

    private static Rect clampRect(Rect r, int w, int h) {
        int left = Math.max(0, Math.min(w - 1, r.left));
        int top = Math.max(0, Math.min(h - 1, r.top));
        int right = Math.max(left + 1, Math.min(w, r.right));
        int bottom = Math.max(top + 1, Math.min(h, r.bottom));
        return new Rect(left, top, right, bottom);
    }
}
