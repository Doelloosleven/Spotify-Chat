package dev.spotifychat;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import net.minecraft.util.Util;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Play/pause, next and previous for the Spotify desktop app, no login needed.
 * Windows: sends the media command straight to Spotify's window (WM_APPCOMMAND), so it never
 * controls another app by accident. macOS: AppleScript. Linux: playerctl.
 */
public final class SpotifyControls {
    private SpotifyControls() {}

    public enum Action {
        PLAY_PAUSE(14, "playpause", "play-pause", "Play/pause"),
        NEXT(11, "next track", "next", "Next song"),
        PREVIOUS(12, "previous track", "previous", "Previous song");

        final int appCommand;
        final String appleScript;
        final String playerctl;
        public final String label;

        Action(int appCommand, String appleScript, String playerctl, String label) {
            this.appCommand = appCommand;
            this.appleScript = appleScript;
            this.playerctl = playerctl;
            this.label = label;
        }
    }

    private static final int WM_APPCOMMAND = 0x0319;

    /** Runs in the background. Completes with false if the Spotify app isn't open. */
    public static CompletableFuture<Boolean> send(Action action) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                boolean ok = switch (Util.getPlatform()) {
                    case WINDOWS -> windows(action);
                    case OSX -> {
                        String out = LocalSpotify.run("osascript",
                                "-e", "if application \"Spotify\" is running then",
                                "-e", "tell application \"Spotify\" to " + action.appleScript,
                                "-e", "return \"ok\"",
                                "-e", "end if");
                        yield out.contains("ok");
                    }
                    case LINUX -> LocalSpotify.run("playerctl", "-p", "spotify", action.playerctl).isBlank();
                    default -> false;
                };
                if (ok) LocalSpotify.refreshSoon();
                return ok;
            } catch (Exception e) {
                SpotifyChatClient.LOGGER.warn("Could not control Spotify", e);
                return false;
            }
        });
    }

    private static boolean windows(Action action) {
        List<LocalSpotify.SpotifyWindow> windows = LocalSpotify.spotifyWindows();
        if (windows.isEmpty()) return false;
        WinDef.HWND hwnd = windows.getFirst().hwnd();
        User32.INSTANCE.PostMessage(hwnd, WM_APPCOMMAND, new WinDef.WPARAM(0),
                new WinDef.LPARAM((long) action.appCommand << 16));
        return true;
    }
}
