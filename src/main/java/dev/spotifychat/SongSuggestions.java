package dev.spotifychat;

import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tab in chat after "!queue ": the song playing now and the ones played before it this session, like
 * Minecraft completes player names. "!queue HAZ" + Tab gives "!queue HAZARD DUTY PAY! - JPEGMAFIA".
 * Typing any word of the title or artist finds it.
 */
public final class SongSuggestions {
    private SongSuggestions() {}

    /** "!queue ", also after /gc, /pc, /cc (Hypixel) and /irc, /girc */
    private static final Pattern TRIGGER = Pattern.compile(
            "(?:/(?:gc|pc|cc|irc|girc|guild chat|party chat) +)?!queue ", Pattern.CASE_INSENSITIVE);
    private static final int HISTORY = 30;
    /** Newest first, "Title - Artist" (game and Spotify-check threads) */
    private static final Deque<String> recent = new ArrayDeque<>();

    /** A song that played: remembered for the suggestions */
    public static void saw(SpotifyClient.Track t) {
        if (t == null || t.song().isBlank()) return;
        String name = name(t);
        synchronized (recent) {
            if (name.equals(recent.peekFirst())) return;
            recent.remove(name);
            recent.addFirst(name);
            while (recent.size() > HISTORY) recent.removeLast();
        }
    }

    static String name(SpotifyClient.Track t) {
        return t.artist().isBlank() ? t.song() : t.song() + " - " + t.artist();
    }

    /** For the chat box: suggestions for "!queue ..." up to the cursor, or null for any other text */
    public static CompletableFuture<Suggestions> suggest(String text, int cursor) {
        String typed = text.substring(0, Math.max(0, Math.min(cursor, text.length())));
        int start = songStart(typed);
        if (start < 0) return null;
        List<String> songs = new ArrayList<>();
        SpotifyClient.Track now = SpotifyChatClient.get().overlayTrack();
        if (now == null) now = LocalSpotify.latest().orElse(null);
        if (now != null && !now.song().isBlank()) songs.add(name(now));
        synchronized (recent) {
            songs.addAll(recent);
        }
        // Built by hand: Brigadier's builder sorts A-Z, and the song playing now should come first
        StringRange range = StringRange.between(start, typed.length());
        List<Suggestion> list = matches(typed.substring(start), songs).stream()
                .map(song -> new Suggestion(range, song)).toList();
        return CompletableFuture.completedFuture(new Suggestions(range, list));
    }

    /** Where the song starts in "!queue <song>" or "/gc !queue <song>", or -1 */
    static int songStart(String typed) {
        Matcher m = TRIGGER.matcher(typed);
        return m.lookingAt() ? m.end() : -1;
    }

    /** The songs (in order, no doubles) where the typed text starts the title or any word of it */
    static List<String> matches(String typed, List<String> songs) {
        String t = typed.stripLeading().toLowerCase(Locale.ROOT);
        Set<String> out = new LinkedHashSet<>();
        for (String song : songs) {
            String s = song.toLowerCase(Locale.ROOT);
            if (t.isEmpty() || s.startsWith(t) || s.contains(" " + t) || s.contains("(" + t)) out.add(song);
        }
        return List.copyOf(out);
    }
}
