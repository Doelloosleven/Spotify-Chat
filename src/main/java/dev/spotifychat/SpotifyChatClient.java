package dev.spotifychat;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import com.mojang.blaze3d.platform.InputConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * In chat (!music works everywhere !spotify does):
 *   !spotify                  share the current song (your "!spotify" is shown too, if enabled)
 *   /gc, /pc, /cc !spotify       share it in guild / party / SkyBlock co-op chat
 *   !jam, /gc /pc /cc !jam       share your Spotify Jam invite link (copied in Spotify); the link itself
 *                                goes to the IRC channel, since servers like Hypixel punish links
 *   [ key, /irc <message>        write a message to IRC (other Spotify Chat users over Rizon, not via the
 *                                server); T still opens normal chat
 *   Someone else's "!spotify" / "!jam" in guild, party or co-op chat is answered in that same channel.
 *
 * Overlay: a "now playing" card on the HUD; /spotify overlay or a key from Controls turns it on/off.
 * Arrow keys (changeable): left = previous song, right = next song, down = play/pause.
 *
 * The song is read from the Spotify desktop app on this PC, so no login is needed.
 *
 * Client-side commands (never reach the server):
 *   /spotify, F4 key          open the settings menu (F4 again closes it)
 *   /spotify overlay          show/hide the overlay
 *   /spotify pause|next|previous   control the Spotify app (also as keys you pick in the menu)
 *   /spotify jam [link|clear] show, set or forget your Jam link
 *   /spotify public|private   who sees the song
 *   /spotify login|logout     optional: Web API (to share from phone / web player)
 *   /spotify id <id>          Client ID for the Web API login
 *   /spotify help
 */
public class SpotifyChatClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("SpotifyChat");
    private static final int MAX_CHAT_LENGTH = 256;
    private static final String DASHBOARD_URL = "https://developer.spotify.com/dashboard";

    /**
     * /gc, /pc, /cc and Hypixel's long forms (/guild chat, /g chat, /party chat, /p chat)
     * followed by !spotify, !music or !jam
     */
    private static final Pattern OWN_CHANNEL_COMMAND = Pattern.compile(
            "(gc|pc|cc|(?:guild|g|party|p) chat)\\s+!(spotify|music|jam)", Pattern.CASE_INSENSITIVE);
    /** Hypixel: "Guild > [MVP++] Name [Admin]: !spotify", "Party > [VIP] Name: !jam", "Co-op > Name: !music" */
    private static final Pattern CHANNEL_MESSAGE = Pattern.compile(
            "(Guild|Party|Co-op) > (?:\\[[^\\]]+\\] )?(\\w{1,16})(?: \\[[^\\]]+\\])?: !(spotify|music|jam)",
            Pattern.CASE_INSENSITIVE);
    /** Spotify Jam invite links: spotify.link short links or open.spotify.com/socialsession/... */
    private static final Pattern JAM_LINK = Pattern.compile(
            "https?://(?:spotify\\.link/\\w+|open\\.spotify\\.com/(?:intl-[\\w-]+/)?socialsession/[\\w-]+)\\S*",
            Pattern.CASE_INSENSITIVE);

    private static SpotifyChatClient instance;

    private ModConfig config;
    private SpotifyClient spotify;
    private volatile boolean busy = false;
    private long lastRemoteShare = 0;
    /** When your own !spotify / !jam was last handled, so Hypixel's echo of it doesn't share a second time */
    private final Map<String, Long> lastOwnShare = new ConcurrentHashMap<>();
    private static final long OWN_ECHO_WINDOW_MS = 5_000;
    /** Minimum time between the "!spotify" and the song, so they reach the server in order */
    private static final long SEND_DELAY_MS = 1_000;
    private long shareStartedAt = 0;
    private final Queue<PendingSend> sendQueue = new ConcurrentLinkedQueue<>();
    private boolean openMenuNextTick = false;
    /** Your Jam invite link from the last !jam (only kept while the game runs) */
    private String jamLink;

    // Keys
    private KeyMapping overlayKey, playPauseKey, nextKey, previousKey, ircKey, menuKey;

    // IRC
    /** Chat messages starting with this go to the IRC channel instead of the server */
    public static final String IRC_PREFIX = "[IRC] ";
    private IrcClient irc;
    /** True while a chat opened with the IRC key ([) is open: what you send from it goes to IRC */
    private volatile boolean ircMode;
    private boolean updateNotified = false;
    /** Auto-update was just switched off by the new default: say so once, after joining a world */
    private boolean autoUpdateNotice = false;

    // Overlay
    private volatile SpotifyClient.Track overlayTrack;
    private volatile SpotifyClient.Track webOverlayTrack;
    private volatile boolean webPolling;
    private long lastWebPoll;
    private int ticks;

    public static SpotifyChatClient get() {
        return instance;
    }

    public ModConfig config() {
        return config;
    }

    /** The song the overlay shows, null = nothing */
    public SpotifyClient.Track overlayTrack() {
        return overlayTrack;
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        config = ModConfig.load();
        autoUpdateNotice = config.applyNewDefaults();
        spotify = new SpotifyClient(config);
        LocalSpotify.startWatching();

        // Return false = cancel the message so it never reaches the server.
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            String trimmed = message.trim();
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (config.ircEnabled && lower.startsWith(IRC_PREFIX.toLowerCase(Locale.ROOT).trim())) {
                sendIrc(trimmed.substring(IRC_PREFIX.trim().length()).trim());
                return false; // IRC messages never go to the server
            }
            boolean trigger = config.enabled
                    && (lower.equals("!jam") || lower.equals("!music") || lower.startsWith("!spotify"));
            if (ircMode && config.ircEnabled && !trigger) {
                sendIrc(trimmed); // chat opened with [ : this message goes to IRC (!spotify / !jam work as usual)
                return false;
            }
            if (!config.enabled) return true;
            if (lower.equals("!jam")) {
                if (!config.jamEnabled) return true;
                return ownJam(null) && showTriggerPublicly() && jamGoesToServerChat(null);
            }
            if (lower.equals("!music")) return ownShare(null) && showTriggerPublicly();
            if (!lower.startsWith("!spotify")) return true;
            String rest = trimmed.substring("!spotify".length()).trim();
            if (!rest.isEmpty() && !Character.isWhitespace(trimmed.charAt("!spotify".length()))) {
                return true; // e.g. "!spotifyfan" isn't our command
            }
            if (!rest.isEmpty()) {
                // Old-style "!spotify login" etc.: keep it out of public chat and point to the / command
                info("Settings moved to /spotify " + rest.split("\\s+")[0] + "  (see /spotify help)",
                        ChatFormatting.YELLOW);
                return false;
            }
            return ownShare(null) && showTriggerPublicly();
        });

        // "/gc !spotify", "/cc !music" or "/pc !jam": let the command through, then answer in that same channel.
        // Some mods send typed commands in a way this doesn't see; the chat echo below covers that.
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            if (!config.enabled) return true;
            Matcher m = OWN_CHANNEL_COMMAND.matcher(command.trim());
            if (!m.matches()) return true;
            LOGGER.info("Saw own command: /{}", command);
            String channel = channelOf(m.group(1));
            if (!sharesTo(channel)) return true; // turned off: plain message
            if (m.group(2).equalsIgnoreCase("jam")) {
                if (!config.jamEnabled) return true;
                return ownJam(channel) && showTriggerPublicly() && jamGoesToServerChat(channel);
            }
            return ownShare(channel) && showTriggerPublicly();
        });

        // "!spotify" / "!music" / "!jam" shows up in guild, party or co-op chat: answer in that channel only
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay || !config.enabled || !config.publicMessages) return;
            Matcher m = CHANNEL_MESSAGE.matcher(ChatFormatting.stripFormatting(message.getString()).trim());
            if (!m.matches()) return;
            String channel = channelOf(m.group(1));
            boolean jam = m.group(3).equalsIgnoreCase("jam");
            String trigger = jam ? "jam" : "spotify"; // !music is the same as !spotify
            if (jam && !config.jamEnabled) return;
            String me = Minecraft.getInstance().getUser().getName();
            LOGGER.info("Saw !{} in {} chat from {} (you are {})", m.group(3), m.group(1), m.group(2), me);

            if (m.group(2).equalsIgnoreCase(me)) {
                // Your own trigger coming back from Hypixel. Skip it if it was already handled when sending.
                if (System.currentTimeMillis() - lastOwnShare.getOrDefault(trigger, 0L) < OWN_ECHO_WINDOW_MS) {
                    LOGGER.info("Own !{} echo ignored: already handled when sending", trigger);
                    return;
                }
                if (!sharesTo(channel)) return;
                if (jam) ownJam(channel);
                else ownShare(channel);
                return;
            }
            if (!answers(channel)) return;
            if (jam && (!config.answerJam || jamLink == null)) return; // only once you've shared a Jam

            long now = System.currentTimeMillis();
            if (now - lastRemoteShare < config.answerCooldownSeconds * 1000L) return; // don't get muted for spam
            lastRemoteShare = now;
            if (jam) answerJam(channel);
            else shareNowPlaying(channel);
        });

        // Overlay on the HUD
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("spotifychat", "overlay"), new SpotifyOverlay());

        // Keys: change them in the Spotify menu > Keys & Updates, or Options > Controls.
        // Minecraft's own key codes, since 26.3 numbers keys differently than 26.2.
        KeyMapping.Category category =
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("spotifychat", "main"));
        playPauseKey = registerKey("key.spotifychat.play_pause", InputConstants.KEY_DOWN, category);
        nextKey = registerKey("key.spotifychat.next", InputConstants.KEY_RIGHT, category);
        previousKey = registerKey("key.spotifychat.previous", InputConstants.KEY_LEFT, category);
        overlayKey = registerKey("key.spotifychat.toggle_overlay", InputConstants.UNKNOWN.getValue(), category);
        ircKey = registerKey("key.spotifychat.irc", InputConstants.KEY_LBRACKET, category);
        menuKey = registerKey("key.spotifychat.menu", InputConstants.KEY_F4, category);

        irc = new IrcClient(m -> runOnGame(() -> showIrc(m)), notice -> runOnGame(() -> ircInfo(notice)));

        // Chat opened with [: green "IRC" label above the input, and back to normal once it closes
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (screen instanceof ChatScreen) {
                ScreenEvents.remove(screen).register(s -> ircMode = false);
                ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, delta) -> {
                    if (!ircMode()) return;
                    var font = Minecraft.getInstance().font;
                    String label = "IRC";
                    String hint = "this message goes to " + irc.channel();
                    int x = 2, y = s.height - 27, lw = font.width(label) + 8;
                    SpotifyUi.pill(g, x, y, lw, 11, SpotifyUi.accent());
                    g.text(font, Component.literal(label).withStyle(ChatFormatting.BOLD), x + 4, y + 2,
                            SpotifyUi.BLACK, false);
                    SpotifyUi.pill(g, x + lw + 2, y, font.width(hint) + 8, 11, 0xC0121212);
                    g.text(font, hint, x + lw + 6, y + 2, SpotifyUi.GRAY, false);
                });
            }
        });

        UpdateChecker.start(config);

        // Chat closes itself right after a command runs, so open the menu on the next tick
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (!keyDefaultsChecked) {
                keyDefaultsChecked = true; // options.txt is loaded by now
                applyNewKeyDefaults(mc);
            }
            sendPending(mc);
            while (overlayKey.consumeClick()) toggleOverlay();
            while (playPauseKey.consumeClick()) control(SpotifyControls.Action.PLAY_PAUSE);
            while (nextKey.consumeClick()) control(SpotifyControls.Action.NEXT);
            while (previousKey.consumeClick()) control(SpotifyControls.Action.PREVIOUS);
            while (ircKey.consumeClick()) openIrcChat(mc);
            while (menuKey.consumeClick()) {
                // F3+F4 is Minecraft's game mode switcher: leave that alone
                if (!mc.options.keyDebugModifier.isDown() && Mc.screen() == null) {
                    Mc.setScreen(new SpotifyConfigScreen(null));
                }
            }
            if (config.ircEnabled && irc.status() == IrcClient.Status.OFF) {
                irc.start(mc.getUser().getName()); // first tick, or turned back on
            }
            if (ticks++ % 10 == 0) updateOverlay();
            if (mc.player != null) notifyUpdateOnce();
            if (openMenuNextTick) {
                openMenuNextTick = false;
                Mc.setScreen(new SpotifyConfigScreen(null));
            }
        });

        // /irc <message>: one message to IRC (same as the [ key)
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
                literal("irc")
                        .executes(ctx -> run(() -> ircInfo("Usage: /irc <message>, or press [ to write to IRC.")))
                        .then(argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> run(() -> sendIrc(StringArgumentType.getString(ctx, "message")))))));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
                literal("spotify")
                        .executes(ctx -> run(() -> openMenuNextTick = true))
                        .then(literal("menu").executes(ctx -> run(() -> openMenuNextTick = true)))
                        .then(literal("overlay").executes(ctx -> run(this::toggleOverlay)))
                        .then(literal("pause").executes(ctx -> run(() -> control(SpotifyControls.Action.PLAY_PAUSE))))
                        .then(literal("next").executes(ctx -> run(() -> control(SpotifyControls.Action.NEXT))))
                        .then(literal("previous").executes(ctx -> run(() ->
                                control(SpotifyControls.Action.PREVIOUS))))
                        .then(literal("jam")
                                .executes(ctx -> run(this::jamStatus))
                                .then(literal("clear").executes(ctx -> run(() -> {
                                    jamLink = null;
                                    info("Jam link forgotten.", ChatFormatting.GREEN);
                                })))
                                .then(argument("link", StringArgumentType.greedyString())
                                        .executes(ctx -> run(() ->
                                                setJamLink(StringArgumentType.getString(ctx, "link"))))))
                        .then(literal("help").executes(ctx -> run(this::help)))
                        .then(literal("login").executes(ctx -> run(this::login)))
                        .then(literal("logout").executes(ctx -> run(this::logout)))
                        .then(literal("id")
                                .executes(ctx -> run(() -> info("Usage: /spotify id <your Client ID>",
                                        ChatFormatting.YELLOW)))
                                .then(argument("clientId", StringArgumentType.greedyString())
                                        .executes(ctx -> run(() ->
                                                setClientId(StringArgumentType.getString(ctx, "clientId"))))))
                        .then(literal("public").executes(ctx -> run(() -> setPublic(true))))
                        .then(literal("private").executes(ctx -> run(() -> setPublic(false))))));

        LOGGER.info("Spotify Chat loaded. Type /spotify in chat for the menu.");
    }

    /** "gc", "pc" or "cc" for /gc, /guild chat, "Party >", "Co-op >", ... */
    private static String channelOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("g") ? "gc" : n.startsWith("p") ? "pc" : "cc";
    }

    /** Your own /gc, /pc or /cc !spotify is switched on for this channel */
    private boolean sharesTo(String channel) {
        return switch (channel) {
            case "gc" -> config.guildChat;
            case "pc" -> config.partyChat;
            default -> config.coopChat;
        };
    }

    /** Other players' !spotify / !jam in this channel get an answer */
    private boolean answers(String channel) {
        return switch (channel) {
            case "gc" -> config.answerGuild;
            case "pc" -> config.answerParty;
            default -> config.answerCoop;
        };
    }

    /** Shares for your own !spotify and remembers it, so the echo from the server is ignored. */
    private boolean ownShare(String channel) {
        boolean started = shareNowPlaying(channel);
        if (started) lastOwnShare.put("spotify", System.currentTimeMillis());
        return started;
    }

    // ------------------------------------------------------------ Spotify Jam

    /**
     * Your own !jam: takes the Jam invite link from the clipboard (Spotify: Start a Jam > Invite > Copy link),
     * or the one from earlier in this session.
     */
    private boolean ownJam(String channel) {
        String clip = "";
        try {
            clip = Minecraft.getInstance().keyboardHandler.getClipboard();
        } catch (Exception ignored) {
        }
        Matcher m = JAM_LINK.matcher(clip == null ? "" : clip);
        if (m.find()) jamLink = m.group();
        if (jamLink == null) {
            info("No Jam link found. In Spotify: start a Jam, click Invite > Copy link, then type !jam again.",
                    ChatFormatting.YELLOW);
            return false;
        }
        lastOwnShare.put("jam", System.currentTimeMillis());
        return shareJam(channel, jamLink);
    }

    /**
     * Servers like Hypixel punish links in chat, so with IRC connected the link goes to the
     * Spotify Chat IRC channel, and the server chat only gets a note without the link.
     */
    private boolean shareJam(String channel, String link) {
        String prefix = config.jamPrefix.strip();
        String withLink = (prefix.isEmpty() ? "" : prefix + " ") + link;
        if (!config.publicMessages) {
            info(withLink, ChatFormatting.GREEN);
            return true;
        }
        if (ircConnected()) {
            sendIrc(withLink);
            if (jamGoesToServerChat(channel)) {
                String note = (prefix.isEmpty() ? "♫ Join my Spotify Jam" : prefix.replaceAll("[:\\s]+$", ""))
                        + " (link in the Spotify Chat IRC)";
                queueSend(channel, note, false, System.currentTimeMillis() + SEND_DELAY_MS);
            }
            return true;
        }
        if (linksBlockedHere()) {
            info("This server punishes links in chat. Turn on IRC chat in /spotify > IRC to share Jam links.",
                    ChatFormatting.YELLOW);
            return false;
        }
        queueSend(channel, withLink, false, System.currentTimeMillis() + SEND_DELAY_MS);
        return true;
    }

    /** Someone else asked for your Jam: the link goes to IRC (never to a server that blocks links). */
    private void answerJam(String channel) {
        String withLink = (config.jamPrefix.isBlank() ? "" : config.jamPrefix.strip() + " ") + jamLink;
        if (ircConnected()) sendIrc(withLink);
        else if (!linksBlockedHere()) queueSend(channel, withLink, false, System.currentTimeMillis() + SEND_DELAY_MS);
    }

    /** Hypixel warns and punishes for links ("advertising") */
    private static boolean linksBlockedHere() {
        ServerData server = Minecraft.getInstance().getCurrentServer();
        return server != null && server.ip != null && server.ip.toLowerCase(Locale.ROOT).contains("hypixel");
    }

    // -------------------------------------------------------------------- IRC

    public IrcClient irc() {
        return irc;
    }

    public boolean ircMode() {
        return ircMode && config.ircEnabled;
    }

    /** [ key: opens the chat; the message you send from it goes to IRC. T still opens normal chat. */
    private void openIrcChat(Minecraft mc) {
        if (Mc.screen() != null) return;
        if (!config.ircEnabled) {
            Mc.actionBar(Component.literal("IRC chat is off (turn it on in /spotify > IRC)")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }
        ircMode = true; // reset when this chat closes (see the ChatScreen remove listener)
        Mc.openChat();
    }

    /** With IRC on, the Jam link goes to IRC; the server only gets the note if that's switched on. */
    private boolean jamGoesToServerChat(String channel) {
        if (!ircConnected()) return true; // link itself goes to chat (or nothing on Hypixel)
        return channel == null ? config.jamNoteAllChat : config.jamNoteGuildParty;
    }

    private boolean ircConnected() {
        return config.ircEnabled && irc.status() == IrcClient.Status.CONNECTED;
    }

    /** Called by the menu after IRC settings change */
    public void applyIrcSettings() {
        if (!config.ircEnabled) irc.stop();
        else irc.start(Minecraft.getInstance().getUser().getName());
    }

    private void sendIrc(String text) {
        if (text.isBlank()) return;
        if (!ircConnected()) {
            ircInfo(config.ircEnabled ? "Not connected yet, try again in a moment."
                    : "IRC chat is off. Turn it on in /spotify > IRC.");
            return;
        }
        if (IrcClient.outgoing(text).isBlank()) return;
        // Once the server accepts it, the IRC client shows it in chat exactly as it was sent (cleaned and cut
        // to fit), not as typed
        if (irc.send(text) == null) ircInfo("Couldn't send, reconnecting...");
    }

    private static void ircInfo(String text) {
        Mc.chat().addClientSystemMessage(Component.empty()
                .append(Component.literal("[IRC] ").withStyle(ChatFormatting.DARK_GREEN))
                .append(Component.literal(text).withStyle(ChatFormatting.GRAY)));
    }

    private static final Pattern URL = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

    /** "[IRC] Nick: message", with links clickable (Minecraft asks before opening them) */
    private void showIrc(IrcClient.Message m) {
        boolean me = m.nick().equalsIgnoreCase(irc.nick());
        MutableComponent line = Component.empty()
                .append(Component.literal("[IRC] ").withStyle(ChatFormatting.DARK_GREEN))
                .append(Component.literal(m.action() ? "* " + m.nick() + " " : m.nick())
                        .withStyle(me ? ChatFormatting.GREEN : ChatFormatting.AQUA));
        if (!m.action()) line.append(Component.literal(": ").withStyle(ChatFormatting.GRAY));
        Matcher u = URL.matcher(m.text());
        int pos = 0;
        while (u.find()) {
            line.append(Component.literal(m.text().substring(pos, u.start())).withStyle(ChatFormatting.WHITE));
            String url = u.group();
            try {
                URI uri = URI.create(url);
                line.append(Component.literal(url).withStyle(style -> style.withColor(ChatFormatting.GREEN)
                        .withUnderlined(true).withClickEvent(new ClickEvent.OpenUrl(uri))));
            } catch (IllegalArgumentException e) {
                line.append(Component.literal(url).withStyle(ChatFormatting.WHITE));
            }
            pos = u.end();
        }
        line.append(Component.literal(m.text().substring(pos)).withStyle(ChatFormatting.WHITE));
        Mc.chat().addClientSystemMessage(line);
    }

    private void setJamLink(String text) {
        Matcher m = JAM_LINK.matcher(text);
        if (!m.find()) {
            info("That isn't a Spotify Jam link (spotify.link/... or open.spotify.com/socialsession/...).",
                    ChatFormatting.YELLOW);
            return;
        }
        jamLink = m.group();
        info("Jam link saved. Type !jam, /gc !jam or /pc !jam to share it.", ChatFormatting.GREEN);
    }

    private void jamStatus() {
        if (jamLink == null) {
            info("No Jam link yet. In Spotify: start a Jam, click Invite > Copy link, then type !jam.",
                    ChatFormatting.YELLOW);
        } else {
            info("Your Jam link: " + jamLink + "  (/spotify jam clear to forget it)", ChatFormatting.GREEN);
        }
    }

    // ------------------------------------------------------- Keys & controls

    private static KeyMapping registerKey(String name, int defaultKey, KeyMapping.Category category) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(name, defaultKey, category));
    }

    /** 1: arrow keys for previous / next / play-pause (1.2.0) */
    private static final int KEY_DEFAULTS_VERSION = 1;
    private boolean keyDefaultsChecked = false;

    /**
     * Minecraft saves "no key" for every control in options.txt, so players who had the mod before
     * the media keys got defaults would never get them. Once, give those unbound keys their default,
     * unless another control already uses that key.
     */
    private void applyNewKeyDefaults(Minecraft mc) {
        if (config.keyDefaultsVersion >= KEY_DEFAULTS_VERSION) return;
        boolean changed = false;
        for (KeyMapping key : List.of(previousKey, nextKey, playPauseKey)) {
            if (!key.isUnbound()) continue;
            key.setKey(key.getDefaultKey());
            boolean taken = false;
            for (KeyMapping other : mc.options.keyMappings) {
                if (other != key && other.same(key)) taken = true;
            }
            if (taken) key.setKey(InputConstants.UNKNOWN);
            else changed = true;
        }
        if (changed) {
            KeyMapping.resetMapping();
            mc.options.save();
            LOGGER.info("Gave the Spotify media controls their new default keys (arrow keys)");
        }
        config.keyDefaultsVersion = KEY_DEFAULTS_VERSION;
        config.save();
    }

    /** Spotify Chat's keys, in the order the menu shows them */
    public List<KeyMapping> keys() {
        return List.of(menuKey, ircKey, playPauseKey, nextKey, previousKey, overlayKey);
    }

    /** Opens / closes the Spotify Chat menu (F4 by default) */
    public KeyMapping menuKey() {
        return menuKey;
    }

    /** Play/pause, next or previous in the Spotify app, with a short confirmation above the hotbar. */
    void control(SpotifyControls.Action action) {
        SpotifyControls.send(action).thenAccept(ok -> runOnGame(() -> Mc.actionBar(
                Component.literal(ok ? "♫ " + action.label : "Spotify isn't open")
                        .withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.YELLOW))));
    }

    // ---------------------------------------------------------------- Updates

    /** Once per game start, after joining a world: tell about a new version. */
    private void notifyUpdateOnce() {
        if (autoUpdateNotice) {
            autoUpdateNotice = false;
            info("♫ Spotify Chat auto-update is now off, so new versions aren't installed by themselves. "
                    + "Want it back? Turn on Auto-update in /spotify > Keys & Updates.", ChatFormatting.YELLOW);
        }
        UpdateChecker.State state = UpdateChecker.state();
        if (updateNotified || state == UpdateChecker.State.NONE) return;
        updateNotified = true;
        UpdateChecker.Release r = UpdateChecker.latest();
        String have = UpdateChecker.currentVersion();
        switch (state) {
            case INSTALLED_ON_RESTART -> info("♫ Spotify Chat " + r.version() + " was downloaded (you have " + have
                    + "). It's installed when you close Minecraft.", ChatFormatting.GREEN);
            case FAILED -> {
                info("♫ Spotify Chat " + r.version() + " is out, but auto-update didn't work: "
                        + UpdateChecker.failReason(), ChatFormatting.YELLOW);
                link(r.pageUrl(), "Download it here");
            }
            default -> {
                if (!config.updateNotify) return;
                info("♫ Spotify Chat " + r.version() + " is out (you have " + have + ").", ChatFormatting.GREEN);
                link(r.pageUrl(), "Download / what's new");
            }
        }
    }

    private static void link(String url, String text) {
        Mc.chat().addClientSystemMessage(
                Component.literal("[" + text + "]").withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))));
    }

    // ---------------------------------------------------------------- Overlay

    private void toggleOverlay() {
        config.overlayEnabled = !config.overlayEnabled;
        config.save();
        Mc.actionBar(Component.literal("Spotify overlay " + (config.overlayEnabled ? "on" : "off")));
    }

    /** Every half second: what the overlay should show. Never waits for the network. */
    private void updateOverlay() {
        if (!config.overlayEnabled) {
            overlayTrack = null;
            return;
        }
        SpotifyClient.Track t = LocalSpotify.latest().orElse(null);
        if ((t == null || !t.playing()) && config.useWebApi && spotify.isLoggedIn()) {
            // Phone / web player: ask Spotify every 5 seconds
            long now = System.currentTimeMillis();
            if (!webPolling && now - lastWebPoll > 5_000) {
                webPolling = true;
                lastWebPoll = now;
                spotify.fetchNowPlaying().whenComplete((r, e) -> {
                    if (e == null) webOverlayTrack = r.orElse(null);
                    webPolling = false;
                });
            }
            SpotifyClient.Track web = webOverlayTrack;
            if (web != null && (t == null || web.playing())) t = web;
        }
        if (t == null) {
            overlayTrack = null;
            return;
        }
        TrackInfoLookup.Info info = needsInfo(t) ? TrackInfoLookup.peek(t.artist(), t.song()) : null;
        overlayTrack = info == null ? t : withInfo(t, info);
    }

    private boolean showTriggerPublicly() {
        return config.publicMessages && config.showTrigger;
    }

    private interface Action {
        void run() throws Exception;
    }

    private static int run(Action action) {
        try {
            action.run();
        } catch (Exception e) {
            LOGGER.error("/spotify failed", e);
            info("Something went wrong: " + e.getMessage(), ChatFormatting.RED);
        }
        return Command.SINGLE_SUCCESS;
    }

    private void setClientId(String id) {
        config.clientId = id.trim();
        config.save();
        spotify.logout(); // the old login belongs to the old Client ID
        info("Client ID saved. Now type /spotify login", ChatFormatting.GREEN);
    }

    private void setPublic(boolean publicMessages) {
        config.publicMessages = publicMessages;
        config.save();
        info(publicMessages ? "Songs will now be sent to everyone." : "Songs will now only be shown to you.",
                ChatFormatting.GREEN);
    }

    private void help() {
        info("--- Spotify Chat ---", ChatFormatting.GREEN);
        info("!spotify or !music  - share what's playing in your Spotify app", ChatFormatting.GRAY);
        info("/gc, /pc, /cc !spotify  - share it in guild / party / co-op chat", ChatFormatting.GRAY);
        info("!jam, /gc /pc /cc !jam  - share your Spotify Jam link (copy it in Spotify first)",
                ChatFormatting.GRAY);
        info("/spotify overlay  - show/hide the song overlay", ChatFormatting.GRAY);
        info("Arrow keys: left = previous, right = next, down = play/pause (change them in the menu)",
                ChatFormatting.GRAY);
        info("/spotify pause | next | previous  - the same as commands", ChatFormatting.GRAY);
        info("[  - write a message to IRC (other Spotify Chat users, outside the server)", ChatFormatting.GRAY);
        info("/irc <message>  - same, as a command", ChatFormatting.GRAY);
        info("/spotify or F4  - open the settings menu", ChatFormatting.GRAY);
        info("/spotify public / private  - who sees the song (now: "
                + (config.publicMessages ? "public" : "private") + ")", ChatFormatting.GRAY);
        info("Optional, to share from your phone too: /spotify id <clientId>, /spotify login",
                ChatFormatting.DARK_GRAY);
    }

    public boolean isLoggedIn() {
        return spotify.isLoggedIn();
    }

    public void logout() {
        spotify.logout();
        info("Logged out of the Spotify Web API. The desktop app is still used.", ChatFormatting.GREEN);
    }

    public void login() throws Exception {
        if (config.clientId.isBlank()) {
            info("You don't need to log in: !spotify already works with the Spotify app on this PC.",
                    ChatFormatting.GREEN);
            info("Login is only for sharing from your phone/web player. It needs a Client ID, "
                    + "opening the Spotify developer dashboard...", ChatFormatting.YELLOW);
            info("Create an app there, then type /spotify id <clientId> and /spotify login  (see README)",
                    ChatFormatting.YELLOW);
            openInBrowser(DASHBOARD_URL, "Open the Spotify dashboard");
            return;
        }
        SpotifyClient.LoginSession session = spotify.startLogin();
        info("Opening Spotify login in your browser...", ChatFormatting.GREEN);
        openInBrowser(session.url(), "Click here if the login page didn't open");
        session.result().whenComplete((v, err) -> runOnGame(() -> {
            if (err == null) {
                info("Spotify connected! Type !spotify to share your song.", ChatFormatting.GREEN);
            } else {
                info("Spotify login failed: " + rootMessage(err), ChatFormatting.RED);
            }
        }));
    }

    /** Paused longer than the "paused → not listening" setting? Unknown pause time counts as just paused. */
    boolean pausedTooLong(SpotifyClient.Track t) {
        int minutes = config.pausedToNothingMinutes;
        if (minutes >= ModConfig.PAUSED_NEVER) return false;
        if (minutes <= 0) return true;
        return t.pausedSince() > 0 && System.currentTimeMillis() - t.pausedSince() >= minutes * 60_000L;
    }

    /** Builds the chat line for a track using the current settings (also used for the menu preview). */
    public String formatTrack(SpotifyClient.Track t) {
        List<String> parts = new ArrayList<>();
        boolean featuresShown = config.showArtist && config.showFeatures && t.artists().size() > 1;
        String song = featuresShown ? withoutFeatTag(t.song(), t.features()) : t.song();
        if (config.showSong) parts.add(song);
        if (config.showArtist && !t.artists().isEmpty()) {
            parts.add(config.showFeatures ? String.join(", ", t.artists()) : t.artist());
        }
        if (config.showAlbum && !t.album().isBlank()) parts.add(t.album());
        if (parts.isEmpty()) parts.add(t.song()); // everything off: at least say which song

        String text = String.join(config.separator, parts);
        if (!config.prefix.isBlank()) text = config.prefix.strip() + " " + text;
        if (!t.playing()) text += config.pausedSuffix;
        return text;
    }

    private static final Pattern FEAT_TAG = Pattern.compile("\\s*[(\\[](?:feat\\.?|ft\\.|with) ([^)\\]]*)[)\\]]",
            Pattern.CASE_INSENSITIVE);

    /**
     * "Get Lucky (feat. Pharrell Williams)" -> "Get Lucky" when those artists are listed anyway.
     * Only removed if the brackets name a featured artist, so "Song (With Love)" stays intact.
     */
    private static String withoutFeatTag(String song, List<String> features) {
        Matcher m = FEAT_TAG.matcher(song);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String inside = m.group(1).toLowerCase(Locale.ROOT);
            boolean namesFeature = features.stream().anyMatch(f -> inside.contains(f.toLowerCase(Locale.ROOT)));
            m.appendReplacement(out, namesFeature ? "" : Matcher.quoteReplacement(m.group()));
        }
        m.appendTail(out);
        return out.toString().strip();
    }

    /**
     * The desktop app only gives the main artist and no album, so fill those in from the lookup
     * (usually already cached). Spotify's own spelling of the main artist is kept.
     */
    private SpotifyClient.Track addInfo(SpotifyClient.Track t) {
        boolean needAlbum = config.showAlbum && t.album().isBlank();
        boolean needFeatures = config.showArtist && config.showFeatures && t.artists().size() <= 1;
        if (!needAlbum && !needFeatures) return t;
        return withInfo(t, TrackInfoLookup.find(t.artist(), t.song()));
    }

    /** Desktop app tracks miss album, featured artists and cover. */
    private static boolean needsInfo(SpotifyClient.Track t) {
        return t.album().isBlank() || t.artists().size() <= 1 || t.coverUrl().isBlank();
    }

    private static SpotifyClient.Track withInfo(SpotifyClient.Track t, TrackInfoLookup.Info info) {
        List<String> artists = t.artists();
        if (t.artists().size() <= 1 && info.artists().size() > 1
                && info.artists().getFirst().equalsIgnoreCase(t.artist())) {
            artists = new ArrayList<>(info.artists());
            artists.set(0, t.artist());
        }
        return t.withInfo(t.album().isBlank() ? info.album() : t.album(), artists,
                t.coverUrl().isBlank() ? info.coverUrl() : t.coverUrl());
    }

    /**
     * Looks up the song and posts it. channel = "gc"/"pc" sends it with that command,
     * null sends it to whatever chat channel you're in.
     * Returns true if a lookup was started (so it's fine to show "!spotify" in chat).
     */
    private boolean shareNowPlaying(String channel) {
        if (busy) {
            LOGGER.info("Share to {} skipped: still busy with the previous one", channel == null ? "chat" : channel);
            return false;
        }
        busy = true;
        shareStartedAt = System.currentTimeMillis();
        LOGGER.info("Share to {} started", channel == null ? "chat" : channel);

        CompletableFuture.supplyAsync(() -> findTrack().map(this::addInfo)).whenComplete((track, err) -> runOnGame(() -> {
            try {
                send(channel, track, err);
            } catch (Throwable e) {
                LOGGER.error("Sharing failed", e);
                info("Spotify Chat error: " + rootMessage(e), ChatFormatting.RED);
            } finally {
                // Otherwise sendPending frees it once the song is sent
                if (sendQueue.stream().noneMatch(PendingSend::fromShare)) busy = false;
            }
        }));
        return true;
    }

    private void send(String channel, Optional<SpotifyClient.Track> track, Throwable err) {
        if (err != null) {
            LOGGER.warn("Share to {} failed", channel, err);
            info("Spotify: " + rootMessage(err), ChatFormatting.RED);
            return;
        }
        LOGGER.info("Share to {}: found {}", channel == null ? "chat" : channel, track);
        Optional<SpotifyClient.Track> t = track.filter(tr -> tr.playing() || (config.sharePaused && !pausedTooLong(tr)));
        String text;
        if (t.isPresent()) {
            text = formatTrack(t.get());
        } else if (config.shareNothing) {
            text = config.notPlayingFormat;
        } else {
            info("Nothing is playing on Spotify right now.", ChatFormatting.YELLOW);
            return;
        }
        if (config.publicMessages) {
            // Never send right away: the lookup can finish while your own "!spotify" is still being sent,
            // and Hypixel drops a signed message that overtakes another one. The tick handler sends it.
            queueSend(channel, text, true, shareStartedAt + SEND_DELAY_MS);
        } else {
            info(text, ChatFormatting.GREEN);
        }
    }

    /** fromShare: a song from shareNowPlaying, which keeps the mod busy until it's sent */
    private record PendingSend(String channel, String text, boolean fromShare, long notBefore) {}

    private void queueSend(String channel, String text, boolean fromShare, long notBefore) {
        int max = channel == null ? MAX_CHAT_LENGTH : MAX_CHAT_LENGTH - channel.length() - 1;
        if (text.length() > max) text = text.substring(0, max - 3) + "...";
        sendQueue.add(new PendingSend(channel, text, fromShare, notBefore));
    }

    /** Called every client tick: sends the next queued message once its time has come (one per tick). */
    private void sendPending(Minecraft mc) {
        PendingSend p = sendQueue.peek();
        if (p == null || System.currentTimeMillis() < p.notBefore()) return;
        sendQueue.poll();
        if (p.fromShare()) busy = false;
        if (mc.player == null || mc.getConnection() == null) return;
        LOGGER.info("Sending: {}", p.channel() == null ? p.text() : "/" + p.channel() + " " + p.text());
        if (p.channel() == null) mc.getConnection().sendChat(openChatText(p.text()));
        else mc.getConnection().sendCommand(p.channel() + " " + p.text());
    }

    /**
     * With an empty "text before the song", a song title can start with "/", and some servers and proxies
     * run chat that starts with "/" as a command. One space in front keeps it a chat message.
     */
    static String openChatText(String text) {
        if (!text.startsWith("/")) return text;
        String spaced = " " + text;
        return spaced.length() > MAX_CHAT_LENGTH ? spaced.substring(0, MAX_CHAT_LENGTH - 3) + "..." : spaced;
    }

    /** Runs off the game thread. */
    private Optional<SpotifyClient.Track> findTrack() {
        // The Spotify app on this PC first (no login needed); the Web API only if logged in and turned on
        Optional<SpotifyClient.Track> local = LocalSpotify.nowPlaying();
        if (local.isPresent() && local.get().playing()) return local;
        if (config.useWebApi && spotify.isLoggedIn()) {
            try {
                Optional<SpotifyClient.Track> web = spotify.fetchNowPlaying().join();
                if (web.isPresent()) return web;
            } catch (RuntimeException e) {
                if (local.isEmpty()) throw e; // otherwise just share the paused song from the app
            }
        }
        return local; // paused song from the app, or nothing
    }

    // -------------------------------------------------------------- helpers

    /**
     * Opens a link in the browser. Minecraft moved this between versions
     * (26.3+: Blaze3D.openUri, before: Util.getPlatform().openUri), so the one that exists is used.
     */
    private static void openUri(URI uri) {
        try {
            Class.forName("com.mojang.blaze3d.Blaze3D").getMethod("openUri", URI.class).invoke(null, uri);
            return;
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            Object os = Util.getPlatform();
            os.getClass().getMethod("openUri", URI.class).invoke(os, uri);
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("Could not open {}", uri, e);
        }
    }

    /**
     * Opens the URL in the default browser and also posts a clickable link, since
     * Minecraft's openUri only logs failures and never tells us the browser didn't open.
     */
    private static void openInBrowser(String url, String linkText) {
        LOGGER.info("Opening {}", url);
        openUri(URI.create(url));
        Mc.chat().addClientSystemMessage(
                Component.literal("[" + linkText + "]").withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))));
    }

    /** Shows a message only to you. */
    static void info(String text, ChatFormatting color) {
        Minecraft mc = Minecraft.getInstance();
        Mc.chat().addClientSystemMessage(Component.literal(text).withStyle(color));
    }

    private static void runOnGame(Runnable r) {
        Minecraft.getInstance().execute(r);
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
