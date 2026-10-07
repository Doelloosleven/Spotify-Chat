package dev.spotifychat;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.ptr.IntByReference;
import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Reads the current song from the Spotify desktop app on this computer, no login needed.
 * Windows: the Spotify window title is "Artist - Song" while playing and just "Spotify Premium"/"Spotify Free"
 * when paused, so a background check remembers the last song to share it as paused.
 * macOS: AppleScript. Linux: playerctl. Both report paused songs directly.
 * Empty = Spotify isn't running, or nothing has played since it started.
 */
public final class LocalSpotify {
    private LocalSpotify() {}

    /** Last song seen playing in the Windows app, shared as "paused" while the title doesn't show it */
    private static volatile SpotifyClient.Track lastWindowsTrack;
    /** When we first saw the app paused, 0 while playing (the app itself doesn't tell us) */
    private static volatile long pausedSince;
    private static ScheduledExecutorService watcher;

    /**
     * Checks the app in the background so a song is still known after pausing (Windows)
     * and we know how long it has been paused (every OS).
     */
    public static synchronized void startWatching() {
        if (watcher != null) return;
        watcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SpotifyChat-Watcher");
            t.setDaemon(true);
            return t;
        });
        long interval = Util.getPlatform() == Util.OS.WINDOWS ? 2 : 5; // other OSes start a process per check
        watcher.scheduleWithFixedDelay(() -> {
            try {
                read();
            } catch (Throwable ignored) {
            }
        }, 0, interval, TimeUnit.SECONDS);
    }

    public static Optional<SpotifyClient.Track> nowPlaying() {
        try {
            return read();
        } catch (Throwable t) {
            SpotifyChatClient.LOGGER.warn("Could not read the Spotify app", t);
            return Optional.empty();
        }
    }

    private static synchronized Optional<SpotifyClient.Track> read() throws Exception {
        Optional<SpotifyClient.Track> track = switch (Util.getPlatform()) {
            case WINDOWS -> windows();
            case OSX -> mac();
            case LINUX -> linux();
            default -> Optional.empty();
        };
        if (track.isEmpty() || track.get().playing()) {
            pausedSince = 0;
            return track;
        }
        if (pausedSince == 0) pausedSince = System.currentTimeMillis();
        return Optional.of(track.get().paused(pausedSince));
    }

    // -------------------------------------------------------------- Windows

    private static Optional<SpotifyClient.Track> windows() {
        List<String> titles = new ArrayList<>();
        User32 user32 = User32.INSTANCE;
        user32.EnumWindows((hwnd, data) -> {
            if (!user32.IsWindowVisible(hwnd)) return true;
            int len = user32.GetWindowTextLength(hwnd);
            if (len <= 0) return true;
            char[] buf = new char[len + 1];
            user32.GetWindowText(hwnd, buf, buf.length);
            String title = Native.toString(buf);
            if (!title.contains(" - ") && !title.startsWith("Spotify")) return true; // cheap check first

            IntByReference pid = new IntByReference();
            user32.GetWindowThreadProcessId(hwnd, pid);
            boolean isSpotify = ProcessHandle.of(pid.getValue())
                    .flatMap(p -> p.info().command())
                    .map(c -> c.toLowerCase(Locale.ROOT).endsWith("spotify.exe"))
                    .orElse(false);
            if (isSpotify) titles.add(title);
            return true;
        }, null);

        if (titles.isEmpty()) { // Spotify closed
            lastWindowsTrack = null;
            return Optional.empty();
        }
        for (String title : titles) {
            int i = title.indexOf(" - ");
            if (i < 0) continue;
            String artist = title.substring(0, i).trim();
            String song = title.substring(i + 3).trim();
            if (artist.equalsIgnoreCase("Spotify") || song.isEmpty()) continue; // "Spotify - Advertisement"
            // The title only has the main artist; album and featured artists come from TrackInfoLookup
            SpotifyClient.Track track = new SpotifyClient.Track(song, List.of(artist), "", true);
            if (!track.equals(lastWindowsTrack)) {
                TrackInfoLookup.prefetch(artist, song); // new song: look it up now, so sharing is instant
            }
            lastWindowsTrack = track;
            return Optional.of(track);
        }
        // Spotify is open but the title has no song: paused (or an ad)
        SpotifyClient.Track last = lastWindowsTrack;
        return last == null ? Optional.empty() : Optional.of(last.paused(0));
    }

    // ---------------------------------------------------------------- macOS

    private static Optional<SpotifyClient.Track> mac() throws IOException, InterruptedException {
        // "is running" check first, otherwise "tell application" would launch Spotify
        String out = run("osascript",
                "-e", "if application \"Spotify\" is running then",
                "-e", "tell application \"Spotify\"",
                "-e", "if player state is not stopped then return (name of current track) & linefeed & "
                        + "(artist of current track) & linefeed & (album of current track) & linefeed & "
                        + "(player state as string)",
                "-e", "end tell",
                "-e", "end if");
        return parseLines(out, "playing");
    }

    // ---------------------------------------------------------------- Linux

    private static Optional<SpotifyClient.Track> linux() throws IOException, InterruptedException {
        return parseLines(run("playerctl", "-p", "spotify", "metadata",
                "--format", "{{title}}\n{{artist}}\n{{album}}\n{{status}}"), "Playing");
    }

    // -------------------------------------------------------------- helpers

    /** Lines: song, artist, album, state */
    private static Optional<SpotifyClient.Track> parseLines(String out, String playingState) {
        String[] lines = out.strip().split("\n");
        if (lines.length < 4 || lines[0].isBlank()) return Optional.empty();
        boolean playing = lines[3].trim().equalsIgnoreCase(playingState);
        // macOS / Linux give all artists as one "A, B, C" text
        List<String> artists = List.of(lines[1].trim().split("\\s*,\\s*"));
        return Optional.of(new SpotifyClient.Track(lines[0].trim(), artists, lines[2].trim(), playing));
    }

    private static String run(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        if (!p.waitFor(3, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return "";
        }
        return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
