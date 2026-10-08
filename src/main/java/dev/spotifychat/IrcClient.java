package dev.spotifychat;

import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Tiny IRC client for chatting with other Spotify Chat users outside the Minecraft server, so links
 * (like Jam invites) never go through the server's chat. Uses Rizon over TLS: Rizon hides everyone's
 * IP address behind a scrambled host, so other people in the channel can't see where you connect from.
 */
public final class IrcClient {
    public static final String SERVER = "irc.rizon.net";
    public static final int PORT = 6697;
    /** The one channel every Spotify Chat user joins */
    public static final String CHANNEL = "#spotifychat";

    /** A chat line from the channel */
    public record Message(String nick, String text, boolean action) {}

    public enum Status { OFF, CONNECTING, CONNECTED }

    private final Consumer<Message> onMessage;
    private final Consumer<String> onNotice;

    private volatile Thread thread;
    private volatile Socket socket;
    private volatile Writer out;
    private volatile Status status = Status.OFF;
    private volatile String nick = "";
    private final String channel = CHANNEL;
    private volatile int online;
    private volatile boolean running;

    /** onMessage / onNotice are called on the IRC thread */
    public IrcClient(Consumer<Message> onMessage, Consumer<String> onNotice) {
        this.onMessage = onMessage;
        this.onNotice = onNotice;
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

    /** Connects to #spotifychat with your Minecraft name, unless already running. Keeps retrying until stop(). */
    public synchronized void start(String minecraftName) {
        if (running) return;
        stop();
        running = true;
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
    }

    /** Sends a line to the channel. Returns false when not connected. */
    public boolean send(String text) {
        Writer w = out;
        if (status != Status.CONNECTED || w == null) return false;
        String clean = clean(text);
        if (clean.isBlank()) return false;
        if (clean.length() > 350) clean = clean.substring(0, 350);
        try {
            synchronized (this) {
                w.write("PRIVMSG " + channel + " :" + clean + "\r\n");
                w.flush();
            }
            return true;
        } catch (Exception e) {
            closeSocket();
            return false;
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

    private void session(String baseNick) throws Exception {
        status = Status.CONNECTING;
        online = 0;
        Socket s = SSLSocketFactory.getDefault().createSocket(SERVER, PORT);
        s.setSoTimeout(300_000); // the server pings every few minutes
        socket = s;
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
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

            switch (command) {
                case "001" -> raw("JOIN " + channel); // registered
                case "433", "432" -> { // nick taken / not allowed: add a number
                    myNick = baseNick.substring(0, Math.min(baseNick.length(), 24))
                            + "_" + ThreadLocalRandom.current().nextInt(100, 1000);
                    nick = myNick;
                    raw("NICK " + myNick);
                }
                case "353" -> online += trailing.isBlank() ? 0 : trailing.trim().split(" +").length; // NAMES
                case "366" -> { // end of NAMES: now we're in
                    if (status != Status.CONNECTED) {
                        status = Status.CONNECTED;
                        onNotice.accept("Connected to Spotify Chat IRC as " + nick + " in " + channel
                                + " (" + online + " online)");
                    }
                }
                case "JOIN" -> {
                    if (!from.equalsIgnoreCase(nick) && status == Status.CONNECTED) online++;
                }
                case "PART", "QUIT" -> {
                    if (status == Status.CONNECTED) online = Math.max(1, online - 1);
                }
                case "NICK" -> {
                    if (from.equalsIgnoreCase(nick)) nick = trailing;
                }
                case "PRIVMSG" -> {
                    if (parts.length > 1 && parts[1].equalsIgnoreCase(channel)) {
                        boolean action = trailing.startsWith("\u0001ACTION ") && trailing.endsWith("\u0001");
                        String text = action ? trailing.substring(8, trailing.length() - 1) : trailing;
                        if (!text.startsWith("\u0001")) onMessage.accept(new Message(from, stripFormatting(text), action));
                    }
                }
                case "474", "473", "475", "471" -> onNotice.accept("Can't join " + channel + ": " + trailing);
                case "ERROR" -> throw new IllegalStateException(trailing);
                default -> {
                }
            }
        }
        throw new IllegalStateException("connection closed");
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
