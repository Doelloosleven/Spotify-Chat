package dev.spotifychat;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * In chat:
 *   !spotify                  share the current song (your "!spotify" is shown too, if enabled)
 *   /gc !spotify, /pc !spotify   share it in guild / party chat
 *   Someone else's "!spotify" in guild or party chat is answered in that same channel.
 *
 * The song is read from the Spotify desktop app on this PC, so no login is needed.
 *
 * Client-side commands (never reach the server):
 *   /spotify                  open the settings menu
 *   /spotify public|private   who sees the song
 *   /spotify login|logout     optional: Web API (to share from phone / web player)
 *   /spotify id <id>          Client ID for the Web API login
 *   /spotify help
 */
public class SpotifyChatClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("SpotifyChat");
    private static final int MAX_CHAT_LENGTH = 256;
    private static final String DASHBOARD_URL = "https://developer.spotify.com/dashboard";

    /** /gc, /pc and Hypixel's long forms (/guild chat, /g chat, /party chat, /p chat) followed by !spotify */
    private static final Pattern OWN_CHANNEL_COMMAND = Pattern.compile(
            "(gc|pc|(?:guild|g|party|p) chat)\\s+!spotify", Pattern.CASE_INSENSITIVE);
    /** Hypixel: "Guild > [MVP++] Name [Admin]: !spotify" or "Party > [VIP] Name: !spotify" */
    private static final Pattern CHANNEL_MESSAGE = Pattern.compile(
            "(Guild|Party) > (?:\\[[^\\]]+\\] )?(\\w{1,16})(?: \\[[^\\]]+\\])?: !spotify", Pattern.CASE_INSENSITIVE);

    private static SpotifyChatClient instance;

    private ModConfig config;
    private SpotifyClient spotify;
    private volatile boolean busy = false;
    private long lastRemoteShare = 0;
    /** When your own !spotify was last handled, so Hypixel's echo of it doesn't share a second time */
    private volatile long lastOwnShare = 0;
    private static final long OWN_ECHO_WINDOW_MS = 5_000;
    /** Minimum time between the "!spotify" and the song, so they reach the server in order */
    private static final long SEND_DELAY_MS = 1_000;
    private long shareStartedAt = 0;
    private volatile PendingSend pendingSend;
    private boolean openMenuNextTick = false;

    public static SpotifyChatClient get() {
        return instance;
    }

    public ModConfig config() {
        return config;
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        config = ModConfig.load();
        spotify = new SpotifyClient(config);
        LocalSpotify.startWatching();

        // Return false = cancel the message so it never reaches the server.
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            String trimmed = message.trim();
            if (!config.enabled || !trimmed.toLowerCase(Locale.ROOT).startsWith("!spotify")) return true;
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

        // "/gc !spotify" or "/pc !spotify": let the command through, then answer in that same channel.
        // Some mods send typed commands in a way this doesn't see; the chat echo below covers that.
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            if (!config.enabled) return true;
            Matcher m = OWN_CHANNEL_COMMAND.matcher(command.trim());
            if (!m.matches()) return true;
            LOGGER.info("Saw own command: /{}", command);
            boolean guild = m.group(1).toLowerCase(Locale.ROOT).startsWith("g");
            if (guild ? !config.guildChat : !config.partyChat) return true; // turned off: plain message
            return ownShare(guild ? "gc" : "pc") && showTriggerPublicly();
        });

        // "!spotify" shows up in guild or party chat: answer with your song in that channel only
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay || !config.enabled || !config.publicMessages) return;
            Matcher m = CHANNEL_MESSAGE.matcher(ChatFormatting.stripFormatting(message.getString()).trim());
            if (!m.matches()) return;
            boolean guild = m.group(1).equalsIgnoreCase("Guild");
            String channel = guild ? "gc" : "pc";
            String me = Minecraft.getInstance().getUser().getName();
            LOGGER.info("Saw !spotify in {} chat from {} (you are {})", m.group(1), m.group(2), me);

            if (m.group(2).equalsIgnoreCase(me)) {
                // Your own "!spotify" coming back from Hypixel. Skip it if it was already handled when sending.
                if (System.currentTimeMillis() - lastOwnShare < OWN_ECHO_WINDOW_MS) {
                    LOGGER.info("Own !spotify echo ignored: already shared when sending");
                    return;
                }
                if (guild ? !config.guildChat : !config.partyChat) return;
                ownShare(channel);
                return;
            }
            if (guild ? !config.answerGuild : !config.answerParty) return;

            long now = System.currentTimeMillis();
            if (now - lastRemoteShare < config.answerCooldownSeconds * 1000L) return; // don't get muted for spam
            lastRemoteShare = now;
            shareNowPlaying(channel);
        });

        // Chat closes itself right after a command runs, so open the menu on the next tick
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            sendPending(mc);
            if (openMenuNextTick) {
                openMenuNextTick = false;
                mc.gui.setScreen(new SpotifyConfigScreen(null));
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
                literal("spotify")
                        .executes(ctx -> run(() -> openMenuNextTick = true))
                        .then(literal("menu").executes(ctx -> run(() -> openMenuNextTick = true)))
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

    /** Shares for your own !spotify and remembers it, so the echo from the server is ignored. */
    private boolean ownShare(String channel) {
        boolean started = shareNowPlaying(channel);
        if (started) lastOwnShare = System.currentTimeMillis();
        return started;
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
        config.refreshToken = "";
        config.save();
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
        info("!spotify  - share what's playing in your Spotify app", ChatFormatting.GRAY);
        info("/gc !spotify, /pc !spotify  - share it in guild / party chat", ChatFormatting.GRAY);
        info("/spotify  - open the settings menu", ChatFormatting.GRAY);
        info("/spotify public / private  - who sees the song (now: "
                + (config.publicMessages ? "public" : "private") + ")", ChatFormatting.GRAY);
        info("Optional, to share from your phone too: /spotify id <clientId>, /spotify login",
                ChatFormatting.DARK_GRAY);
    }

    public boolean isLoggedIn() {
        return spotify.isLoggedIn();
    }

    public void logout() {
        config.refreshToken = "";
        config.save();
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
    private boolean pausedTooLong(SpotifyClient.Track t) {
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

        TrackInfoLookup.Info info = TrackInfoLookup.find(t.artist(), t.song());
        List<String> artists = t.artists();
        if (t.artists().size() <= 1 && info.artists().size() > 1
                && info.artists().getFirst().equalsIgnoreCase(t.artist())) {
            artists = new ArrayList<>(info.artists());
            artists.set(0, t.artist());
        }
        return t.withInfo(t.album().isBlank() ? info.album() : t.album(), artists);
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
                if (pendingSend == null) busy = false; // otherwise sendPending frees it once sent
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
        int max = channel == null ? MAX_CHAT_LENGTH : MAX_CHAT_LENGTH - channel.length() - 1;
        if (text.length() > max) text = text.substring(0, max - 3) + "...";

        if (config.publicMessages) {
            // Never send right away: the lookup can finish while your own "!spotify" is still being sent,
            // and Hypixel drops a signed message that overtakes another one. The tick handler sends it.
            pendingSend = new PendingSend(channel, text, shareStartedAt + SEND_DELAY_MS);
        } else {
            info(text, ChatFormatting.GREEN);
        }
    }

    private record PendingSend(String channel, String text, long notBefore) {}

    /** Called every client tick: sends the queued song once its time has come. */
    private void sendPending(Minecraft mc) {
        PendingSend p = pendingSend;
        if (p == null || System.currentTimeMillis() < p.notBefore()) return;
        pendingSend = null;
        busy = false;
        if (mc.player == null || mc.getConnection() == null) return;
        LOGGER.info("Sending: {}", p.channel() == null ? p.text() : "/" + p.channel() + " " + p.text());
        if (p.channel() == null) mc.getConnection().sendChat(p.text());
        else mc.getConnection().sendCommand(p.channel() + " " + p.text());
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
     * Opens the URL in the default browser and also posts a clickable link, since
     * Minecraft's openUri only logs failures and never tells us the browser didn't open.
     */
    private static void openInBrowser(String url, String linkText) {
        LOGGER.info("Opening {}", url);
        Util.getPlatform().openUri(url);
        Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(
                Component.literal("[" + linkText + "]").withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))));
    }

    /** Shows a message only to you. */
    static void info(String text, ChatFormatting color) {
        Minecraft mc = Minecraft.getInstance();
        mc.gui.hud.getChat().addClientSystemMessage(Component.literal(text).withStyle(color));
    }

    private static void runOnGame(Runnable r) {
        Minecraft.getInstance().execute(r);
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
