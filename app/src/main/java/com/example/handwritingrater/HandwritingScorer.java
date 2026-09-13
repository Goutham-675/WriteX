package com.example.handwritingrater;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import androidx.exifinterface.media.ExifInterface;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Pure scoring logic. Only touches Bitmap. No UI dependencies.
 *
 * Pipeline: downscale -> grayscale -> adaptive (Bradley) threshold ->
 * connected components (drops page-edge/desk blobs) -> text LINE segmentation
 * -> per-line metrics (baseline, spacing, slant, stroke, size) aggregated with
 * line-length weighting, computed with robust (median-clipped) statistics so
 * tiny i-dots and merged cursive blobs can't zero out a score.
 * Also detects machine-printed text vs handwriting.
 */
public final class HandwritingScorer {

    private static final int MAX_EDGE = 1000;
    private static final int MIN_COMPONENT_PX = 8;
    /** Components touching the image border larger than this are page edge / desk noise. */
    private static final int MAX_BORDER_COMP_PX = 400;

    // ---- adaptive threshold (Bradley) ----
    private static final double ADAPTIVE_RATIO = 0.85;
    private static final int WINDOW_DIV = 8;

    // ---- line segmentation ----
    private static final int LINE_PAD_PX = 3;
    private static final double LINE_OVERLAP_RATIO = 0.35;

    // ---- metric weights ----
    private static final float WT_SLANT = 0.18f;
    private static final float WT_BASELINE = 0.18f;
    private static final float WT_SPACING = 0.28f;
    private static final float WT_STROKE = 0.18f;
    private static final float WT_SIZE = 0.18f;

    // ---- metric curve constants (spread scores across all tiers) ----
    private static final double K_BASELINE = 220.0;   // scaled by rmse fraction of letter height
    private static final double K_SPACING = 70.0;
    private static final double K_SLANT = 1.7;
    private static final double K_STROKE = 60.0;
    private static final double K_SIZE = 90.0;

    // ---- robust-stat clipping bounds ----
    private static final double CLIP_LO = 0.4;
    private static final double CLIP_HI = 2.5;

    // ---- printed-text detection: voting thresholds ----
    // Baselines that are nearly perfectly straight are decisive (human writing
    // never is) -> printed gate; the rest vote with a "character grid"
    // regularity signal approximating font consistency.
    private static final double PRINT_STROKE = 0.15;
    private static final double PRINT_SIZE = 0.18;
    private static final double PRINT_SPACING = 0.26;
    private static final double PRINT_PITCH = 0.22;
    private static final double PRINT_BASE_HARD = 0.03;   // very straight -> less evidence needed
    private static final double PRINT_BASE_GATE = 0.06;   // must be at least this straight

    private HandwritingScorer() {}

    public static final class Result {
        public final int score;
        public final String tier;
        public final int slant, baseline, spacing, stroke, size;
        public final int lines, comps;
        public final double skew;
        public final boolean printed;
        public final Bitmap overlay; // debug builds only, may be null

        Result(int score, String tier, int slant, int baseline, int spacing, int stroke, int size,
               int lines, int comps, double skew, boolean printed, Bitmap overlay) {
            this.score = score;
            this.tier = tier;
            this.slant = slant;
            this.baseline = baseline;
            this.spacing = spacing;
            this.stroke = stroke;
            this.size = size;
            this.lines = lines;
            this.comps = comps;
            this.skew = skew;
            this.printed = printed;
            this.overlay = overlay;
        }
    }

    public static Bitmap downscale(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int longEdge = Math.max(w, h);
        if (longEdge <= MAX_EDGE) return src;
        float s = (float) MAX_EDGE / longEdge;
        return Bitmap.createScaledBitmap(src, Math.round(w * s), Math.round(h * s), true);
    }

    /**
     * Cheap pre-check for screenshots of digital text, run before any pixel
     * analysis. Real camera photos almost always carry EXIF (make/model,
     * datetime, orientation, ...); screenshots almost never do. Combined with a
     * tall phone-screen aspect ratio this independently catches screenshots
     * (including screenshots of handwriting images) that pure pixel stats miss.
     * Returns false if EXIF cannot be read (avoid false accusations).
     */
    public static boolean isScreenshot(byte[] image) {
        if (image == null || image.length < 64) return false;
        boolean hasCameraExif = false;
        try {
            ExifInterface ex = new ExifInterface(new java.io.ByteArrayInputStream(image));
            String make = ex.getAttribute(ExifInterface.TAG_MAKE);
            String model = ex.getAttribute(ExifInterface.TAG_MODEL);
            String date = ex.getAttribute(ExifInterface.TAG_DATETIME);
            int ori = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED);
            hasCameraExif = (make != null && !make.isEmpty())
                    || (model != null && !model.isEmpty())
                    || (date != null && !date.isEmpty())
                    || (ori >= ExifInterface.ORIENTATION_NORMAL && ori <= ExifInterface.ORIENTATION_ROTATE_270);
        } catch (Exception e) {
            return false;          // unreadable/missing EXIF -> assume camera-like
        }
        if (hasCameraExif) return false;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(image, 0, image.length, o);
        int w = o.outWidth, h = o.outHeight;
        if (w <= 0 || h <= 0) return false;
        return h > (int) (w * 1.8); // portrait/phone-screen aspect
    }

    public static Result score(Bitmap bitmap) {
        return score(bitmap, false);
    }

    public static Result score(Bitmap bitmap, boolean withOverlay) {
        Bitmap bmp = downscale(bitmap);
        int w = bmp.getWidth(), h = bmp.getHeight(), n = w * h;

        int[] gray = new int[n];
        bmp.getPixels(gray, 0, w, 0, 0, w, h);
        for (int i = 0; i < n; i++) {
            int c = gray[i];
            gray[i] = (((c >> 16) & 0xff) * 38 + ((c >> 8) & 0xff) * 75 + (c & 0xff) * 15) >> 7;
        }

        boolean[] ink = adaptiveInk(gray, w, h);
        int[] label = new int[n];
        List<Comp> comps = components(ink, gray, w, h, label);
        if (comps.size() < 4) {
            return new Result(0, tierOf(0), 0, 0, 0, 0, 0, 0, comps.size(), 0, false, null);
        }

        List<Line> lines = lines(comps);
        double medHeight = medianHeights(comps);

        double slantStd = slantStd(lines);
        double baseNorm = baselineNorm(lines, medHeight);
        double spacingCv = spacingCv(lines);
        double strokeCv = strokeCv(ink, comps, w, h);
        double sizeCv = sizeCv(lines);
        double pitch = pitchCv(lines);
        double skew = skewDegrees(lines);

        int slant = slantStd < 0 ? 70 : clamp(100 - slantStd * K_SLANT);
        int baseline = baseNorm < 0 ? 70 : clamp(100 - baseNorm * K_BASELINE);
        int spacing = spacingCv < 0 ? 70 : clamp(100 - spacingCv * K_SPACING);
        int stroke = strokeCv < 0 ? 70 : clamp(100 - strokeCv * K_STROKE);
        int size = sizeCv < 0 ? 70 : clamp(100 - sizeCv * K_SIZE);

        int score = Math.round(slant * WT_SLANT + baseline * WT_BASELINE + spacing * WT_SPACING
                + stroke * WT_STROKE + size * WT_SIZE);

        // Printed detection: voting across 5 machine-text signals, with a hard
        // gate on baseline straightness (the strongest single discriminator
        // that distinguishes human writing from any font, including carefully
        // handwritten imitations).
        boolean haveAll = strokeCv >= 0 && sizeCv >= 0 && spacingCv >= 0
                && baseNorm >= 0 && pitch >= 0;
        int votes = 0;
        if (haveAll) {
            if (strokeCv < PRINT_STROKE) votes++;
            if (sizeCv < PRINT_SIZE) votes++;
            if (spacingCv < PRINT_SPACING) votes++;
            if (pitch < PRINT_PITCH) votes++;
        }
        boolean printed = haveAll
                && baseNorm < PRINT_BASE_GATE                      // lines must be nearly perfect
                && (votes >= 4 || baseNorm < PRINT_BASE_HARD);    // or, fewer votes if lines are very straight

        Bitmap overlay = withOverlay ? mkOverlay(gray, ink, label, lines, w, h) : null;
        return new Result(score, tierOf(score), slant, baseline, spacing, stroke, size,
                lines.size(), comps.size(), skew, printed, overlay);
    }

    public static String tierOf(int s) {
        if (s >= 90) return "Font Incarnate";
        if (s >= 75) return "Calligrapher";
        if (s >= 60) return "Textbook Neat";
        if (s >= 40) return "Chaotic Scribbler";
        return "Doctor's Prescription";
    }

    // ---- Bradley adaptive threshold: ink = gray <= localMean * RATIO ----
    private static boolean[] adaptiveInk(int[] gray, int w, int h) {
        int sw = w + 1, sh = h + 1;
        long[] integral = new long[sw * sh];
        for (int y = 0; y < h; y++) {
            long row = 0;
            for (int x = 0; x < w; x++) {
                row += gray[y * w + x];
                integral[(y + 1) * sw + (x + 1)] = integral[y * sw + (x + 1)] + row;
            }
        }
        int win = Math.max(15, Math.max(w, h) / WINDOW_DIV);
        int half = win / 2;
        boolean[] ink = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - half);
            int y1 = Math.min(h - 1, y + half);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - half);
                int x1 = Math.min(w - 1, x + half);
                long sum = integral[(y1 + 1) * sw + (x1 + 1)]
                        - integral[y0 * sw + (x1 + 1)]
                        - integral[(y1 + 1) * sw + x0]
                        + integral[y0 * sw + x0];
                long cnt = (long) (y1 - y0 + 1) * (x1 - x0 + 1);
                double thr = (sum / (double) cnt) * ADAPTIVE_RATIO;
                ink[y * w + x] = gray[y * w + x] <= thr;
            }
        }
        return ink;
    }

    // ---- Connected components (8-dir BFS), pixels retained for all comps ----
    private static final class Comp {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        int count;
        long sumX, sumY;
        int cx, cy, bottomY;
        int[] xs, ys;
    }

    private static List<Comp> components(boolean[] ink, int[] gray, int w, int h, int[] label) {
        java.util.Arrays.fill(label, -1);
        int n = w * h;
        boolean[] seen = new boolean[n];
        List<Comp> out = new ArrayList<>();
        int[] q = new int[4096];
        List<Integer> pix = new ArrayList<>(256);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int start = y * w + x;
                if (!ink[start] || seen[start]) continue;
                pix.clear();
                int qh = 0, qt = 0;
                q[qt++] = start;
                seen[start] = true;
                Comp c = new Comp();
                while (qh < qt) {
                    int cur = q[qh++];
                    int cx = cur % w, cy = cur / w;
                    pix.add(cur);
                    c.count++;
                    c.sumX += cx;
                    c.sumY += cy;
                    if (cx < c.minX) c.minX = cx;
                    if (cx > c.maxX) c.maxX = cx;
                    if (cy < c.minY) c.minY = cy;
                    if (cy > c.maxY) c.maxY = cy;
                    for (int dy = -1; dy <= 1; dy++) {
                        int ny = cy + dy;
                        if (ny < 0 || ny >= h) continue;
                        for (int dx = -1; dx <= 1; dx++) {
                            if (dx == 0 && dy == 0) continue;
                            int nx = cx + dx;
                            if (nx < 0 || nx >= w) continue;
                            int ni = ny * w + nx;
                            if (ink[ni] && !seen[ni]) {
                                seen[ni] = true;
                                if (qt == q.length) q = java.util.Arrays.copyOf(q, q.length * 2);
                                q[qt++] = ni;
                            }
                        }
                    }
                }
                if (c.count < MIN_COMPONENT_PX) continue;
                c.cx = (int) (c.sumX / c.count);
                c.cy = (int) (c.sumY / c.count);
                c.bottomY = c.maxY;
                boolean touchesBorder = c.minX == 0 || c.maxX == w - 1 || c.minY == 0 || c.maxY == h - 1;
                // Page edge / desk blobs: large border-touching dark areas.
                if (touchesBorder && c.count > MAX_BORDER_COMP_PX) continue;
                c.xs = new int[c.count];
                c.ys = new int[c.count];
                for (int i = 0; i < pix.size(); i++) {
                    int p = pix.get(i);
                    c.xs[i] = p % w;
                    c.ys[i] = p / w;
                }
                label[pix.get(0)] = out.size();
                for (int i = 1; i < pix.size(); i++) label[pix.get(i)] = out.size();
                out.add(c);
            }
        }
        return out;
    }

    // ---- Text line segmentation: greedy band-overlap clustering on center-Y ----
    private static final class Line {
        int top = Integer.MAX_VALUE, bottom = Integer.MIN_VALUE;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        final List<Comp> comps = new ArrayList<>();

        void add(Comp c) {
            comps.add(c);
            top = Math.min(top, c.minY);
            bottom = Math.max(bottom, c.maxY);
            minX = Math.min(minX, c.minX);
            maxX = Math.max(maxX, c.maxX);
        }

        int height() { return bottom - top + 1; }
    }

    private static double overlapRatio(Comp c, Line L) {
        int lo = Math.max(c.minY, L.top - LINE_PAD_PX);
        int hi = Math.min(c.maxY, L.bottom + LINE_PAD_PX);
        if (hi < lo) return 0;
        double inter = hi - lo + 1;
        int denom = Math.min(c.maxY - c.minY + 1, L.height());
        return denom <= 0 ? 0 : inter / denom;
    }

    private static List<Line> lines(List<Comp> comps) {
        List<Comp> s = new ArrayList<>(comps);
        Collections.sort(s, Comparator.comparingDouble(c -> c.cy));
        List<Line> clean = new ArrayList<>();
        for (Comp c : s) {
            Line best = null;
            double bestOv = 0;
            for (Line L : clean) {
                double ov = overlapRatio(c, L);
                if (ov > bestOv) { bestOv = ov; best = L; }
            }
            if (best != null && bestOv >= LINE_OVERLAP_RATIO) {
                best.add(c);
            } else {
                Line nl = new Line();
                nl.add(c);
                clean.add(nl);
            }
        }
        // merge/drop singleton lines into their nearest band neighbor
        List<Line> kept = new ArrayList<>();
        for (Line L : clean) {
            if (L.comps.size() >= 2) { kept.add(L); continue; }
            Comp only = L.comps.get(0);
            Line best = null;
            double bestOv = 0;
            for (Line M : kept) {
                double ov = overlapRatio(only, M);
                if (ov > bestOv) { bestOv = ov; best = M; }
            }
            if (best != null && bestOv >= LINE_OVERLAP_RATIO) best.add(only);
        }
        kept.sort(Comparator.comparingInt(L -> L.top));
        return kept;
    }

    // ---- Robust per-line baseline fit (LSQ + outlier trimming) ----
    private static final class Fit {
        final double slope, icept, rmse;
        Fit(double slope, double icept, double rmse) {
            this.slope = slope; this.icept = icept; this.rmse = rmse;
        }
    }

    private static Fit fitBaseline(List<Comp> comps, double lineHeight) {
        Fit f = fitBaselinePass(comps);
        if (lineHeight <= 0) return f;
        // iterative trimming: keep only comps resting near the fitted baseline
        // (band ~0.35 of the line height, so descenders drop out, not letters)
        for (int iter = 0; iter < 2; iter++) {
            double tol = lineHeight * 0.35;
            List<Comp> in = new ArrayList<>();
            for (Comp c : comps) {
                double d = c.bottomY - (f.slope * c.cx + f.icept);
                if (Math.abs(d) <= tol) in.add(c);
            }
            if (in.size() < 2) return f;
            Fit g = fitBaselinePass(in);
            if (Math.abs(g.rmse - f.rmse) < 0.05) return g;
            f = g;
        }
        return f;
    }

    private static Fit fitBaselinePass(List<Comp> comps) {
        int n = comps.size();
        double sx = 0, sy = 0;
        for (Comp c : comps) { sx += c.cx; sy += c.bottomY; }
        double mx = sx / n, my = sy / n;
        double sxx = 0, sxy = 0;
        for (Comp c : comps) {
            double dx = c.cx - mx;
            sxx += dx * dx;
            sxy += dx * (c.bottomY - my);
        }
        double slope = sxx == 0 ? 0 : sxy / sxx;
        double icept = my - slope * mx;
        double se = 0;
        for (Comp c : comps) { double d = c.bottomY - (slope * c.cx + icept); se += d * d; }
        return new Fit(slope, icept, Math.sqrt(se / n));
    }

    private static double skewDegrees(List<Line> lines) {
        double wSum = 0;
        int cnt = 0;
        for (Line L : lines) {
            if (L.comps.size() < 2) continue;
            Fit f = fitBaseline(L.comps, L.height());
            wSum += Math.toDegrees(Math.atan(f.slope)) * L.comps.size();
            cnt += L.comps.size();
        }
        return cnt == 0 ? 0 : wSum / cnt;
    }

    // ---- Baseline: average per-line RMSE as a fraction of median letter height ----
    private static double baselineNorm(List<Line> lines, double medHeight) {
        double seTot = 0;
        int cnt = 0;
        for (Line L : lines) {
            if (L.comps.size() < 2) continue;
            Fit f = fitBaseline(L.comps, L.height());
            seTot += f.rmse * f.rmse * L.comps.size();
            cnt += L.comps.size();
        }
        if (cnt == 0 || medHeight <= 0) return -1;
        return Math.sqrt(seTot / cnt) / medHeight;
    }

    // ---- Spacing: weighted CV of intra-line gaps (robust-clipped) ----
    private static double spacingCv(List<Line> lines) {
        double num = 0;
        int cnt = 0;
        for (Line L : lines) {
            if (L.comps.size() < 4) continue;
            List<Comp> o = new ArrayList<>(L.comps);
            o.sort(Comparator.comparingInt(c -> c.minX));
            List<Double> gaps = new ArrayList<>();
            for (int i = 1; i < o.size(); i++) {
                double g = o.get(i).minX - o.get(i - 1).maxX;
                if (g > 0) gaps.add(g);
            }
            if (gaps.size() < 3) continue;
            double med = median(gaps);
            if (med <= 0) continue;
            List<Double> clipped = new ArrayList<>();
            for (double g : gaps) if (g >= med * 0.2 && g <= med * 6) clipped.add(g);
            if (clipped.size() < 3) continue;
            num += stdOf(clipped) / meanOf(clipped) * L.comps.size();
            cnt += L.comps.size();
        }
        return cnt == 0 ? -1 : num / cnt;
    }

    // ---- Slant: consistency of PCA lean across tall comps (robust std) ----
    private static double slantStd(List<Line> lines) {
        List<Double> angles = new ArrayList<>();
        for (Line L : lines) {
            for (Comp c : L.comps) {
                int bw = c.maxX - c.minX + 1, bh = c.maxY - c.minY + 1;
                if (bh < 12 || bh < 1.5 * bw) continue;
                double mx = c.sumX / (double) c.count, my = c.sumY / (double) c.count;
                double sxx = 0, syy = 0, sxy = 0;
                for (int i = 0; i < c.count; i++) {
                    double dx = c.xs[i] - mx, dy = c.ys[i] - my;
                    sxx += dx * dx;
                    syy += dy * dy;
                    sxy += dx * dy;
                }
                double angle = 0.5 * Math.toDegrees(Math.atan2(2 * sxy, sxx - syy));
                if (angle > 90) angle -= 180;
                if (angle < -90) angle += 180;
                angles.add(angle);
            }
        }
        if (angles.size() < 4) return -1;
        double med = median(angles);
        List<Double> c = new ArrayList<>();
        for (double a : angles) if (Math.abs(a - med) <= 25) c.add(a);
        if (c.size() < 4) return -1;
        return stdOf(c);
    }

    // ---- Stroke width: CV of per-component max distance-to-background ----
    private static double strokeCv(boolean[] ink, List<Comp> comps, int w, int h) {
        int n = w * h;
        int[] dist = new int[n];
        java.util.Arrays.fill(dist, -1);
        ArrayDeque<Integer> bfs = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            if (!ink[i]) { dist[i] = 0; bfs.add(i); }
        }
        int[] dx4 = {1, -1, 0, 0}, dy4 = {0, 0, 1, -1};
        while (!bfs.isEmpty()) {
            int cur = bfs.poll();
            int cx = cur % w, cy = cur / w;
            for (int k = 0; k < 4; k++) {
                int nx = cx + dx4[k], ny = cy + dy4[k];
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                int ni = ny * w + nx;
                if (dist[ni] == -1) { dist[ni] = dist[cur] + 1; bfs.add(ni); }
            }
        }
        List<Double> maxDs = new ArrayList<>();
        for (Comp c : comps) {
            int maxD = 0;
            for (int i = 0; i < c.count; i++) {
                int d = dist[c.ys[i] * w + c.xs[i]];
                if (d > maxD) maxD = d;
            }
            maxDs.add((double) maxD);
        }
        return cvs(maxDs);
    }

    // ---- Size: weighted CV of within-line component heights (robust) ----
    private static double sizeCv(List<Line> lines) {
        double num = 0;
        int cnt = 0;
        for (Line L : lines) {
            if (L.comps.size() < 3) continue;
            List<Double> hs = new ArrayList<>();
            for (Comp c : L.comps) hs.add((double) (c.maxY - c.minY + 1));
            double cv = cvs(hs);
            if (cv < 0) continue;
            num += cv * L.comps.size();
            cnt += L.comps.size();
        }
        return cnt == 0 ? -1 : num / cnt;
    }

    // ---- char pitch: CV of consecutive component center-X deltas per line ----
    // Font-set text repeats a near-constant advance width; handwriting does not.
    private static double pitchCv(List<Line> lines) {
        double num = 0;
        int cnt = 0;
        for (Line L : lines) {
            if (L.comps.size() < 5) continue;
            List<Comp> o = new ArrayList<>(L.comps);
            o.sort(Comparator.comparingInt(c -> c.cx));
            List<Double> d = new ArrayList<>();
            for (int i = 1; i < o.size(); i++) {
                double gap = o.get(i).cx - o.get(i - 1).cx;
                if (gap > 0) d.add(gap);
            }
            if (d.size() < 4) continue;
            double med = median(d);
            if (med <= 0) continue;
            List<Double> c = new ArrayList<>();
            for (double x : d) if (x >= med * 0.4 && x <= med * 4) c.add(x);
            if (c.size() < 4) continue;
            num += stdOf(c) / meanOf(c) * L.comps.size();
            cnt += L.comps.size();
        }
        return cnt == 0 ? -1 : num / cnt;
    }

    private static double medianHeights(List<Comp> comps) {
        List<Double> hs = new ArrayList<>();
        for (Comp c : comps) hs.add((double) (c.maxY - c.minY + 1));
        return median(hs);
    }

    // ---- robust median-clipped coefficient of variation; -1 if degenerate ----
    private static double cvs(List<Double> v) {
        double med = median(v);
        if (med <= 0) return -1;
        List<Double> c = new ArrayList<>();
        for (double x : v) if (x >= med * CLIP_LO && x <= med * CLIP_HI) c.add(x);
        if (c.size() < 3) return -1;
        double m = meanOf(c);
        if (m == 0) return -1;
        return stdOf(c) / m;
    }

    private static double meanOf(List<Double> v) {
        double m = 0;
        for (double x : v) m += x;
        return m / v.size();
    }

    private static double stdOf(List<Double> v) {
        double m = meanOf(v);
        double s = 0;
        for (double x : v) { double d = x - m; s += d * d; }
        return Math.sqrt(s / v.size());
    }

    private static double median(List<Double> v) {
        List<Double> c = new ArrayList<>(v);
        Collections.sort(c);
        return c.get(c.size() / 2);
    }

    // ---- Debug overlay: tinted components + line boxes + baselines ----
    private static Bitmap mkOverlay(int[] gray, boolean[] ink, int[] label, List<Line> lines, int w, int h) {
        int n = w * h;
        int[] pix = new int[n];
        for (int i = 0; i < n; i++) {
            if (!ink[i]) {
                int g = gray[i];
                pix[i] = Color.argb(255, g, g, g);
            } else if (label[i] >= 0) {
                pix[i] = hueColor(label[i]);
            } else {
                pix[i] = Color.rgb(60, 60, 60); // dropped (edge) component
            }
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(pix, 0, w, 0, 0, w, h);
        Canvas c = new Canvas(out);
        Paint lp = new Paint();
        lp.setStyle(Paint.Style.STROKE);
        lp.setStrokeWidth(2f);
        lp.setColor(0xFF00E5FF);
        Paint bp = new Paint();
        bp.setStyle(Paint.Style.STROKE);
        bp.setStrokeWidth(2f);
        bp.setColor(0xFFFF1744);
        for (Line L : lines) {
            float pad = LINE_PAD_PX;
            c.drawRect(L.minX - pad, L.top - pad, L.maxX + pad, L.bottom + pad, lp);
            if (L.comps.size() >= 2) {
                Fit f = fitBaseline(L.comps, L.height());
                float left = L.minX, right = L.maxX;
                float yl = (float) (f.slope * left + f.icept);
                float yr = (float) (f.slope * right + f.icept);
                c.drawLine(left, yl, right, yr, bp);
            }
        }
        return out;
    }

    private static int hueColor(int i) {
        float h = (i * 47f) % 360f;
        return Color.HSVToColor(new float[]{h, 0.85f, 0.9f});
    }

    private static int clamp(double v) {
        if (v < 0) return 0;
        if (v > 100) return 100;
        return (int) Math.round(v);
    }
}