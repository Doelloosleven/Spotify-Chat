package dev.spotifychat;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Spotify colors and small drawing helpers shared by the menu, the overlay and the overlay editor. */
public final class SpotifyUi {
    private SpotifyUi() {}

    public static final int BLACK = 0xFF000000;
    public static final int ELEVATED = 0xFF282828;
    public static final int WHITE = 0xFFFFFFFF;
    public static final int GRAY = 0xFFB3B3B3;
    public static final int DARK_GRAY = 0xFF7A7A7A;
    public static final int OFF = 0xFF535353;

    /** Menu color preset: the accent color (switches, bars, buttons) and its lighter hover version */
    public record Theme(String name, int accent, int light) {}

    /** Spotify green first: the default */
    public static final List<Theme> THEMES = List.of(
            new Theme("Spotify", 0xFF1DB954, 0xFF1ED760),
            new Theme("Ocean", 0xFF2E8BEF, 0xFF5AA7FF),
            new Theme("Sunset", 0xFFF2832B, 0xFFFFA25C),
            new Theme("Bubblegum", 0xFFF06AA9, 0xFFFF8CC0),
            new Theme("Grape", 0xFF9B6BF2, 0xFFB38CFF),
            new Theme("Cherry", 0xFFE5394A, 0xFFFF5E6C));

    /** The menu color picked in the settings (Spotify green if the name is unknown) */
    public static Theme theme() {
        SpotifyChatClient client = SpotifyChatClient.get();
        String name = client == null ? "" : client.config().menuTheme;
        for (Theme t : THEMES) {
            if (t.name().equalsIgnoreCase(name)) return t;
        }
        return THEMES.getFirst();
    }

    public static int accent() {
        return theme().accent();
    }

    public static int accentLight() {
        return theme().light();
    }

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

    /** Same as fit, for text drawn in bold: every letter is a pixel wider, so it has to be cut shorter. */
    public static String fitBold(Font font, String text, int maxWidth) {
        if (boldWidth(font, text) <= maxWidth) return text;
        String cut = fit(font, text, maxWidth);
        String base = cut.endsWith("...") ? cut.substring(0, cut.length() - 3) : cut;
        while (!base.isEmpty() && boldWidth(font, base.stripTrailing() + "...") > maxWidth) {
            base = base.substring(0, base.length() - 1);
        }
        return base.stripTrailing() + "...";
    }

    private static int boldWidth(Font font, String text) {
        return font.width(Component.literal(text).withStyle(ChatFormatting.BOLD));
    }

    /** Spotify-style logo: circle in the menu color with three black "sound wave" bars, 12x12. */
    public static void logo(GuiGraphicsExtractor g, int x, int y) {
        int color = accent();
        g.fill(x + 3, y, x + 9, y + 12, color);
        g.fill(x + 1, y + 1, x + 11, y + 11, color);
        g.fill(x, y + 3, x + 12, y + 9, color);
        g.fill(x + 3, y + 3, x + 9, y + 4, BLACK);
        g.fill(x + 3, y + 5, x + 8, y + 6, BLACK);
        g.fill(x + 4, y + 7, x + 8, y + 8, BLACK);
    }

    /** Rounded Spotify button: menu color with black text (primary) or dark gray with white text. */
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
            int bg = primary ? (isHovered() ? accentLight() : accent()) : (isHovered() ? 0xFF3E3E3E : ELEVATED);
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
