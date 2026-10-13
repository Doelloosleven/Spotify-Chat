package dev.spotifychat;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
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

    /** Check again shortly, e.g. right after skipping a song, so the overlay catches up fast. */
    public static void refreshSoon() {
        ScheduledExecutorService w = watcher;
        if (w == null) return;
        for (long delay : new long[]{300, 900}) {
            w.schedule(() -> {
                try {
                    read();
                } catch (Throwable ignored) {
                }
            }, delay, TimeUnit.MILLISECONDS);
        }
    }

    public static Optional<SpotifyClient.Track> nowPlaying() {
        try {
            return read();
        } catch (Throwable t) {
            SpotifyChatClient.LOGGER.warn("Could not read the Spotify app", t);
            return Optional.empty();
        }
    }

    /** What the last check found (the overlay reads this, so it never waits for the app) */
    public static Optional<SpotifyClient.Track> latest() {
        return latest;
    }

    private static volatile Optional<SpotifyClient.Track> latest = Optional.empty();

    private static synchronized Optional<SpotifyClient.Track> read() throws Exception {
        Optional<SpotifyClient.Track> track = switch (Util.getPlatform()) {
            case WINDOWS -> windows();
            case OSX -> mac();
            case LINUX -> linux();
            default -> Optional.empty();
        };
        if (track.isEmpty() || track.get().playing()) {
            pausedSince = 0;
        } else {
            if (pausedSince == 0) pausedSince = System.currentTimeMillis();
            track = Optional.of(track.get().paused(pausedSince));
        }
        // The window title doesn't say where in the song Spotify is: guess it from when the song changed
        if (track.isPresent() && Util.getPlatform() == Util.OS.WINDOWS) {
            PlaybackClock.seen(track.get().song(), track.get().playing(), System.currentTimeMillis());
        }
        if (track.isPresent() && track.get().playing()) SongSuggestions.saw(track.get()); // for Tab after !queue
        latest = track;
        return track;
    }

    // -------------------------------------------------------------- Windows

    /** A visible window of the Spotify desktop app */
    record SpotifyWindow(WinDef.HWND hwnd, String title) {}

    /** The Spotify app's main window(s): visible, with a title, owned by Spotify.exe. Windows only. */
    static List<SpotifyWindow> spotifyWindows() {
        List<SpotifyWindow> found = new ArrayList<>();
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
            if (isSpotify) found.add(new SpotifyWindow(hwnd, title));
            return true;
        }, null);
        return found;
    }

    private static Optional<SpotifyClient.Track> windows() {
        List<String> titles = spotifyWindows().stream().map(SpotifyWindow::title).toList();
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
                "-e", "if player state is not stopped then",
                "-e", "set art to \"\"",
                "-e", "try", // older Spotify versions have no artwork url
                "-e", "set art to artwork url of current track",
                "-e", "end try",
                "-e", "return (name of current track) & linefeed & "
                        + "(artist of current track) & linefeed & (album of current track) & linefeed & "
                        + "(player state as string) & linefeed & (player position as string) & linefeed & "
                        + "(duration of current track as string) & linefeed & art",
                "-e", "end if",
                "-e", "end tell",
                "-e", "end if");
        return parseLines(out, "playing", 1000, 1); // position in seconds, length in ms
    }

    // ---------------------------------------------------------------- Linux

    private static Optional<SpotifyClient.Track> linux() throws IOException, InterruptedException {
        return parseLines(run("playerctl", "-p", "spotify", "metadata",
                "--format", "{{title}}\n{{artist}}\n{{album}}\n{{status}}\n{{position}}\n{{mpris:length}}\n{{mpris:artUrl}}"),
                "Playing", 0.001, 0.001); // both in microseconds
    }

    // -------------------------------------------------------------- helpers

    /**
     * Lines: song, artist, album, state, position, length, cover. msPerPosition / msPerLength: milliseconds
     * per unit of those two (they're for the lyrics).
     */
    static Optional<SpotifyClient.Track> parseLines(String out, String playingState, double msPerPosition,
                                                    double msPerLength) {
        String[] lines = out.strip().split("\n");
        if (lines.length < 4 || lines[0].isBlank()) return Optional.empty();
        boolean playing = lines[3].trim().equalsIgnoreCase(playingState);
        if (lines.length > 4) {
            try { // some languages write "12,5" for 12.5
                double position = Double.parseDouble(lines[4].trim().replace(',', '.'));
                long length = 0;
                if (lines.length > 5) {
                    try {
                        length = Math.round(Double.parseDouble(lines[5].trim().replace(',', '.')) * msPerLength);
                    } catch (NumberFormatException ignored) {
                    }
                }
                PlaybackClock.reportPlayer(lines[0].trim(), Math.round(position * msPerPosition), System.currentTimeMillis(),
                        playing, length);
            } catch (NumberFormatException ignored) {
            }
        }
        // macOS / Linux give all artists as one "A, B, C" text
        List<String> artists = List.of(lines[1].trim().split("\\s*,\\s*"));
        String cover = lines.length > 6 ? spotifyCover(lines[6].trim()) : "";
        return Optional.of(new SpotifyClient.Track(lines[0].trim(), artists, lines[2].trim(), playing, 0, cover));
    }

    /**
     * Spotify's own cover link from the player: only from Spotify's image server. Older Spotify versions on
     * Linux give an open.spotify.com/image link that doesn't work anymore; it's the same picture on i.scdn.co.
     */
    static String spotifyCover(String url) {
        String u = url.replaceFirst("^https?://open\\.spotify\\.com/image/", "https://i.scdn.co/image/");
        return u.matches("https://i\\.scdn\\.co/image/[0-9a-fA-F]{8,64}") ? u : "";
    }

    static String run(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        if (!p.waitFor(3, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return "";
        }
        return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
