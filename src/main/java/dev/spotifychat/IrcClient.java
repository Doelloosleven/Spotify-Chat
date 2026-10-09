package dev.spotifychat;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tiny IRC client for chatting with other Spotify Chat users outside the Minecraft server, so links
 * (like Jam invites) never go through the server's chat. Uses Rizon over TLS: Rizon hides everyone's
 * IP address behind a scrambled host, so other people in the channel can't see where you connect from.
 *
 * Guild IRC: the bridge bot (BRIDGE_NICK) runs a hidden, invite-only channel for one Hypixel guild. After
 * joining #spotifychat the client asks the bot for it; if your Minecraft name belongs to a guild member, the
 * bot sends a random code, the client proves the name to Mojang (see prove) and the bot invites it.
 */
public final class IrcClient {
    public static final String SERVER = "irc.rizon.net";
    public static final int PORT = 6697;
    /** The one channel every Spotify Chat user joins */
    public static final String CHANNEL = "#spotifychat";
    /** The bridge bot: relays Discord, and lets guild members into the guild channel */
    public static final String BRIDGE_NICK = "SpotifyDiscord";
    /**
     * The Mojang server id for the guild check is a hash of this plus the bot's code. A real Minecraft server
     * checks a hash of its own key, so no code the bot (or someone pretending to be it) sends can make this a
     * login for a real server.
     */
    static final String AUTH_PREFIX = "SpotifyChat guild IRC:";

    /** IRC lines are at most 512 bytes; this is far more than any real one, and nothing longer is kept */
    static final int MAX_LINE_BYTES = 2048;
    /** What we send must fit in 512 bytes together with "PRIVMSG #spotifychat :" and the server's prefix */
    static final int MAX_TEXT_BYTES = 400;
    private static final long REJOIN_DELAY_MS = 10_000;
    private static final long REJOIN_MIN_GAP_MS = 60_000;
    private static final long GUILD_ASK_GAP_MS = 25_000;
    private static final Pattern GUILD_CODE = Pattern.compile("[0-9a-f]{32}");
    /** How the bridge sends Discord messages to IRC: "<Name> text" */
    private static final Pattern FROM_DISCORD = Pattern.compile("<([^<>]{1,32})> (.+)");

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "SpotifyChat-IRC-rejoin");
        t.setDaemon(true);
        return t;
    });

    /** A chat line from a channel. guild = the guild channel; discord = relayed from Discord by the bridge. */
    public record Message(String nick, String text, boolean action, boolean guild, boolean discord) {}

    public enum Status { OFF, CONNECTING, CONNECTED }

    /** Proves the player's Minecraft account to Mojang: "joins" serverId like a client joining a server */
    public interface Prover {
        void join(String serverId) throws Exception;
    }

    /** A sent message waiting for the server to answer the PING sent right after it */
    private record Pending(String token, String text, boolean guild) {}

    private final Consumer<Message> onMessage;
    private final Consumer<String> onNotice;
    private final Prover prover;

    private volatile Thread thread;
    private volatile Socket socket;
    private volatile Writer out;
    private volatile Status status = Status.OFF;
    private volatile String nick = "";
    private volatile String minecraftName = "";
    private final String channel = CHANNEL;
    private volatile int online;
    private volatile boolean running;
    private final Deque<Pending> unconfirmed = new ConcurrentLinkedDeque<>();
    private final AtomicLong tokens = new AtomicLong();
    private volatile long lastRejoinAt;

    // Guild channel
    private volatile boolean guildEnabled;
    /** The guild channel we're in, or null */
    private volatile String guildChannel;
    /** The channel the bot invited us to, until we're in it */
    private volatile String guildJoining;
    private final Set<String> guildMembers = ConcurrentHashMap.newKeySet();
    private volatile boolean awaitingCode;
    private volatile long lastGuildAsk;

    /** onMessage / onNotice are called on the IRC thread */
    public IrcClient(Consumer<Message> onMessage, Consumer<String> onNotice, Prover prover) {
        this.onMessage = onMessage;
        this.onNotice = onNotice;
        this.prover = prover;
    }

    public Status status() {
        return status;
    }

    public String nick() {
        return nick;
    }

    public String channel() {
        return channel;
    }

    public int online() {
        return online;
    }

    /** In the guild channel? */
    public boolean inGuild() {
        return guildChannel != null && status == Status.CONNECTED;
    }

    /** Others in the guild channel, plus you (the bridge bot doesn't count) */
    public int guildOnline() {
        return guildMembers.size();
    }

    /** Connects to #spotifychat with your Minecraft name, unless already running. Keeps retrying until stop(). */
    public synchronized void start(String minecraftName) {
        if (running) return;
        stop();
        running = true;
        this.minecraftName = minecraftName;
        String baseNick = toNick(minecraftName);
        Thread t = new Thread(() -> loop(baseNick), "SpotifyChat-IRC");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    public synchronized void stop() {
        running = false;
        Writer w = out;
        if (w != null) {
            try {
                w.write("QUIT :bye\r\n");
                w.flush();
            } catch (Exception ignored) {
            }
        }
        closeSocket();
        Thread t = thread;
        if (t != null) t.interrupt();
        thread = null;
        status = Status.OFF;
        online = 0;
        leftGuild();
    }

    /** Guild IRC on/off: off leaves the guild channel, on asks the bridge bot to let us in */
    public void setGuildEnabled(boolean enabled) {
        guildEnabled = enabled;
        String g = guildChannel;
        if (!enabled && g != null) {
            leftGuild();
            try {
                raw("PART " + g);
            } catch (Exception ignored) {
            }
        } else if (enabled) {
            lastGuildAsk = 0;
            askGuild();
        }
    }

    /**
     * Sends a line to the channel (or the guild channel). Returns the text exactly as it was sent (see
     * outgoing), or null when not connected. It's shown in chat (onMessage) only once the server has accepted it.
     */
    public String send(String text, boolean guild) {
        Writer w = out;
        String target = guild ? guildChannel : channel;
        if (status != Status.CONNECTED || w == null || target == null) return null;
        String line = outgoing(text);
        if (line.isBlank()) return null;
        Pending p = new Pending("sc" + tokens.incrementAndGet(), line, guild);
        try {
            synchronized (this) {
                unconfirmed.add(p);
                w.write("PRIVMSG " + target + " :" + line + "\r\n");
                // The server answers in order, so an error about this message arrives before this PONG
                w.write("PING :" + p.token() + "\r\n");
                w.flush();
            }
            return line;
        } catch (Exception e) {
            unconfirmed.remove(p);
            closeSocket();
            return null;
        }
    }

    /** What send() sends for this text: no line breaks or control characters, at most 400 UTF-8 bytes. */
    public static String outgoing(String text) {
        return cutToBytes(clean(text), MAX_TEXT_BYTES);
    }

    /** Longest start of s that's at most maxBytes in UTF-8, never cutting a character in half */
    static String cutToBytes(String s, int maxBytes) {
        int bytes = 0, i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int len = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (bytes + len > maxBytes) break;
            bytes += len;
            i += Character.charCount(cp);
        }
        return s.substring(0, i);
    }

    /** A channel line as shown in chat: Discord messages the bridge relays get the Discord name */
    static Message channelMessage(String from, String text, boolean action, boolean guild) {
        if (!action && from.equalsIgnoreCase(BRIDGE_NICK)) {
            Matcher m = FROM_DISCORD.matcher(text);
            if (m.matches()) return new Message(m.group(1).strip(), m.group(2), false, guild, true);
        }
        return new Message(from, text, action, guild, false);
    }

    /** The Mojang server id for the bridge bot's code: sha1(AUTH_PREFIX + code) in hex */
    static String guildServerId(String code) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1")
                    .digest((AUTH_PREFIX + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void loop(String baseNick) {
        int delaySeconds = 5;
        while (running) {
            try {
                session(baseNick);
                delaySeconds = 5; // was connected: retry quickly
            } catch (Exception e) {
                if (running) SpotifyChatClient.LOGGER.info("IRC disconnected: {}", e.toString());
            } finally {
                closeSocket();
                status = Status.CONNECTING;
                online = 0;
                leftGuild();
            }
            if (!running) break;
            try {
                Thread.sleep(delaySeconds * 1000L);
            } catch (InterruptedException e) {
                break;
            }
            delaySeconds = Math.min(300, delaySeconds * 2);
        }
        status = Status.OFF;
    }

    /**
     * TLS socket that also checks the certificate is for this host name. Without that, any certificate a
     * trusted authority signed for any name would be accepted.
     */
    static SSLSocket openTls(String host, int port) throws IOException {
        SSLSocket s = (SSLSocket) SSLSocketFactory.getDefault().createSocket(host, port);
        try {
            SSLParameters params = s.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            s.setSSLParameters(params);
            s.setSoTimeout(30_000);
            s.startHandshake();
            return s;
        } catch (IOException e) {
            s.close();
            throw e;
        }
    }

    private void session(String baseNick) throws Exception {
        status = Status.CONNECTING;
        online = 0;
        unconfirmed.clear();
        leftGuild();
        Socket s = openTls(SERVER, PORT);
        s.setSoTimeout(300_000); // the server pings every few minutes
        socket = s;
        BoundedLineReader in = new BoundedLineReader(new BufferedInputStream(s.getInputStream()), MAX_LINE_BYTES);
        Writer w = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
        out = w;

        String myNick = baseNick;
        nick = myNick;
        raw("NICK " + myNick);
        raw("USER spotifychat 0 * :Spotify Chat (Minecraft mod)");

        String line;
        while (running && (line = in.readLine()) != null) {
            if (line.startsWith("PING")) {
                raw("PONG" + line.substring(4));
                continue;
            }
            String prefix = "";
            String rest = line;
            if (rest.startsWith(":")) {
                int sp = rest.indexOf(' ');
                if (sp < 0) continue;
                prefix = rest.substring(1, sp);
                rest = rest.substring(sp + 1);
            }
            String trailing = "";
            int ti = rest.indexOf(" :");
            if (ti >= 0) {
                trailing = rest.substring(ti + 2);
                rest = rest.substring(0, ti);
            }
            String[] parts = rest.split(" ");
            String command = parts[0];
            String from = prefix.contains("!") ? prefix.substring(0, prefix.indexOf('!')) : prefix;
            boolean fromBridge = from.equalsIgnoreCase(BRIDGE_NICK);
            String target = parts.length > 1 ? parts[1] : trailing;

            switch (command) {
                case "001" -> raw("JOIN " + channel); // registered
                case "433", "432" -> { // nick taken / not allowed: add a number
                    myNick = baseNick.substring(0, Math.min(baseNick.length(), 24))
                            + "_" + ThreadLocalRandom.current().nextInt(100, 1000);
                    nick = myNick;
                    raw("NICK " + myNick);
                }
                case "353" -> { // NAMES: "353 me = #channel :names"
                    String ch = parts.length > 3 ? parts[3] : "";
                    if (ch.equalsIgnoreCase(channel)) {
                        online += trailing.isBlank() ? 0 : trailing.trim().split(" +").length;
                    } else if (ch.equalsIgnoreCase(guildJoining)) {
                        for (String n : trailing.trim().split(" +")) {
                            String bare = n.replaceFirst("^[@+%&~]+", "");
                            if (!bare.isEmpty() && !bare.equalsIgnoreCase(BRIDGE_NICK)) guildMembers.add(lower(bare));
                        }
                    }
                }
                case "366" -> { // end of NAMES: now we're in
                    String ch = parts.length > 2 ? parts[2] : "";
                    if (ch.equalsIgnoreCase(channel) && status != Status.CONNECTED) {
                        status = Status.CONNECTED;
                        onNotice.accept("Connected to Spotify Chat IRC as " + nick + " in " + channel
                                + " (" + online + " online)");
                        askGuild();
                    } else if (ch.equalsIgnoreCase(guildJoining)) {
                        guildChannel = guildJoining;
                        guildJoining = null;
                        onNotice.accept("Joined your guild's private IRC (" + guildOnline()
                                + " online). Press ] or type /girc <message> to write there.");
                    }
                }
                case "JOIN" -> {
                    if (from.equalsIgnoreCase(nick)) break;
                    if (target.equalsIgnoreCase(channel)) {
                        if (status == Status.CONNECTED) online++;
                        // The bridge bot (re)started: it made a new guild channel, so ask again
                        if (fromBridge) {
                            lastGuildAsk = 0;
                            askGuild();
                        }
                    } else if (target.equalsIgnoreCase(guildChannel) && !fromBridge) {
                        guildMembers.add(lower(from));
                    }
                }
                case "PART" -> {
                    if (target.equalsIgnoreCase(channel)) {
                        if (status == Status.CONNECTED) online = Math.max(1, online - 1);
                    } else if (target.equalsIgnoreCase(guildChannel)) {
                        guildMembers.remove(lower(from));
                    }
                }
                case "QUIT" -> {
                    if (status == Status.CONNECTED) online = Math.max(1, online - 1);
                    guildMembers.remove(lower(from));
                    String g = guildChannel;
                    if (fromBridge && g != null) {
                        // The bot makes a new channel when it's back (we ask again then), so leave this one
                        leftGuild();
                        raw("PART " + g);
                        onNotice.accept("The guild IRC is offline for a moment.");
                    }
                }
                case "KICK" -> {
                    if (parts.length > 2 && parts[2].equalsIgnoreCase(nick)) {
                        if (parts[1].equalsIgnoreCase(channel)) {
                            kicked(from, stripFormatting(trailing), w);
                        } else if (parts[1].equalsIgnoreCase(guildChannel)) {
                            leftGuild();
                            onNotice.accept("Removed from the guild IRC: " + stripFormatting(trailing));
                        }
                    } else if (parts.length > 2 && parts[1].equalsIgnoreCase(guildChannel)) {
                        guildMembers.remove(lower(parts[2]));
                    }
                }
                case "NICK" -> {
                    if (from.equalsIgnoreCase(nick)) nick = trailing;
                    if (guildMembers.remove(lower(from))) guildMembers.add(lower(trailing));
                }
                case "PRIVMSG" -> {
                    boolean guild = target.equalsIgnoreCase(guildChannel);
                    if (guild || target.equalsIgnoreCase(channel)) {
                        boolean action = trailing.startsWith("\u0001ACTION ") && trailing.endsWith("\u0001");
                        String text = action ? trailing.substring(8, trailing.length() - 1) : trailing;
                        if (!text.startsWith("\u0001")) {
                            onMessage.accept(channelMessage(from, stripFormatting(text), action, guild));
                        }
                    }
                }
                case "NOTICE" -> {
                    if (fromBridge && target.equalsIgnoreCase(nick)) guildReply(trailing, w);
                }
                case "INVITE" -> { // the bridge bot lets us into the guild channel
                    String ch = trailing.isEmpty() && parts.length > 2 ? parts[2] : trailing;
                    if (fromBridge && guildEnabled && ch.startsWith("#") && !ch.equalsIgnoreCase(channel)
                            && ch.matches("#[\\w-]{1,40}")) {
                        String old = guildChannel;
                        if (old != null && !old.equalsIgnoreCase(ch)) raw("PART " + old);
                        leftGuild();
                        guildJoining = ch;
                        raw("JOIN " + ch);
                    }
                }
                case "PONG" -> { // the server got the message sent before this PING: show it
                    String token = !trailing.isEmpty() ? trailing : parts.length > 2 ? parts[2] : "";
                    for (Pending p : unconfirmed) {
                        if (p.token().equals(token) && unconfirmed.remove(p)) {
                            onMessage.accept(new Message(nick, p.text(), false, p.guild(), false));
                            break;
                        }
                    }
                }
                case "404", "442" -> { // cannot send to channel / not on channel: the oldest unanswered message failed
                    Pending failed = unconfirmed.poll();
                    onNotice.accept("Not delivered" + (failed == null ? "" : ": \"" + failed.text() + "\"")
                            + " (" + stripFormatting(trailing) + ")");
                }
                case "474", "473", "475", "471" -> {
                    String ch = parts.length > 2 ? parts[2] : "";
                    if (ch.equalsIgnoreCase(guildJoining)) guildJoining = null;
                    else onNotice.accept("Can't join " + channel + ": " + trailing);
                }
                case "ERROR" -> throw new IllegalStateException(trailing);
                default -> {
                }
            }
        }
        throw new IllegalStateException("connection closed");
    }

    /** Asks the bridge bot to let us into the guild channel (it answers only guild members with a code) */
    private void askGuild() {
        long now = System.currentTimeMillis();
        if (!guildEnabled || status != Status.CONNECTED || guildChannel != null || now - lastGuildAsk < GUILD_ASK_GAP_MS) {
            return;
        }
        lastGuildAsk = now;
        awaitingCode = true;
        try {
            raw("PRIVMSG " + BRIDGE_NICK + " :\u0001SCGUILD HELLO " + minecraftName + "\u0001");
        } catch (Exception ignored) {
        }
    }

    /** The bridge bot's answer: "PROVE <code>", "NO" or "FAIL <why>" */
    private void guildReply(String text, Writer session) {
        if (!text.startsWith("\u0001SCGUILD ") || !text.endsWith("\u0001")) return;
        String[] words = text.substring(9, text.length() - 1).split(" ", 2);
        switch (words[0]) {
            case "PROVE" -> {
                // Only right after we asked, and only a plain code: never sign anything else
                if (!awaitingCode || words.length < 2 || !GUILD_CODE.matcher(words[1]).matches()) return;
                awaitingCode = false;
                String code = words[1];
                TIMER.execute(() -> prove(code, session));
            }
            case "NO" -> awaitingCode = false;
            case "FAIL" -> {
                awaitingCode = false;
                SpotifyChatClient.LOGGER.info("Guild IRC check failed: {}", words.length > 1 ? words[1] : "");
            }
            default -> {
            }
        }
    }

    /** Proves the Minecraft name to Mojang for the bot's code, then tells the bot (off the IRC thread) */
    private void prove(String code, Writer session) {
        try {
            prover.join(guildServerId(code));
            if (out != session) return; // reconnected meanwhile
            raw("PRIVMSG " + BRIDGE_NICK + " :\u0001SCGUILD PROVED\u0001");
        } catch (Exception e) {
            SpotifyChatClient.LOGGER.info("Guild IRC: couldn't confirm the account with Mojang: {}", e.toString());
        }
    }

    private void leftGuild() {
        guildChannel = null;
        guildJoining = null;
        guildMembers.clear();
        awaitingCode = false;
    }

    /** Kicked from the channel: say why, then rejoin after 10 seconds, at most once a minute. */
    private void kicked(String by, String reason, Writer session) {
        status = Status.CONNECTING; // no longer in the channel, so nothing can be sent
        online = 0;
        long now = System.currentTimeMillis();
        long at = Math.max(now + REJOIN_DELAY_MS, lastRejoinAt + REJOIN_MIN_GAP_MS);
        lastRejoinAt = at;
        long seconds = (at - now + 999) / 1000;
        onNotice.accept("Kicked from " + channel + " by " + by + (reason.isBlank() ? "" : ": " + reason)
                + ". Rejoining in " + seconds + " s.");
        TIMER.schedule(() -> {
            if (!running || out != session) return; // stopped or reconnected meanwhile
            try {
                raw("JOIN " + channel);
            } catch (Exception ignored) {
            }
        }, at - now, TimeUnit.MILLISECONDS);
    }

    private synchronized void raw(String line) throws Exception {
        Writer w = out;
        if (w == null) return;
        w.write(line + "\r\n");
        w.flush();
    }

    private void closeSocket() {
        Socket s = socket;
        socket = null;
        out = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /** Minecraft names can start with a digit, IRC nicks can't */
    static String toNick(String minecraftName) {
        String n = minecraftName.replaceAll("[^A-Za-z0-9_\\-\\[\\]\\\\^{}|`]", "");
        if (n.isEmpty() || !Character.isLetter(n.charAt(0)) && "_[]\\^{}|`".indexOf(n.charAt(0)) < 0) n = "mc_" + n;
        return n.length() > 30 ? n.substring(0, 30) : n;
    }

    /** No line breaks or control characters in what we send */
    private static String clean(String s) {
        return s.replaceAll("[\\r\\n\\u0000-\\u001F\\u007F]", " ").replace('§', ' ').strip();
    }

    /** Removes IRC colors/bold and Minecraft § codes from what others send */
    private static String stripFormatting(String s) {
        return s.replaceAll("\\u0003(\\d{1,2}(,\\d{1,2})?)?", "")
                .replaceAll("[\\u0002\\u000F\\u0011\\u0016\\u001D\\u001E\\u001F]", "")
                .replaceAll("§.?", "");
    }
}
