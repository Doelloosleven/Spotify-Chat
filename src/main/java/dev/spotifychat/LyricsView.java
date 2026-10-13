package dev.spotifychat;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;

import static dev.spotifychat.SpotifyUi.*;

/**
 * The lyrics on the overlay, animated like a lyrics screen:
 * - the line being sung fills up from left to right, with a glow in the album's color running at the
 *   front of the fill, and the letters hop as it passes them
 * - the next line waits dimmed below it; on its turn the lines glide up, each a little after the one above
 * - instrumental parts get three dots that light up one by one while they breathe, and pop when the
 *   singing comes back
 * LRC lyrics are timed per line, not per word, so the fill guesses how fast a line is sung.
 * Render thread only.
 */
final class LyricsView {
    private LyricsView() {}

    /** Height of the lyrics area: the current line and what comes next (three rows) */
    static final int HEIGHT = 35;
    private static final int ROW = 10, LINE_GAP = 5, MAX_ROWS = 2;
    /** Each line comes a moment early: the reading is a little behind, and it reads better */
    private static final long LEAD_MS = 200;
    /** Roughly how fast lyrics are sung: a line fills at least this fast */
    private static final long MS_PER_CHAR = 65;
    /** Silence this long after a line is sung gets the dots, even without an empty line in the lyrics */
    private static final long LONG_BREAK_MS = 4_500;
    /** Width of the glowing front of the fill */
    private static final float EDGE = 18;
    /** Lines that aren't being sung */
    private static final float DIM = 0.4f;
    private static final int DOT = 5, DOT_STEP = 8;
    /** Rows fade out over this many pixels at the top and bottom edge, instead of being cut off */
    private static final float EDGE_FADE = 7;

    /** One row of a line, with where each character starts */
    private record Row(String[] chars, int[] x, int width) {}

    /** A line of the lyrics (rows empty = an instrumental part), placed in the scrolling list */
    private record Block(long time, long end, List<Row> rows, int y, int height) {
        boolean dots() {
            return rows.isEmpty();
        }
    }

    // Laid out once per song
    private static List<Lyrics.Line> laidOut;
    private static int laidOutWidth;
    private static List<Block> blocks = List.of();
    /** Per line: where it's drawn now (moving towards its place), and how lit it still is */
    private static float[] shownY, lit;
    private static int lastCurrent = -1;
    private static long lastFrame;

    // A steady clock: the readings jump a little now and then, the animation shouldn't
    private static String clockSong;
    private static double clockMs;
    private static long clockFrame;

    /** Draws the lyrics in the area x, y, width x HEIGHT */
    static void draw(GuiGraphicsExtractor g, Font font, SpotifyClient.Track t, List<Lyrics.Line> lines,
                     CoverColors.Palette colors, int x, int y, int width) {
        long now = System.currentTimeMillis();
        long pos = steadyPosition(t, now);
        if (pos < 0) return;
        if (lines != laidOut || width != laidOutWidth) {
            blocks = layout(font, lines, width);
            laidOut = lines;
            laidOutWidth = width;
            shownY = null;
        }
        if (blocks.isEmpty()) return;
        long at = pos + LEAD_MS;
        int current = Math.max(0, indexAt(blocks, at));
        float dt = Math.min(0.25f, (now - lastFrame) / 1000f);
        lastFrame = now;
        move(current, dt);

        float fade = t.playing() ? 1 : 0.7f;
        int glow = CoverColors.mix(colors.accent(), WHITE, 0.35f);
        g.enableScissor(x - 2, y - 1, x + width + 2, y + HEIGHT);
        for (int i = Math.max(0, current - 2); i < Math.min(blocks.size(), current + 5); i++) {
            Block b = blocks.get(i);
            float top = shownY[i];
            if (top + b.height() < -2 || top > HEIGHT) continue;
            float alpha = fade;
            if (top > ROW) alpha *= clamp(1 - (top - ROW) / (HEIGHT - ROW + 8f)); // further down: dimmer
            if (b.dots()) {
                drawDots(g, b, x, y + Math.round(top), i == current ? at : -1, alpha * edge(top), t.playing(), now);
            } else if (i == current) {
                drawSung(g, font, i, x, y, top, at, alpha, glow, t.playing());
            } else {
                for (int r = 0; r < b.rows().size(); r++) {
                    float rowTop = top + r * ROW;
                    int a = Math.round(255 * alpha * edge(rowTop) * (DIM + (1 - DIM) * lit[i]));
                    if (a > 4) {
                        g.text(font, String.join("", b.rows().get(r).chars()), x, y + Math.round(rowTop), argb(a, WHITE), false);
                    }
                }
            }
        }
        g.disableScissor();
    }

    /** Stand-in for the overlay editor, where no song may be playing */
    static void drawPlaceholder(GuiGraphicsExtractor g, Font font, int x, int y) {
        g.text(font, "The lyrics go here", x, y, WHITE, false);
        g.text(font, "and the next line here", x, y + ROW + LINE_GAP - 2, argb(Math.round(255 * DIM), WHITE), false);
    }

    /** A row this far from the top of the area: fully there, or fading out over the top / bottom edge */
    private static float edge(float rowTop) {
        return clamp(1 + rowTop / EDGE_FADE) * clamp(1 - (rowTop + 8 - HEIGHT) / EDGE_FADE);
    }

    /** Each line glides towards its place; lines further down follow a little later (a wave) */
    private static void move(int current, float dt) {
        int n = blocks.size();
        boolean jump = shownY == null || shownY.length != n || Math.abs(current - lastCurrent) > 2;
        if (jump) {
            shownY = new float[n];
            lit = new float[n];
        }
        int base = blocks.get(current).y();
        for (int i = 0; i < n; i++) {
            float target = blocks.get(i).y() - base;
            boolean near = i >= current - 2 && i <= current + 5;
            if (jump || !near) {
                shownY[i] = target;
            } else {
                float speed = 13f / (1 + 0.45f * Math.max(0, i - current));
                shownY[i] += (target - shownY[i]) * (1 - (float) Math.exp(-dt * speed));
            }
            if (i != current) lit[i] *= (float) Math.exp(-dt * 5);
        }
        lastCurrent = current;
    }

    /** The line being sung: lit up to where the singing is, glowing at the front, letters hopping there */
    private static void drawSung(GuiGraphicsExtractor g, Font font, int index, int x, int y, float top, long at,
                                 float alpha, int glow, boolean playing) {
        Block b = blocks.get(index);
        int total = 0, chars = 0;
        for (Row r : b.rows()) {
            total += r.width();
            chars += r.chars().length;
        }
        long slot = Math.max(250, b.end() - b.time() - 100);
        long sing = Math.min(slot, Math.max(600, chars * MS_PER_CHAR));
        float p = clamp((at - b.time()) / (float) sing);
        lit[index] = p;
        float front = p * (total + EDGE);

        int before = 0; // width of the rows above
        for (int r = 0; r < b.rows().size(); r++) {
            Row row = b.rows().get(r);
            float rowTop = top + r * ROW;
            float rowAlpha = alpha * edge(rowTop);
            for (int k = 0; k < row.chars().length; k++) {
                int w = k + 1 < row.x().length ? row.x()[k + 1] - row.x()[k] : row.width() - row.x()[k];
                float c = before + row.x()[k] + w / 2f;
                float s = clamp((front - c) / EDGE); // 0 = not sung yet, 1 = sung
                float bell = (float) Math.sin(Math.PI * s);
                int a = Math.round(255 * rowAlpha * (DIM + (1 - DIM) * Math.min(1, s * 1.6f)));
                int color = CoverColors.mix(WHITE, glow, bell);
                int hop = playing && s > 0.15f && s < 0.75f ? 1 : 0;
                if (a > 4) g.text(font, row.chars()[k], x + row.x()[k], y + Math.round(rowTop) - hop, argb(a, color), false);
            }
            before += row.width();
        }
    }

    /**
     * Three dots for an instrumental part. While it plays (at >= 0) they light up one by one over its
     * length and breathe, and at the end they swell and fade as the next line comes in.
     */
    private static void drawDots(GuiGraphicsExtractor g, Block b, int x, int y, long at, float alpha,
                                 boolean playing, long now) {
        float p = 0, scale = 1;
        if (at >= 0) {
            long length = Math.max(1, b.end() - b.time());
            p = clamp((at - b.time()) / (float) length);
            if (playing) scale += 0.12f * (float) Math.sin(now * 2 * Math.PI / 1500.0);
            float left = clamp((b.end() - at) / 350f); // the last moment: swell and fade out
            scale *= 1 + 0.45f * (1 - left);
            alpha *= left;
        }
        float cx = x + (DOT_STEP * 2 + DOT) / 2f, cy = y + 1 + DOT / 2f;
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().scale(scale, scale);
        g.pose().translate(-cx, -cy);
        for (int i = 0; i < 3; i++) {
            float on = at >= 0 ? clamp(p * 3 - i) : 0;
            int a = Math.round(255 * alpha * (0.3f + 0.7f * on));
            if (a <= 4) continue;
            int dx = x + i * DOT_STEP, dy = y + 1;
            int color = argb(a, WHITE);
            g.fill(dx + 1, dy, dx + DOT - 1, dy + DOT, color);
            g.fill(dx, dy + 1, dx + 1, dy + DOT - 1, color);
            g.fill(dx + DOT - 1, dy + 1, dx + DOT, dy + DOT - 1, color);
        }
        g.pose().popMatrix();
    }

    /**
     * Where in the song we are, moving smoothly: follows the clock, but eases into a new reading instead
     * of jumping a few hundred ms (that would shake the fill). Seeks and song changes still jump.
     */
    private static long steadyPosition(SpotifyClient.Track t, long now) {
        long reading = PlaybackClock.position(t, now);
        String song = PlaybackClock.key(t.song());
        long dt = now - clockFrame;
        clockFrame = now;
        if (reading < 0) {
            clockSong = null;
            return -1;
        }
        double guess = clockMs + dt;
        if (!song.equals(clockSong) || !t.playing() || dt > 1_000 || Math.abs(guess - reading) > 1_500) {
            clockSong = song;
            clockMs = reading;
        } else {
            clockMs = guess + (reading - guess) * Math.min(1, dt / 500.0);
        }
        return Math.round(clockMs);
    }

    /**
     * The lines as blocks under each other. Adds dots for the intro and for long silences, and makes one
     * block of several empty lines in a row.
     */
    private static List<Block> layout(Font font, List<Lyrics.Line> lines, int width) {
        List<Lyrics.Line> seq = new ArrayList<>();
        if (lines.isEmpty() || lines.getFirst().timeMs() > 1_500) seq.add(new Lyrics.Line(0, "")); // intro
        for (int i = 0; i < lines.size(); i++) {
            Lyrics.Line l = lines.get(i);
            boolean empty = l.text().isBlank();
            if (empty && !seq.isEmpty() && seq.getLast().text().isEmpty()) continue;
            seq.add(empty ? new Lyrics.Line(l.timeMs(), "") : l);
            if (!empty && i + 1 < lines.size()) {
                long sungBy = l.timeMs() + Math.max(2_500, l.text().length() * MS_PER_CHAR + 1_500);
                Lyrics.Line next = lines.get(i + 1);
                if (!next.text().isBlank() && next.timeMs() - sungBy >= LONG_BREAK_MS) seq.add(new Lyrics.Line(sungBy, ""));
            }
        }
        List<Block> out = new ArrayList<>();
        int y = 0;
        for (int i = 0; i < seq.size(); i++) {
            Lyrics.Line l = seq.get(i);
            long end = i + 1 < seq.size() ? seq.get(i + 1).timeMs() : l.timeMs() + 10_000;
            List<Row> rows = l.text().isEmpty() ? List.of() : wrap(font, l.text(), width);
            int height = rows.isEmpty() ? DOT + 2 : rows.size() * ROW - 2;
            out.add(new Block(l.timeMs(), end, rows, y, height));
            y += height + LINE_GAP;
        }
        return List.copyOf(out);
    }

    /** Wraps a line to the width, at most two rows (the rest cut with "...") */
    private static List<Row> wrap(Font font, String text, int width) {
        List<String> parts = new ArrayList<>();
        for (var part : font.getSplitter().splitLines(text, width, Style.EMPTY)) parts.add(part.getString().strip());
        if (parts.size() > MAX_ROWS) {
            String rest = String.join(" ", parts.subList(MAX_ROWS - 1, parts.size()));
            parts = new ArrayList<>(parts.subList(0, MAX_ROWS - 1));
            parts.add(fit(font, rest, width));
        }
        List<Row> rows = new ArrayList<>();
        for (String part : parts) {
            int[] cps = part.codePoints().toArray();
            String[] chars = new String[cps.length];
            int[] xs = new int[cps.length];
            int x = 0;
            for (int k = 0; k < cps.length; k++) {
                chars[k] = new String(Character.toChars(cps[k]));
                xs[k] = x;
                x += font.width(chars[k]);
            }
            rows.add(new Row(chars, xs, x));
        }
        return rows;
    }

    /** The last block that has started by this time, -1 before the first */
    private static int indexAt(List<Block> blocks, long ms) {
        int lo = 0, hi = blocks.size() - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (blocks.get(mid).time() <= ms) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    private static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }

    private static int argb(int alpha, int rgb) {
        return Math.max(0, Math.min(255, alpha)) << 24 | (rgb & 0xFFFFFF);
    }
}
