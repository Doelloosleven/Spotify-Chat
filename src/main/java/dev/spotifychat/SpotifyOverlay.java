package dev.spotifychat;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

import static dev.spotifychat.SpotifyUi.*;

/**
 * "Now playing" card on the HUD: album cover, song, artists and album, in Spotify colors
 * or in the colors of the album cover.
 */
public class SpotifyOverlay implements HudElement {
    private static final int PAD = 4;
    private static final int COVER = 32;
    private static final int MAX_TEXT = 150;
    private static final int BACKGROUND = 0xE0121212;
    /** A new song keeps the old colors this long while its cover is looked up, instead of flashing gray */
    private static final long COVER_WAIT_MS = 3_000;
    /** Lyrics: two lines of room under the song, so the card doesn't jump when a line wraps */
    private static final int LYRIC_ROWS = 2, LYRIC_GAP = 5;
    /** Show each lyrics line a moment early: the reading is a little behind and it reads better */
    private static final long LYRIC_LEAD_MS = 200;

    // Colors fade from one song to the next (render thread only)
    private static CoverColors.Palette shown, target;
    private static long lastFrame;
    private static String colorSong = "";
    private static long colorSongSince;

    /** Size of the card at 100%, in GUI pixels */
    public record Size(int width, int height) {}

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        SpotifyChatClient client = SpotifyChatClient.get();
        ModConfig cfg = client.config();
        if (!cfg.overlayEnabled || Mc.screen() instanceof OverlayPositionScreen) return; // the editor draws its own
        SpotifyClient.Track t = client.overlayTrack();
        if (t == null || !visible(t, cfg, client)) return;
        drawAtConfiguredSpot(g, mc.font, t, cfg, g.guiWidth(), g.guiHeight());
    }

    static boolean visible(SpotifyClient.Track t, ModConfig cfg, SpotifyChatClient client) {
        return t.playing() || (cfg.overlayShowWhenPaused && !client.pausedTooLong(t));
    }

    /** Draws the card where the settings put it, kept inside the screen. Returns where it ended up. */
    static int[] drawAtConfiguredSpot(GuiGraphicsExtractor g, Font font, SpotifyClient.Track t, ModConfig cfg,
                                      int screenW, int screenH) {
        float scale = cfg.overlayScale / 100f;
        String lyric = lyric(t, cfg);
        if (lyric == null && cfg.overlayLyrics && Mc.screen() instanceof OverlayPositionScreen) {
            lyric = "The lyrics go here"; // so the card is placed with its full size
        }
        Size size = measure(font, t, cfg, lyric);
        int w = Math.round(size.width() * scale), h = Math.round(size.height() * scale);
        int x = clamp((int) Math.round(cfg.overlayX * screenW), 0, Math.max(0, screenW - w));
        int y = clamp((int) Math.round(cfg.overlayY * screenH), 0, Math.max(0, screenH - h));

        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        draw(g, font, t, cfg, size, lyric);
        g.pose().popMatrix();
        return new int[]{x, y, w, h};
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static List<String> lines(SpotifyClient.Track t, ModConfig cfg) {
        List<String> lines = new ArrayList<>();
        lines.add(t.song());
        lines.add(String.join(", ", t.artists()));
        if (!t.playing()) lines.add("Paused");
        else if (cfg.overlayShowAlbum && !t.album().isBlank()) lines.add(t.album());
        return lines;
    }

    /** The lyrics line to show now ("" between lines), or null when there's no lyrics row */
    static String lyric(SpotifyClient.Track t, ModConfig cfg) {
        if (!cfg.overlayLyrics) return null;
        List<Lyrics.Line> lines = Lyrics.get(t, PlaybackClock.length(t.song()));
        if (lines == null) return null;
        long at = PlaybackClock.position(t, System.currentTimeMillis());
        if (at < 0) return null; // don't know where in the song Spotify is
        int i = Lyrics.indexAt(lines, at + LYRIC_LEAD_MS);
        return i < 0 ? "" : lines.get(i).text();
    }

    public static Size measure(Font font, SpotifyClient.Track t, ModConfig cfg) {
        return measure(font, t, cfg, lyric(t, cfg));
    }

    private static Size measure(Font font, SpotifyClient.Track t, ModConfig cfg, String lyric) {
        int textW = 0;
        List<String> lines = lines(t, cfg);
        for (int i = 0; i < lines.size(); i++) {
            int w = i == 0 ? font.width(Component.literal(lines.get(i)).withStyle(ChatFormatting.BOLD))
                    : font.width(lines.get(i));
            textW = Math.max(textW, Math.min(MAX_TEXT, w));
        }
        if (lyric != null) textW = MAX_TEXT; // full width with lyrics, so the card doesn't change size per line
        int left = PAD + 2 + (cfg.overlayShowCover ? COVER + 6 : 4);
        return new Size(left + textW + PAD + 2, songHeight(cfg, lines.size()) + (lyric == null ? 0 : lyricHeight()));
    }

    /** Picks this frame's colors: the album's (faded in) or the normal ones in the menu color. */
    private static CoverColors.Palette updateColors(SpotifyClient.Track t, ModConfig cfg) {
        long now = System.currentTimeMillis();
        String song = t.artist() + " - " + t.song();
        if (!song.equals(colorSong)) {
            colorSong = song;
            colorSongSince = now;
        }

        CoverColors.Palette next = new CoverColors.Palette(BACKGROUND, BACKGROUND, accent(), GRAY);
        boolean keepOld = false;
        if (cfg.overlayAlbumColors) {
            String url = t.coverUrl();
            if (url.isBlank()) {
                keepOld = now - colorSongSince < COVER_WAIT_MS; // the cover may still be looked up
            } else if (CoverTextures.loading(url)) {
                CoverTextures.get(url); // starts the download when the cover itself is hidden
                keepOld = true;
            } else {
                CoverColors.Palette p = CoverTextures.palette(url);
                if (p != null) next = p;
            }
        }
        if (!keepOld || target == null) target = next;

        // Ease towards the target in about half a second; jump if the card wasn't drawn for a while
        float dt = (now - lastFrame) / 1000f;
        lastFrame = now;
        if (shown == null || dt > 1) {
            shown = target;
        } else {
            float k = 1 - (float) Math.exp(-dt * 8);
            shown = new CoverColors.Palette(CoverColors.mix(shown.left(), target.left(), k),
                    CoverColors.mix(shown.right(), target.right(), k),
                    CoverColors.mix(shown.accent(), target.accent(), k),
                    CoverColors.mix(shown.text(), target.text(), k));
        }
        return shown;
    }

    /** The card's rounded shape, filled with a left-to-right gradient. */
    private static void gradientPill(GuiGraphicsExtractor g, int w, int h, int left, int right) {
        if (left == right) {
            pill(g, 0, 0, w, h, left);
            return;
        }
        for (int x = 0; x < w; x++) {
            int color = CoverColors.mix(left, right, x / (float) Math.max(1, w - 1));
            boolean edge = x == 0 || x == w - 1;
            g.fill(x, edge ? 1 : 0, x + 1, edge ? h - 1 : h, color);
        }
    }

    /** Height of the part with the cover and the song */
    private static int songHeight(ModConfig cfg, int lineCount) {
        return Math.max(cfg.overlayShowCover ? COVER + PAD * 2 : 0, lineCount * 10 - 2 + PAD * 2 + 2);
    }

    private static int lyricHeight() {
        return LYRIC_GAP + LYRIC_ROWS * 10 - 2 + PAD;
    }

    /** Draws the card with its top-left corner at 0,0 (the caller positions and scales it). */
    private static void draw(GuiGraphicsExtractor g, Font font, SpotifyClient.Track t, ModConfig cfg, Size size,
                             String lyric) {
        CoverColors.Palette colors = updateColors(t, cfg);
        List<String> lines = lines(t, cfg);
        int w = size.width(), full = size.height(), h = songHeight(cfg, lines.size());
        gradientPill(g, w, full, colors.left(), colors.right());
        // Bar in the accent color while playing, gray when paused
        g.fill(1, 3, 3, full - 3, t.playing() ? colors.accent() : OFF);

        int textX = PAD + 2;
        if (cfg.overlayShowCover) {
            int cy = (h - COVER) / 2;
            Identifier cover = CoverTextures.get(t.coverUrl());
            if (cover != null) {
                g.blit(RenderPipelines.GUI_TEXTURED, cover, textX, cy, 0, 0, COVER, COVER, COVER, COVER);
            } else {
                g.fill(textX, cy, textX + COVER, cy + COVER, ELEVATED);
                logo(g, textX + (COVER - 12) / 2, cy + (COVER - 12) / 2);
            }
            textX += COVER + 6;
        } else {
            textX += 4;
        }

        int y = (h - (lines.size() * 10 - 2)) / 2;
        for (int i = 0; i < lines.size(); i++) {
            String text = i == 0 ? fitBold(font, lines.get(i), MAX_TEXT) : fit(font, lines.get(i), MAX_TEXT);
            Component c = i == 0 ? Component.literal(text).withStyle(ChatFormatting.BOLD) : Component.literal(text);
            int color = i == 0 ? WHITE : i == 1 ? colors.text()
                    : !t.playing() ? colors.accent() : CoverColors.mix(colors.text(), DARK_GRAY, 0.5f);
            g.text(font, c, textX, y, color, false);
            y += 10;
        }
        if (lyric != null) drawLyric(g, font, lyric, colors, w, h);
    }

    /** The current lyrics line under the song, wrapped over at most two lines; a note between lines */
    private static void drawLyric(GuiGraphicsExtractor g, Font font, String lyric, CoverColors.Palette colors,
                                  int w, int top) {
        int x = PAD + 2, maxW = w - x - PAD - 2;
        g.fill(x, top + 1, w - PAD - 2, top + 2, CoverColors.mix(colors.text(), colors.left(), 0.6f));
        int y = top + LYRIC_GAP;
        if (lyric.isBlank()) {
            g.text(font, Component.literal("♪"), x, y, CoverColors.mix(colors.text(), DARK_GRAY, 0.5f), false);
            return;
        }
        List<String> rows = new ArrayList<>();
        for (var part : font.getSplitter().splitLines(lyric, maxW, net.minecraft.network.chat.Style.EMPTY)) {
            rows.add(part.getString());
        }
        for (int i = 0; i < Math.min(LYRIC_ROWS, rows.size()); i++) {
            String row = rows.get(i);
            if (i == LYRIC_ROWS - 1 && rows.size() > LYRIC_ROWS) {
                row = fit(font, String.join(" ", rows.subList(i, rows.size())), maxW); // cut the rest with "..."
            }
            g.text(font, Component.literal(row), x, y, WHITE, false);
            y += 10;
        }
    }
}
