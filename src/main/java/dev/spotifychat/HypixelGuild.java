package dev.spotifychat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which Hypixel guild you're in, read from Hypixel's own guild list: the "Guild Name: ..." line of /g online
 * or /g list. When you join Hypixel the mod asks once with /g online and keeps that answer out of your chat
 * (see asked / hide). The bridge bot lets you into your guild's IRC by this name until it can ask Hypixel's
 * API itself.
 */
final class HypixelGuild {
    /** "Guild Name: Mooi Weer Fietsers", sometimes with a chat mod's " (2)" stack count after it */
    private static final Pattern NAME = Pattern.compile("Guild Name: ([A-Za-z0-9_ ]{1,32}?)(?: \\(\\d+\\))?");
    /** You left, got kicked, or the guild is gone */
    private static final Pattern GONE = Pattern.compile(
            "(?i)(You left the guild|You were kicked from the guild|The guild has been disbanded).*");
    /** Hypixel's answer to /g online when you're not in a guild (only trusted right after we asked) */
    private static final Pattern NO_GUILD = Pattern.compile("(?i).*\\b(not in a guild|must be in a guild)\\b.*");
    private static final Pattern RULE = Pattern.compile("-{10,}");
    /** The rest of the list: rank headers, member rows, counts and empty lines */
    private static final Pattern LIST_LINE = Pattern.compile(
            "\\s*|\\s*-- .{1,40} --\\s*|.*●.*|(Total|Online|Offline) Members: \\d+.*");
    private static final Pattern COUNT = Pattern.compile("(Total|Online|Offline) Members: \\d+.*");
    static final long ANSWER_MS = 5_000;

    private enum State { IDLE, WAITING, LIST, CLOSING }

    private State state = State.IDLE;
    private long until;
    private boolean sawCount;

    /** We just sent /g online: its answer is hidden for a few seconds */
    void asked(long now) {
        state = State.WAITING;
        until = now + ANSWER_MS;
        sawCount = false;
    }

    /** Your guild if this line names it, "" if it says you're not in one (anymore), else null */
    String read(String line) {
        String l = line.strip();
        Matcher m = NAME.matcher(l);
        if (m.matches()) return m.group(1).strip().replaceAll(" {2,}", " ");
        if (GONE.matcher(l).matches()) return "";
        if (state == State.WAITING && NO_GUILD.matcher(l).matches()) return "";
        return null;
    }

    /**
     * Whether to keep this chat line out of chat: only the answer to our own /g online, line by line, and
     * nothing that doesn't look like part of it (anyone chatting in between still shows up).
     */
    boolean hide(String line, long now) {
        if (state != State.IDLE && now > until) state = State.IDLE;
        String l = line.strip();
        switch (state) {
            case WAITING -> {
                if (RULE.matcher(l).matches()) return true;
                if (NAME.matcher(l).matches()) {
                    state = State.LIST;
                    return true;
                }
                if (NO_GUILD.matcher(l).matches()) {
                    state = State.CLOSING;
                    return true;
                }
                return false;
            }
            case LIST -> {
                if (RULE.matcher(l).matches()) {
                    if (sawCount) state = State.IDLE; // the closing line
                    return true;
                }
                if (COUNT.matcher(l).matches()) sawCount = true;
                return LIST_LINE.matcher(line).matches();
            }
            case CLOSING -> {
                state = State.IDLE;
                return RULE.matcher(l).matches();
            }
            default -> {
                return false;
            }
        }
    }
}
