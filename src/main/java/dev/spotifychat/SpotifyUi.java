package dev.spotifychat;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/** Spotify colors and small drawing helpers shared by the menu, the overlay and the overlay editor. */
public final class SpotifyUi {
    private SpotifyUi() {}

    public static final int BLACK = 0xFF000000;
    public static final int ELEVATED = 0xFF282828;
    public static final int GREEN = 0xFF1DB954;
    public static final int GREEN_LIGHT = 0xFF1ED760;
    public static final int WHITE = 0xFFFFFFFF;
    public static final int GRAY = 0xFFB3B3B3;
    public static final int DARK_GRAY = 0xFF7A7A7A;
    public static final int OFF = 0xFF535353;

    /** Rectangle with clipped corners, the closest thing to rounded in pixel art. */
    public static void pill(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x + 1, y, x + w - 1, y + h, color);
        g.fill(x, y + 1, x + w, y + h - 1, color);
    }

    /** Cuts text with "..." so it fits in maxWidth pixels. */
    public static String fit(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("..."))) + "...";
    }

    /** Spotify-style logo: green circle with three black "sound wave" bars, 12x12. */
    public static void logo(GuiGraphicsExtractor g, int x, int y) {
        g.fill(x + 3, y, x + 9, y + 12, GREEN);
        g.fill(x + 1, y + 1, x + 11, y + 11, GREEN);
        g.fill(x, y + 3, x + 12, y + 9, GREEN);
        g.fill(x + 3, y + 3, x + 9, y + 4, BLACK);
        g.fill(x + 3, y + 5, x + 8, y + 6, BLACK);
        g.fill(x + 4, y + 7, x + 8, y + 8, BLACK);
    }

    /** Rounded Spotify button: green with black text (primary) or dark gray with white text. */
    public static class Button extends AbstractButton {
        private final boolean primary;
        private final Runnable action;

        public Button(int x, int y, int w, int h, String label, boolean primary, Runnable action) {
            super(x, y, w, h, Component.literal(label).withStyle(ChatFormatting.BOLD));
            this.primary = primary;
            this.action = action;
        }

        @Override
        public void onPress(InputWithModifiers input) {
            action.run();
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            Font font = Minecraft.getInstance().font;
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            int bg = primary ? (isHovered() ? GREEN_LIGHT : GREEN) : (isHovered() ? 0xFF3E3E3E : ELEVATED);
            pill(g, x, y, w, h, bg);
            int tx = x + (w - font.width(getMessage())) / 2;
            g.text(font, getMessage(), tx, y + (h - 8) / 2, primary ? BLACK : WHITE, false);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
