package dev.spotifychat;

import java.util.Locale;

/**
 * How far into the current song Spotify is, for the lyrics. Players that know it (the Web API, Windows'
 * media controls, AppleScript, playerctl) report it; the Spotify window title on Windows doesn't, so for
 * that there's a guess that starts at 0 when the song changes while the game watches.
 * A real reading always wins over the guess.
 */
public final class PlaybackClock {
    private PlaybackClock() {}

    /** A guess from watching the song change */
    static final int GUESS = 0;
    /** Spotify itself said where it is */
    static final int PLAYER = 1;
    /** A real reading older than this no longer blocks the guess (e.g. the media controls stopped answering) */
    private static final long PLAYER_FRESH_MS = 15_000;
    /** A song change only counts as "the song just started" if the previous check was this recent */
    private static final long WATCH_GAP_MS = 6_000;

    /** positionMs at atMs; receivedAt = when we got it */
    record Sample(String song, long positionMs, long atMs, boolean playing, int quality, long receivedAt) {}

    private static volatile Sample sample;
    /** How long the song is (from players that say), to pick the lyrics made for this exact version */
    private static volatile String lengthSong = "";
    private static volatile long lengthMs;
    // What the guess last saw (only touched while holding the class lock)
    private static String seenSong;
    private static boolean seenPlaying;
    private static long seenAt;

    /** The key songs are matched on: just the title, since every source writes the artists differently */
    public static String key(String song) {
        return song == null ? "" : song.trim().toLowerCase(Locale.ROOT);
    }

    /** Spotify was positionMs into the song at atMs */
    static synchronized void report(String song, long positionMs, long atMs, boolean playing, int quality, long now) {
        String k = key(song);
        Sample s = sample;
        if (quality == GUESS && s != null && s.quality > GUESS && s.song.equals(k) && now - s.receivedAt < PLAYER_FRESH_MS) {
            return;
        }
        sample = new Sample(k, Math.max(0, positionMs), atMs, playing, quality, now);
    }

    public static void report(String song, long positionMs, long atMs, boolean playing, int quality) {
        report(song, positionMs, atMs, playing, quality, System.currentTimeMillis());
    }

    /** A player's reading that also says how long the song is (0 = it doesn't) */
    public static void reportPlayer(String song, long positionMs, long atMs, boolean playing, long songLengthMs) {
        if (songLengthMs > 0) {
            lengthMs = songLengthMs;
            lengthSong = key(song);
        }
        report(song, positionMs, atMs, playing, PLAYER, System.currentTimeMillis());
    }

    /** How long this song is in ms, 0 = unknown */
    public static long length(String song) {
        return key(song).equals(lengthSong) ? lengthMs : 0;
    }

    /**
     * For players that don't say where they are: called on every check. A new song that replaces one that
     * was playing a moment ago has just started; play / pause freezes or continues the guess.
     */
    static synchronized void seen(String song, boolean playing, long now) {
        String k = key(song);
        if (!k.equals(seenSong)) {
            if (seenSong != null && seenPlaying && playing && now - seenAt < WATCH_GAP_MS) {
                report(k, 0, now, true, GUESS, now);
            }
        } else if (playing != seenPlaying) {
            long at = position(k, seenPlaying, 0, now);
            if (at >= 0) report(k, at, now, playing, GUESS, now);
        }
        seenSong = k;
        seenPlaying = playing;
        seenAt = now;
    }

    /**
     * Milliseconds into this song right now, or -1 if unknown.
     * pausedSince: when the song was paused (0 = unknown or playing).
     */
    public static long position(String song, boolean playing, long pausedSince, long now) {
        Sample s = sample;
        if (s == null || !s.song.equals(key(song))) return -1;
        if (!s.playing) return s.positionMs;
        long end = playing ? now : pausedSince > 0 ? Math.max(s.atMs, pausedSince) : s.atMs;
        return s.positionMs + Math.max(0, end - s.atMs);
    }

    public static long position(SpotifyClient.Track t, long now) {
        return position(t.song(), t.playing(), t.pausedSince(), now);
    }

    /** For tests */
    static synchronized void reset() {
        sample = null;
        lengthSong = "";
        lengthMs = 0;
        seenSong = null;
        seenPlaying = false;
        seenAt = 0;
    }
}
