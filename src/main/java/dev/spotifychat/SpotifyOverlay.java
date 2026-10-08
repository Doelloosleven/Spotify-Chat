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

/** "Now playing" card on the HUD: album cover, song, artists and album, in Spotify colors. */
public class SpotifyOverlay implements HudElement {
    private static final int PAD = 4;
    private static final int COVER = 32;
    private static final int MAX_TEXT = 150;
    private static final int BACKGROUND = 0xE0121212;

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
        Size size = measure(font, t, cfg);
        int w = Math.round(size.width() * scale), h = Math.round(size.height() * scale);
        int x = clamp((int) Math.round(cfg.overlayX * screenW), 0, Math.max(0, screenW - w));
        int y = clamp((int) Math.round(cfg.overlayY * screenH), 0, Math.max(0, screenH - h));

        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        draw(g, font, t, cfg, size);
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

    public static Size measure(Font font, SpotifyClient.Track t, ModConfig cfg) {
        int textW = 0;
        List<String> lines = lines(t, cfg);
        for (int i = 0; i < lines.size(); i++) {
            int w = i == 0 ? font.width(Component.literal(lines.get(i)).withStyle(ChatFormatting.BOLD))
                    : font.width(lines.get(i));
            textW = Math.max(textW, Math.min(MAX_TEXT, w));
        }
        int textH = lines.size() * 10 - 2;
        int left = PAD + 2 + (cfg.overlayShowCover ? COVER + 6 : 4);
        int height = Math.max(cfg.overlayShowCover ? COVER + PAD * 2 : 0, textH + PAD * 2 + 2);
        return new Size(left + textW + PAD + 2, height);
    }

    /** Draws the card with its top-left corner at 0,0 (the caller positions and scales it). */
    private static void draw(GuiGraphicsExtractor g, Font font, SpotifyClient.Track t, ModConfig cfg, Size size) {
        int w = size.width(), h = size.height();
        pill(g, 0, 0, w, h, BACKGROUND);
        // Green bar while playing, gray when paused
        g.fill(1, 3, 3, h - 3, t.playing() ? GREEN : OFF);

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

        List<String> lines = lines(t, cfg);
        int y = (h - (lines.size() * 10 - 2)) / 2;
        for (int i = 0; i < lines.size(); i++) {
            String text = fit(font, lines.get(i), i == 0 ? MAX_TEXT - 6 : MAX_TEXT);
            Component c = i == 0 ? Component.literal(text).withStyle(ChatFormatting.BOLD) : Component.literal(text);
            int color = i == 0 ? WHITE : i == 1 ? GRAY : (!t.playing() ? GREEN : DARK_GRAY);
            g.text(font, c, textX, y, color, false);
            y += 10;
        }
    }
}
