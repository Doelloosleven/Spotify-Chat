package dev.spotifychat;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;

/** Settings menu in Spotify's colors (or another preset). Open with /spotify, F4 or from Mod Menu. */
public class SpotifyConfigScreen extends Screen {
    // Spotify palette; the accent (green by default) comes from SpotifyUi.theme()
    private static final int BLACK = 0xFF000000;
    private static final int BACKGROUND = 0xF2121212;
    private static final int PANEL = 0xFF181818;
    private static final int ROW = 0xFF1F1F1F;
    private static final int ROW_HOVER = 0xFF2A2A2A;
    private static final int ELEVATED = 0xFF282828;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFB3B3B3;
    private static final int OFF = 0xFF535353;

    private static final SpotifyClient.Track SAMPLE =
            new SpotifyClient.Track("Song Name", List.of("Artist", "Feature"), "Album Name", true);
    private static final String[] TABS = {"General", "Hypixel", "Message", "Overlay", "IRC", "Keys & Updates"};

    /** Remembered while the game runs, so the menu reopens on the same tab */
    private static int tab = 0;

    private final Screen parent;
    private final ModConfig cfg;

    private final List<EditBox> boxes = new ArrayList<>();
    private int panelX, panelW, panelTop, panelBottom, previewY = -1, themeRowY = -1;

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
        themeRowY = -1;
        int step = 24;
        int rowH = 20;

        panelW = Math.min(width - 16, 460);
        panelX = (width - panelW) / 2;
        int colW = (panelW - 24) / 2;
        int left = panelX + 8;
        int right = left + colW + 8;

        // Tabs, styled like Spotify's filter chips
        // Padding shrinks on narrow screens so all tabs fit on one line
        int textTotal = 0;
        for (String t : TABS) textTotal += font.width(t);
        int pad = Math.max(8, Math.min(20, (panelW - textTotal - 6 * (TABS.length - 1)) / TABS.length));
        int chipX = panelX;
        for (int i = 0; i < TABS.length; i++) {
            int w = font.width(TABS[i]) + pad;
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
            case 1 -> y = hypixelTab(left, right, colW, rowH, step, y);
            case 2 -> y = messageTab(left, right, colW, rowH, step, y);
            case 3 -> y = overlayTab(left, right, colW, rowH, step, y);
            case 4 -> y = ircTab(left, right, colW, rowH, step, y);
            default -> y = keysTab(left, right, colW, rowH, step, y);
        }
        panelBottom = y + 4;

        int buttonY = Math.min(height - 26, panelBottom + 8);
        int buttonW = Math.min(150, (panelW - 24) / 2);
        addRenderableWidget(new SpotifyUi.Button(width / 2 - buttonW - 4, buttonY, buttonW, 20,
                SpotifyChatClient.get().isLoggedIn() ? "Log out phone/web" : "Connect phone/web",
                false, this::toggleLogin));
        addRenderableWidget(new SpotifyUi.Button(width / 2 + 4, buttonY, buttonW, 20, "Done", true, this::onClose));
    }

    private int generalTab(int left, int right, int colW, int rowH, int step, int y) {
        toggle(left, y, colW, rowH, "Mod enabled",
                "Turn off to make the mod ignore every !spotify, !music and !jam.",
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

    /** Hypixel chats: guild (/gc), party (/pc) and SkyBlock co-op (/cc), three switches per row */
    private int hypixelTab(int left, int right, int colW, int rowH, int step, int y) {
        int thirdW = (panelW - 16 - 16) / 3;
        int middle = left + thirdW + 8, last = middle + thirdW + 8;
        toggle(left, y, thirdW, rowH, "/gc !spotify",
                "Typing /gc !spotify (or /gc !music) shares your song in guild chat.",
                () -> cfg.guildChat, v -> cfg.guildChat = v);
        toggle(middle, y, thirdW, rowH, "/pc !spotify",
                "Typing /pc !spotify (or /pc !music) shares your song in party chat.",
                () -> cfg.partyChat, v -> cfg.partyChat = v);
        toggle(last, y, thirdW, rowH, "/cc !spotify",
                "Typing /cc !spotify (or /cc !music) shares your song in SkyBlock co-op chat.",
                () -> cfg.coopChat, v -> cfg.coopChat = v);
        y += step;
        toggle(left, y, thirdW, rowH, "Answer guild",
                "When a guild member says !spotify or !music, your song is sent to guild chat.",
                () -> cfg.answerGuild, v -> cfg.answerGuild = v);
        toggle(middle, y, thirdW, rowH, "Answer party",
                "When a party member says !spotify or !music, your song is sent to party chat.",
                () -> cfg.answerParty, v -> cfg.answerParty = v);
        toggle(last, y, thirdW, rowH, "Answer co-op",
                "When a co-op member says !spotify or !music, your song is sent to co-op chat.",
                () -> cfg.answerCoop, v -> cfg.answerCoop = v);
        y += step;
        toggle(left, y, colW, rowH, "!jam (Spotify Jam)",
                "!jam, /gc !jam, /pc !jam and /cc !jam share your Jam invite link. "
                        + "In Spotify: start a Jam, click Invite > Copy link, then type !jam.",
                () -> cfg.jamEnabled, v -> cfg.jamEnabled = v);
        toggle(right, y, colW, rowH, "Answer !jam",
                "When a guild, party or co-op member says !jam, your Jam link is sent back "
                        + "(only after you shared it once this session).",
                () -> cfg.answerJam, v -> cfg.answerJam = v);
        y += step;
        toggle(left, y, colW, rowH, "Jam note: guild/party/co-op",
                "The Jam link goes to IRC. This also posts \"Join my Spotify Jam (link in the Spotify Chat IRC)\" "
                        + "in guild, party or co-op chat when you use /gc !jam, /pc !jam or /cc !jam.",
                () -> cfg.jamNoteGuildParty, v -> cfg.jamNoteGuildParty = v);
        toggle(right, y, colW, rowH, "Jam note: open chat",
                "Same note in open chat when you type !jam there. Off: nothing goes to open chat, "
                        + "the link only goes to IRC.",
                () -> cfg.jamNoteAllChat, v -> cfg.jamNoteAllChat = v);
        y += step;
        addRenderableWidget(new ValueSlider(left, y, panelW - 16, rowH, 60,
                "Answer cooldown: 60s",
                "Minimum time between answering other players' !spotify / !jam, so you don't get muted for spam.",
                () -> cfg.answerCooldownSeconds, v -> cfg.answerCooldownSeconds = v,
                v -> "Answer cooldown: " + v + "s"));
        y += step + 12;
        box(left, y, panelW - 16, rowH, "Text before the Jam link",
                cfg.jamPrefix, v -> cfg.jamPrefix = v);
        return y + rowH;
    }

    private int overlayTab(int left, int right, int colW, int rowH, int step, int y) {
        toggle(left, y, colW, rowH, "Show overlay",
                "A \"now playing\" card on your screen. Also: /spotify overlay, or pick a key in Controls.",
                () -> cfg.overlayEnabled, v -> cfg.overlayEnabled = v);
        toggle(right, y, colW, rowH, "Album cover",
                "Show the album cover on the card.",
                () -> cfg.overlayShowCover, v -> cfg.overlayShowCover = v);
        y += step;
        toggle(left, y, colW, rowH, "Album name",
                "Show the album name under the artist.",
                () -> cfg.overlayShowAlbum, v -> cfg.overlayShowAlbum = v);
        toggle(right, y, colW, rowH, "Show when paused",
                "Keep the card on screen while Spotify is paused (until it counts as \"not listening\").",
                () -> cfg.overlayShowWhenPaused, v -> cfg.overlayShowWhenPaused = v);
        y += step;
        toggle(left, y, colW, rowH, "Album colors",
                "Color the card like the album cover: a gradient of the cover's main colors, "
                        + "and its liveliest color for the bar and text. Off: dark gray with your menu color.",
                () -> cfg.overlayAlbumColors, v -> cfg.overlayAlbumColors = v);
        addRenderableWidget(new SpotifyUi.Button(right, y, colW, rowH, "Move overlay", false,
                () -> Mc.setScreen(new OverlayPositionScreen(this))));
        y += step;
        addRenderableWidget(new ValueSlider(left, y, panelW - 16, rowH, 150,
                "Overlay size: 200%",
                "How big the card is. You can also scroll while moving it.",
                () -> cfg.overlayScale - 50, v -> cfg.overlayScale = v + 50,
                v -> "Overlay size: " + (v + 50) + "%"));
        y += step;
        // Menu color: a row with the name on the left and a swatch per preset on the right
        themeRowY = y;
        int sw = 18, gap = 4;
        int sx = panelX + panelW - 8 - 6 - SpotifyUi.THEMES.size() * (sw + gap) + gap;
        for (SpotifyUi.Theme t : SpotifyUi.THEMES) {
            Swatch s = new Swatch(sx, y + (rowH - 12) / 2, sw, 12, t);
            s.setTooltip(Tooltip.create(Component.literal(t.name())));
            addRenderableWidget(s);
            sx += sw + gap;
        }
        return y + rowH;
    }

    private int ircTab(int left, int right, int colW, int rowH, int step, int y) {
        SpotifyChatClient client = SpotifyChatClient.get();
        toggle(left, y, panelW - 16, rowH, "IRC chat with other Spotify Chat users",
                "Chat outside the Minecraft server, and share Jam links there, because servers like Hypixel "
                        + "punish links in chat. Everyone is in " + IrcClient.CHANNEL + " on Rizon (encrypted); "
                        + "your IP is hidden and others see your Minecraft name.",
                () -> cfg.ircEnabled, v -> {
                    cfg.ircEnabled = v;
                    client.applyIrcSettings();
                });
        y += step;
        toggle(left, y, panelW - 16, rowH, "Guild IRC (automatic for guild members)",
                "A private IRC for your Hypixel guild, if your guild has one. The Spotify Chat bot checks that "
                        + "you're in the guild (your Minecraft name is confirmed with Mojang, the same check a "
                        + "server does when you join) and lets you in. Nobody else can join.",
                () -> cfg.guildIrcEnabled, v -> {
                    cfg.guildIrcEnabled = v;
                    client.irc().setGuildEnabled(v);
                });
        y += step + 2;
        IrcClient irc = client.irc();
        String status = switch (irc.status()) {
            case CONNECTED -> "Connected as " + irc.nick() + " in " + irc.channel() + " (" + irc.online() + " online)"
                    + (irc.inGuild() ? ", guild IRC: " + irc.guildOnline() + " online" : "");
            case CONNECTING -> "Connecting to " + IrcClient.SERVER + "...";
            case OFF -> "Off";
        };
        labels.add(new Label(status, left + 2, y));
        y += 12;
        labels.add(new Label("Press [ or type /irc <message> to write. Guild IRC: ] or /girc <message>.", left + 2, y));
        return y + 10;
    }

    private int keysTab(int left, int right, int colW, int rowH, int step, int y) {
        List<KeyMapping> keys = SpotifyChatClient.get().keys();
        for (int i = 0; i < keys.size(); i++) {
            int x = i % 2 == 0 ? left : right;
            addRenderableWidget(new KeyButton(x, y, colW, rowH, keys.get(i)));
            if (i % 2 == 1) y += step;
        }
        if (keys.size() % 2 == 1) y += step;
        y += 2;
        labels.add(new Label("Click a key, then press the new key. Esc = no key. Works in Spotify's desktop app.",
                left + 2, y));
        y += 18;

        toggle(left, y, colW, rowH, "Update notifications",
                "Tell me in chat when a new Spotify Chat version is out.",
                () -> cfg.updateNotify, v -> cfg.updateNotify = v);
        toggle(right, y, colW, rowH, "Auto-update",
                "Download new versions automatically (from the official GitHub page). "
                        + "They're installed when you close Minecraft.",
                () -> cfg.autoUpdate, v -> cfg.autoUpdate = v);
        y += step + 2;
        UpdateChecker.Release latest = UpdateChecker.latest();
        String status = "You have " + UpdateChecker.currentVersion() + switch (UpdateChecker.state()) {
            case AVAILABLE -> ", " + latest.version() + " is out";
            case INSTALLED_ON_RESTART -> ", " + latest.version() + " installs when you close Minecraft";
            case FAILED -> ", " + latest.version() + " is out (auto-update failed)";
            case NONE -> "";
        };
        labels.add(new Label(status, left + 2, y));
        return y + 10;
    }

    /** The key mapping waiting for a key press, null = none */
    private KeyMapping listening;

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (listening != null) {
            listening.setKey(event.key() == InputConstants.KEY_ESCAPE ? InputConstants.UNKNOWN : InputConstants.getKey(event));
            KeyMapping.resetMapping();
            minecraft.options.save();
            listening = null;
            return true;
        }
        // The menu key (F4) closes the menu again, unless you're typing in a text field
        if (!(getFocused() instanceof EditBox) && SpotifyChatClient.get().menuKey().matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    /** Key name on the left, the bound key in a pill on the right; red when another control uses it too. */
    private class KeyButton extends AbstractButton {
        private final KeyMapping mapping;

        KeyButton(int x, int y, int w, int h, KeyMapping mapping) {
            super(x, y, w, h, Component.translatable(mapping.getName()));
            this.mapping = mapping;
        }

        @Override
        public void onPress(InputWithModifiers input) {
            listening = listening == mapping ? null : mapping;
        }

        private boolean conflict() {
            if (mapping.isUnbound()) return false;
            for (KeyMapping other : minecraft.options.keyMappings) {
                // F3 shortcuts (like F3+F4) only work while F3 is held, so they don't really clash
                if (other.getCategory() == KeyMapping.Category.DEBUG) continue;
                if (other != mapping && other.same(mapping)) return true;
            }
            return false;
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            pill(g, x, y, w, h, isHovered() ? ROW_HOVER : ROW);
            g.text(font, getMessage(), x + 6, y + (h - 8) / 2, WHITE, false);

            boolean waiting = listening == mapping;
            String key = waiting ? "Press a key..." : mapping.isUnbound() ? "Not set"
                    : mapping.getTranslatedKeyMessage().getString();
            int kw = Math.max(40, font.width(key) + 12), kx = x + w - kw - 4, ky = y + 3;
            int bg = waiting ? SpotifyUi.accent() : conflict() ? 0xFF8B1E1E : ELEVATED;
            pill(g, kx, ky, kw, h - 6, bg);
            int color = waiting ? BLACK : mapping.isUnbound() ? GRAY : WHITE;
            g.text(font, key, kx + (kw - font.width(key)) / 2, y + (h - 8) / 2, color, false);
            if (conflict() && isHovered()) {
                g.setTooltipForNextFrame(font, Component.literal("This key is also used by another control"),
                        mouseX, mouseY);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
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
        g.fill(0, 24, width, 25, SpotifyUi.accent());
        // Panel
        g.fill(panelX, panelTop, panelX + panelW, panelBottom, PANEL);
        // Text field backgrounds
        for (EditBox box : boxes) {
            int x = box.getX() - 6, y = box.getY() - 5, w = box.getWidth() + 12, h = 19;
            pill(g, x, y, w, h, box.isFocused() ? SpotifyUi.accent() : ELEVATED);
            pill(g, x + 1, y + 1, w - 2, h - 2, ELEVATED);
        }
        // Menu color row (the swatches are widgets on top of it)
        if (themeRowY >= 0) {
            pill(g, panelX + 8, themeRowY, panelW - 16, 20, ROW);
            g.text(font, "Menu color: " + SpotifyUi.theme().name(), panelX + 14, themeRowY + 6, WHITE, false);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        drawLogo(g, panelX + 8, 6);
        g.text(font, Component.literal("Spotify Chat").withStyle(ChatFormatting.BOLD), panelX + 24, 8, WHITE, false);
        String status = cfg.enabled ? "ON" : "OFF";
        g.text(font, status, panelX + panelW - 8 - font.width(status), 8, cfg.enabled ? SpotifyUi.accent() : GRAY,
                false);

        for (Label l : labels) {
            g.text(font, l.text(), l.x(), l.y(), GRAY, false);
        }

        if (previewY >= 0) {
            int x = panelX + 8 + font.width("Preview: ");
            String preview = fit(SpotifyChatClient.get().formatTrack(SAMPLE), panelX + panelW - 8 - x);
            g.text(font, "Preview: ", panelX + 8, previewY, GRAY, false);
            g.text(font, preview, x, previewY, SpotifyUi.accent(), false);
        }
    }

    private String fit(String text, int maxWidth) {
        return SpotifyUi.fit(font, text, maxWidth);
    }

    private static void drawLogo(GuiGraphicsExtractor g, int x, int y) {
        SpotifyUi.logo(g, x, y);
    }

    private static void pill(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        SpotifyUi.pill(g, x, y, w, h, color);
    }

    @Override
    public void onClose() {
        cfg.save();
        Mc.setScreen(parent);
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
            pill(g, sx, sy, sw, sh, on ? (isHovered() ? SpotifyUi.accentLight() : SpotifyUi.accent()) : OFF);
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
            pill(g, tx, ty, Math.max(2, filled), 4, isHovered() ? SpotifyUi.accentLight() : SpotifyUi.accent());
            pill(g, tx + filled - 4, ty - 2, 8, 8, WHITE);
        }
    }

    /** A color preset to click; the picked one has a white ring around it. */
    private class Swatch extends AbstractButton {
        private final SpotifyUi.Theme theme;

        Swatch(int x, int y, int w, int h, SpotifyUi.Theme theme) {
            super(x, y, w, h, Component.literal(theme.name()));
            this.theme = theme;
        }

        @Override
        public void onPress(InputWithModifiers input) {
            cfg.menuTheme = theme.name();
            cfg.save();
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            if (SpotifyUi.theme() == theme) {
                pill(g, x - 2, y - 2, w + 4, h + 4, WHITE);
                pill(g, x - 1, y - 1, w + 2, h + 2, ROW);
            }
            pill(g, x, y, w, h, isHovered() ? theme.light() : theme.accent());
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
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
}
