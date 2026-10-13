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

/**
 * Tab in chat after "!queue ": the song playing now and the ones played before it this session, like
 * Minecraft completes player names. "!queue HAZ" + Tab gives "!queue HAZARD DUTY PAY! - JPEGMAFIA".
 * Typing any word of the title or artist finds it.
 */
public final class SongSuggestions {
    private SongSuggestions() {}

    static final String TRIGGER = "!queue ";
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
        if (typed.length() < TRIGGER.length() || !typed.regionMatches(true, 0, TRIGGER, 0, TRIGGER.length())) return null;
        List<String> songs = new ArrayList<>();
        SpotifyClient.Track now = SpotifyChatClient.get().overlayTrack();
        if (now == null) now = LocalSpotify.latest().orElse(null);
        if (now != null && !now.song().isBlank()) songs.add(name(now));
        synchronized (recent) {
            songs.addAll(recent);
        }
        // Built by hand: Brigadier's builder sorts A-Z, and the song playing now should come first
        StringRange range = StringRange.between(TRIGGER.length(), typed.length());
        List<Suggestion> list = matches(typed.substring(TRIGGER.length()), songs).stream()
                .map(song -> new Suggestion(range, song)).toList();
        return CompletableFuture.completedFuture(new Suggestions(range, list));
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
