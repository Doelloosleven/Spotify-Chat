package dev.spotifychat;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;

/** Settings menu in Spotify's colors. Open with /spotify or from Mod Menu. */
public class SpotifyConfigScreen extends Screen {
    // Spotify palette
    private static final int BLACK = 0xFF000000;
    private static final int BACKGROUND = 0xF2121212;
    private static final int PANEL = 0xFF181818;
    private static final int ROW = 0xFF1F1F1F;
    private static final int ROW_HOVER = 0xFF2A2A2A;
    private static final int ELEVATED = 0xFF282828;
    private static final int GREEN = 0xFF1DB954;
    private static final int GREEN_LIGHT = 0xFF1ED760;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFB3B3B3;
    private static final int OFF = 0xFF535353;

    private static final SpotifyClient.Track SAMPLE =
            new SpotifyClient.Track("Song Name", List.of("Artist", "Feature"), "Album Name", true);
    private static final String[] TABS = {"General", "Guild & Party", "Message"};

    /** Remembered while the game runs, so the menu reopens on the same tab */
    private static int tab = 0;

    private final Screen parent;
    private final ModConfig cfg;

    private final List<EditBox> boxes = new ArrayList<>();
    private int panelX, panelW, panelTop, panelBottom, previewY = -1;

    public SpotifyConfigScreen(Screen parent) {
        super(Component.literal("Spotify Chat"));
        this.parent = parent;
        this.cfg = SpotifyChatClient.get().config();
    }

    @Override
    protected void init() {
        boxes.clear();
        labels.clear();
        previewY = -1;
        int step = 24;
        int rowH = 20;

        panelW = Math.min(width - 16, 460);
        panelX = (width - panelW) / 2;
        int colW = (panelW - 24) / 2;
        int left = panelX + 8;
        int right = left + colW + 8;

        // Tabs, styled like Spotify's filter chips
        int chipX = panelX;
        for (int i = 0; i < TABS.length; i++) {
            int w = font.width(TABS[i]) + 20;
            final int index = i;
            addRenderableWidget(new Chip(chipX, 31, w, 16, TABS[i], i == tab, () -> {
                tab = index;
                rebuildWidgets();
            }));
            chipX += w + 6;
        }

        panelTop = 53;
        int y = panelTop + 8;
        switch (tab) {
            case 0 -> y = generalTab(left, right, colW, rowH, step, y);
            case 1 -> y = guildPartyTab(left, right, colW, rowH, step, y);
            default -> y = messageTab(left, right, colW, rowH, step, y);
        }
        panelBottom = y + 4;

        int buttonY = Math.min(height - 26, panelBottom + 8);
        int buttonW = Math.min(150, (panelW - 24) / 2);
        addRenderableWidget(new PillButton(width / 2 - buttonW - 4, buttonY, buttonW, 20,
                SpotifyChatClient.get().isLoggedIn() ? "Log out phone/web" : "Connect phone/web",
                false, this::toggleLogin));
        addRenderableWidget(new PillButton(width / 2 + 4, buttonY, buttonW, 20, "Done", true, this::onClose));
    }

    private int generalTab(int left, int right, int colW, int rowH, int step, int y) {
        toggle(left, y, colW, rowH, "Mod enabled",
                "Turn off to make the mod ignore every !spotify.",
                () -> cfg.enabled, v -> cfg.enabled = v);
        toggle(right, y, colW, rowH, "Public messages",
                "On: everyone sees your song. Off: only you see it.",
                () -> cfg.publicMessages, v -> cfg.publicMessages = v);
        y += step;
        toggle(left, y, colW, rowH, "Share paused songs",
                "When Spotify is paused, share the last song with \"" + cfg.pausedSuffix.trim() + "\".",
                () -> cfg.sharePaused, v -> cfg.sharePaused = v);
        toggle(right, y, colW, rowH, "Share \"not listening\"",
                "When nothing is playing at all, send the \"not listening\" message instead of nothing.",
                () -> cfg.shareNothing, v -> cfg.shareNothing = v);
        y += step;
        toggle(left, y, colW, rowH, "Show my !spotify",
                "Also send your own \"!spotify\" message before the song.",
                () -> cfg.showTrigger, v -> cfg.showTrigger = v);
        toggle(right, y, colW, rowH, "Phone / web player",
                "Only after /spotify login: also look at your phone or web player when the desktop app has nothing.",
                () -> cfg.useWebApi, v -> cfg.useWebApi = v);
        y += step;
        addRenderableWidget(new ValueSlider(left, y, panelW - 16, rowH, ModConfig.PAUSED_NEVER,
                "Paused counts as not listening: after 30 min",
                "How long a song can be paused before it counts as \"not listening\". "
                        + "Before that it's shared as paused.",
                () -> cfg.pausedToNothingMinutes, v -> cfg.pausedToNothingMinutes = v,
                v -> "Paused counts as not listening: " + (v == 0 ? "right away"
                        : v >= ModConfig.PAUSED_NEVER ? "never" : "after " + v + " min")));
        return y + rowH;
    }

    private int guildPartyTab(int left, int right, int colW, int rowH, int step, int y) {
        toggle(left, y, colW, rowH, "/gc !spotify",
                "Typing /gc !spotify shares your song in guild chat.",
                () -> cfg.guildChat, v -> cfg.guildChat = v);
        toggle(right, y, colW, rowH, "/pc !spotify",
                "Typing /pc !spotify shares your song in party chat.",
                () -> cfg.partyChat, v -> cfg.partyChat = v);
        y += step;
        toggle(left, y, colW, rowH, "Answer guild",
                "When a guild member says !spotify, your song is sent to guild chat.",
                () -> cfg.answerGuild, v -> cfg.answerGuild = v);
        toggle(right, y, colW, rowH, "Answer party",
                "When a party member says !spotify, your song is sent to party chat.",
                () -> cfg.answerParty, v -> cfg.answerParty = v);
        y += step;
        addRenderableWidget(new ValueSlider(left, y, panelW - 16, rowH, 60,
                "Answer cooldown: 60s",
                "Minimum time between answering other players' !spotify, so you don't get muted for spam.",
                () -> cfg.answerCooldownSeconds, v -> cfg.answerCooldownSeconds = v,
                v -> "Answer cooldown: " + v + "s"));
        return y + rowH;
    }

    private int messageTab(int left, int right, int colW, int rowH, int step, int y) {
        toggle(left, y, colW, rowH, "Song",
                "Show the song name.",
                () -> cfg.showSong, v -> cfg.showSong = v);
        toggle(right, y, colW, rowH, "Artist",
                "Show the artist.",
                () -> cfg.showArtist, v -> cfg.showArtist = v);
        y += step;
        toggle(left, y, colW, rowH, "Featured artists",
                "Also show the featured artists after the main artist, like \"Abel, Sef, IJSLAND\". "
                        + "Only when Artist is on.",
                () -> cfg.showFeatures, v -> cfg.showFeatures = v);
        toggle(right, y, colW, rowH, "Album",
                "Show the album at the end.",
                () -> cfg.showAlbum, v -> cfg.showAlbum = v);
        y += step + 12;
        box(left, y, panelW - 16, rowH, "Text before the song",
                cfg.prefix, v -> cfg.prefix = v);
        y += step + 12;
        box(left, y, panelW - 16, rowH, "Message when you're not listening to anything",
                cfg.notPlayingFormat, v -> cfg.notPlayingFormat = v);
        y += step + 2;
        previewY = y;
        return y + 10;
    }

    private void toggleLogin() {
        SpotifyChatClient client = SpotifyChatClient.get();
        onClose(); // messages go to chat, so get out of the way
        if (client.isLoggedIn()) {
            client.logout();
        } else {
            try {
                client.login();
            } catch (Exception e) {
                SpotifyChatClient.LOGGER.error("Login failed", e);
                SpotifyChatClient.info("Something went wrong: " + e.getMessage(), ChatFormatting.RED);
            }
        }
    }

    private record Label(String text, int x, int y) {}
    private final List<Label> labels = new ArrayList<>();

    private void toggle(int x, int y, int w, int h, String label, String tip,
                        BooleanSupplier get, Consumer<Boolean> set) {
        Toggle t = new Toggle(x, y, w, h, label, get, set);
        t.setTooltip(Tooltip.create(Component.literal(tip)));
        addRenderableWidget(t);
    }

    private void box(int x, int y, int w, int h, String hint, String value, Consumer<String> onChange) {
        labels.add(new Label(hint, x + 2, y - 11));
        EditBox box = new EditBox(font, x + 6, y + (h - 8) / 2, w - 12, 10, Component.literal(hint));
        box.setBordered(false);
        box.setMaxLength(200);
        box.setValue(value);
        box.setTextColor(WHITE);
        box.setTooltip(Tooltip.create(Component.literal(hint)));
        box.setResponder(v -> {
            onChange.accept(v);
            cfg.save();
        });
        boxes.add(box);
        addRenderableWidget(box);
    }

    // ------------------------------------------------------------- drawing

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, BACKGROUND);
        // Top bar
        g.fill(0, 0, width, 24, BLACK);
        g.fill(0, 24, width, 25, GREEN);
        // Panel
        g.fill(panelX, panelTop, panelX + panelW, panelBottom, PANEL);
        // Text field backgrounds
        for (EditBox box : boxes) {
            int x = box.getX() - 6, y = box.getY() - 5, w = box.getWidth() + 12, h = 19;
            pill(g, x, y, w, h, box.isFocused() ? GREEN : ELEVATED);
            pill(g, x + 1, y + 1, w - 2, h - 2, ELEVATED);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        drawLogo(g, panelX + 8, 6);
        g.text(font, Component.literal("Spotify Chat").withStyle(ChatFormatting.BOLD), panelX + 24, 8, WHITE, false);
        String status = cfg.enabled ? "ON" : "OFF";
        g.text(font, status, panelX + panelW - 8 - font.width(status), 8, cfg.enabled ? GREEN : GRAY, false);

        for (Label l : labels) {
            g.text(font, l.text(), l.x(), l.y(), GRAY, false);
        }

        if (previewY >= 0) {
            int x = panelX + 8 + font.width("Preview: ");
            String preview = fit(SpotifyChatClient.get().formatTrack(SAMPLE), panelX + panelW - 8 - x);
            g.text(font, "Preview: ", panelX + 8, previewY, GRAY, false);
            g.text(font, preview, x, previewY, GREEN, false);
        }
    }

    /** Cuts text with "..." so it fits in maxWidth pixels. */
    private String fit(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        while (!text.isEmpty() && font.width(text + "...") > maxWidth) text = text.substring(0, text.length() - 1);
        return text + "...";
    }

    /** Spotify-style logo: green circle with three black "sound wave" bars. */
    private static void drawLogo(GuiGraphicsExtractor g, int x, int y) {
        g.fill(x + 3, y, x + 9, y + 12, GREEN);
        g.fill(x + 1, y + 1, x + 11, y + 11, GREEN);
        g.fill(x, y + 3, x + 12, y + 9, GREEN);
        g.fill(x + 3, y + 3, x + 9, y + 4, BLACK);
        g.fill(x + 3, y + 5, x + 8, y + 6, BLACK);
        g.fill(x + 4, y + 7, x + 8, y + 8, BLACK);
    }

    /** Rectangle with clipped corners, the closest thing to rounded in pixel art. */
    private static void pill(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x + 1, y, x + w - 1, y + h, color);
        g.fill(x, y + 1, x + w, y + h - 1, color);
    }

    @Override
    public void onClose() {
        cfg.save();
        minecraft.gui.setScreen(parent);
    }

    // ------------------------------------------------------------- widgets

    /** A row with a label and a Spotify-style switch on the right. */
    private class Toggle extends AbstractButton {
        private final BooleanSupplier get;
        private final Consumer<Boolean> set;

        Toggle(int x, int y, int w, int h, String label, BooleanSupplier get, Consumer<Boolean> set) {
            super(x, y, w, h, Component.literal(label));
            this.get = get;
            this.set = set;
        }

        @Override
        public void onPress(InputWithModifiers input) {
            set.accept(!get.getAsBoolean());
            cfg.save();
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            boolean on = get.getAsBoolean();
            pill(g, x, y, w, h, isHovered() ? ROW_HOVER : ROW);
            g.text(font, getMessage(), x + 6, y + (h - 8) / 2, on ? WHITE : GRAY, false);

            int sw = 20, sh = 10, sx = x + w - sw - 6, sy = y + (h - sh) / 2;
            pill(g, sx, sy, sw, sh, on ? (isHovered() ? GREEN_LIGHT : GREEN) : OFF);
            int knob = sh - 4;
            int kx = on ? sx + sw - knob - 2 : sx + 2;
            pill(g, kx, sy + 2, knob, knob, on ? BLACK : WHITE);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /** Whole-number slider from 0 to max, with a label on the left and a Spotify-style bar on the right. */
    private class ValueSlider extends AbstractSliderButton {
        private final int max;
        private final String widestLabel;
        private final IntConsumer set;
        private final IntFunction<String> label;

        ValueSlider(int x, int y, int w, int h, int max, String widestLabel, String tooltip,
                    IntSupplier get, IntConsumer set, IntFunction<String> label) {
            super(x, y, w, h, Component.empty(), Math.clamp(get.getAsInt() / (double) max, 0.0, 1.0));
            this.max = max;
            this.widestLabel = widestLabel;
            this.set = set;
            this.label = label;
            updateMessage();
            setTooltip(Tooltip.create(Component.literal(tooltip)));
        }

        private int intValue() {
            return (int) Math.round(value * max);
        }

        @Override
        protected void updateMessage() {
            if (label != null) setMessage(Component.literal(label.apply(intValue())));
        }

        @Override
        protected void applyValue() {
            set.accept(intValue());
            cfg.save();
        }

        // The bar sits to the right of the label, so clicks are mapped onto the bar instead of the whole row
        private int trackX() {
            return Math.max(getX() + 12 + font.width(widestLabel), getX() + getWidth() / 3);
        }

        private int trackW() {
            return Math.max(20, getX() + getWidth() - 10 - trackX());
        }

        private void setFromMouse(double mouseX) {
            setValue(Math.clamp((mouseX - trackX()) / trackW(), 0.0, 1.0));
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            setFromMouse(event.x());
        }

        @Override
        protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
            setFromMouse(event.x());
        }

        @Override
        public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            pill(g, x, y, w, h, isHovered() ? ROW_HOVER : ROW);
            g.text(font, getMessage(), x + 6, y + (h - 8) / 2, WHITE, false);

            int tx = trackX(), tw = trackW(), ty = y + h / 2 - 2;
            pill(g, tx, ty, tw, 4, OFF);
            int filled = (int) (value * tw);
            pill(g, tx, ty, Math.max(2, filled), 4, isHovered() ? GREEN_LIGHT : GREEN);
            pill(g, tx + filled - 4, ty - 2, 8, 8, WHITE);
        }
    }

    /** Tab chip like Spotify's filters: white with black text when selected, dark gray otherwise. */
    private class Chip extends AbstractButton {
        private final boolean selected;
        private final Runnable action;

        Chip(int x, int y, int w, int h, String label, boolean selected, Runnable action) {
            super(x, y, w, h, Component.literal(label));
            this.selected = selected;
            this.action = action;
        }

        @Override
        public void onPress(InputWithModifiers input) {
            if (!selected) action.run();
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            int bg = selected ? WHITE : (isHovered() ? 0xFF3E3E3E : ELEVATED);
            pill(g, x, y, w, h, bg);
            int tx = x + (w - font.width(getMessage())) / 2;
            g.text(font, getMessage(), tx, y + (h - 8) / 2, selected ? BLACK : WHITE, false);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /** Rounded Spotify button: green with black text (primary) or dark gray with white text. */
    private class PillButton extends AbstractButton {
        private final boolean primary;
        private final Runnable action;

        PillButton(int x, int y, int w, int h, String label, boolean primary, Runnable action) {
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
