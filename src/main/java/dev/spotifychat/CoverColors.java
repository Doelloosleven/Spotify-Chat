package dev.spotifychat;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Picks the overlay's colors from an album cover, like Spotify's own player does.
 * The cover is split into its main colors (k-means); the card gets a dark gradient from the cover's
 * biggest color to its second one, and its liveliest color as the accent (bar, "Paused", tinted text).
 */
public final class CoverColors {
    private CoverColors() {}

    /** left / right: the background gradient; accent: bar and "Paused"; text: artists and album line */
    public record Palette(int left, int right, int accent, int text) {}

    /** How many colors a cover is split into */
    private static final int K = 6;
    /** Same transparency as the normal overlay background */
    private static final int ALPHA = 0xE0;

    /** One of the cover's main colors and how much of the cover it fills (0..1) */
    private record Swatch(float hue, float sat, float bright, float share) {
        float vividness() {
            return sat * bright;
        }

        boolean colorful() {
            return sat >= 0.18f && bright >= 0.15f;
        }
    }

    /** argb = the cover's pixels (any square size). */
    public static Palette from(int[] argb) {
        List<Swatch> swatches = mainColors(argb);
        swatches.sort(Comparator.comparingDouble(Swatch::share).reversed());
        Swatch biggest = swatches.getFirst();

        // Accent: the liveliest color that covers at least a little of the cover (a small bright logo counts)
        Swatch lively = null;
        for (Swatch s : swatches) {
            if (s.share() < 0.015f || !s.colorful()) continue;
            double score = s.vividness() * Math.sqrt(s.share() + 0.05);
            if (lively == null || score > lively.vividness() * Math.sqrt(lively.share() + 0.05)) lively = s;
        }

        // Background: the biggest color; if that's gray or black, a hint of the accent's hue instead
        Swatch base = biggest.colorful() ? biggest
                : lively != null ? new Swatch(lively.hue(), 0.35f, biggest.bright(), biggest.share())
                : biggest;
        // Second gradient color: the next big color that looks different enough,
        // else the accent's color, else a darker base
        Swatch second = null;
        for (Swatch s : swatches) {
            if (s == biggest || s.share() < 0.06f) continue;
            if (differs(s, base)) {
                second = s;
                break;
            }
        }
        if (second == null && lively != null && differs(lively, base)) second = lively;
        int left = dark(base, 1f);
        int right = second != null ? dark(second, 0.9f) : dark(base, 0.6f);

        int accent, text;
        if (lively != null) {
            accent = Color.HSBtoRGB(lively.hue(), clamp(lively.sat(), 0.4f, 0.9f), Math.max(lively.bright(), 0.85f))
                    | 0xFF000000;
            text = CoverColors.mix(0xFFC8C8C8, accent, 0.35f);
        } else {
            accent = 0xFFE6E6E6; // black-and-white cover: stays black and white
            text = 0xFFB3B3B3;
        }
        return new Palette(left, right, accent, text);
    }

    /** A dark version of a color for under white text, keeping its hue and some of its saturation. */
    private static int dark(Swatch s, float factor) {
        float sat = s.colorful() || s.sat() >= 0.3f ? clamp(s.sat() * 1.15f, 0.45f, 0.85f) : s.sat() * 0.5f;
        // Lighter covers give a slightly lighter card, but always dark enough for white text
        float bright = clamp(0.17f + 0.22f * s.bright(), 0.14f, 0.36f) * factor;
        return Color.HSBtoRGB(s.hue(), sat, bright) & 0xFFFFFF | ALPHA << 24;
    }

    private static boolean differs(Swatch a, Swatch b) {
        float dh = Math.abs(a.hue() - b.hue());
        dh = Math.min(dh, 1 - dh);
        boolean bothColorful = a.colorful() && b.colorful();
        return (bothColorful && dh > 0.08f) || Math.abs(a.bright() - b.bright()) > 0.35f
                || a.colorful() != b.colorful();
    }

    /** k-means on (a sample of) the pixels: the cover's K main colors. */
    private static List<Swatch> mainColors(int[] argb) {
        int step = Math.max(1, argb.length / 1024); // ~1000 pixels are plenty
        int n = (argb.length + step - 1) / step;
        float[][] px = new float[n][3];
        for (int i = 0, j = 0; i < argb.length && j < n; i += step, j++) {
            int p = argb[i];
            px[j][0] = p >> 16 & 0xFF;
            px[j][1] = p >> 8 & 0xFF;
            px[j][2] = p & 0xFF;
        }

        // Start from spread-out pixels (always the same for the same cover): each next one is the
        // pixel furthest from the ones picked so far
        float[][] centers = new float[K][];
        centers[0] = px[0].clone();
        float[] nearest = new float[n];
        Arrays.fill(nearest, Float.MAX_VALUE);
        for (int c = 1; c < K; c++) {
            int far = 0;
            for (int i = 0; i < n; i++) {
                nearest[i] = Math.min(nearest[i], dist(px[i], centers[c - 1]));
                if (nearest[i] > nearest[far]) far = i;
            }
            centers[c] = px[far].clone();
        }

        int[] owner = new int[n];
        int[] count = new int[K];
        for (int iter = 0; iter < 10; iter++) {
            float[][] sum = new float[K][3];
            Arrays.fill(count, 0);
            for (int i = 0; i < n; i++) {
                int best = 0;
                for (int c = 1; c < K; c++) {
                    if (dist(px[i], centers[c]) < dist(px[i], centers[best])) best = c;
                }
                owner[i] = best;
                count[best]++;
                for (int ch = 0; ch < 3; ch++) sum[best][ch] += px[i][ch];
            }
            for (int c = 0; c < K; c++) {
                if (count[c] == 0) continue;
                for (int ch = 0; ch < 3; ch++) centers[c][ch] = sum[c][ch] / count[c];
            }
        }

        List<Swatch> out = new ArrayList<>();
        float[] hsb = new float[3];
        for (int c = 0; c < K; c++) {
            if (count[c] == 0) continue;
            Color.RGBtoHSB(Math.round(centers[c][0]), Math.round(centers[c][1]), Math.round(centers[c][2]), hsb);
            out.add(new Swatch(hsb[0], hsb[1], hsb[2], count[c] / (float) n));
        }
        return out;
    }

    private static float dist(float[] a, float[] b) {
        float dr = a[0] - b[0], dg = a[1] - b[1], db = a[2] - b[2];
        return dr * dr * 0.3f + dg * dg * 0.59f + db * db * 0.11f + (dr * dr + dg * dg + db * db) * 0.5f;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    /** Mixes two ARGB colors, t = 0 gives a, t = 1 gives b. */
    public static int mix(int a, int b, float t) {
        int out = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int ca = a >>> shift & 0xFF, cb = b >>> shift & 0xFF;
            out |= Math.round(ca + (cb - ca) * t) << shift;
        }
        return out;
    }
}
