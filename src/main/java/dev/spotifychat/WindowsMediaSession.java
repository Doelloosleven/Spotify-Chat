package dev.spotifychat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Where Spotify is in the song, on Windows. The window title doesn't say, but Windows' media controls
 * (the ones next to the volume popup) do. Plain Java can't ask them, so a small PowerShell script does,
 * once a second, while the lyrics are on. It stops by itself when Minecraft closes.
 */
final class WindowsMediaSession {
    private WindowsMediaSession() {}

    private static final String SCRIPT = """
            $ErrorActionPreference = 'Stop'
            [Console]::OutputEncoding = [Text.Encoding]::UTF8
            Add-Type -AssemblyName System.Runtime.WindowsRuntime
            $asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
            function Await($op, $type) { $t = $asTask.MakeGenericMethod($type).Invoke($null, @($op)); [void]$t.Wait(3000); $t.Result }
            [void][Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]
            $mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
            $props = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties]
            while (Get-Process -Id PARENT -ErrorAction SilentlyContinue) {
              try {
                $s = $mgr.GetSessions() | Where-Object { $_.SourceAppUserModelId -like '*Spotify*' } | Select-Object -First 1
                if ($s) {
                  $t = $s.GetTimelineProperties(); $p = $s.GetPlaybackInfo(); $m = Await ($s.TryGetMediaPropertiesAsync()) $props
                  $title = ($m.Title -replace '[\\t\\r\\n]', ' ')
                  [Console]::Out.WriteLine('S' + "`t" + $title + "`t" + [long]$t.Position.TotalMilliseconds + "`t" + $t.LastUpdatedTime.ToUnixTimeMilliseconds() + "`t" + [long]$t.EndTime.TotalMilliseconds + "`t" + $p.PlaybackStatus)
                } else { [Console]::Out.WriteLine('N') }
                [Console]::Out.Flush()
              } catch { } # e.g. the song changed halfway through reading it: just read again
              Start-Sleep -Milliseconds 1000
            }
            """;

    private static Process process;
    private static long nextStart;

    /** Game thread, every half second: runs the script while it's wanted (lyrics on, on Windows) */
    static synchronized void want(boolean wanted) {
        Process p = process;
        if (!wanted) {
            if (p != null) p.destroy();
            process = null;
            return;
        }
        if (p != null && p.isAlive()) return;
        long now = System.currentTimeMillis();
        if (now < nextStart) return; // it stopped: try again a bit later, not every half second
        nextStart = now + 30_000;
        try {
            String script = SCRIPT.replace("PARENT", Long.toString(ProcessHandle.current().pid()));
            String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            Process started = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            process = started;
            Thread reader = new Thread(() -> read(started), "Spotify Chat media session");
            reader.setDaemon(true);
            reader.start();
        } catch (Exception e) {
            SpotifyChatClient.LOGGER.debug("Couldn't read Windows' media controls", e);
        }
    }

    private static void read(Process p) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                Reading r = parse(line);
                if (r != null) PlaybackClock.reportPlayer(r.title(), r.positionMs(), r.atMs(), r.playing(), r.lengthMs());
            }
        } catch (Exception ignored) {
        } finally {
            p.destroy();
        }
    }

    record Reading(String title, long positionMs, long atMs, boolean playing, long lengthMs) {}

    /** "S<tab>title<tab>position<tab>updated at<tab>length<tab>status", or null if it says nothing useful */
    static Reading parse(String line) {
        String[] f = line.split("\t", -1);
        if (f.length < 6 || !f[0].equals("S") || f[1].isBlank()) return null;
        try {
            long position = Long.parseLong(f[2].trim()), at = Long.parseLong(f[3].trim()), length = Long.parseLong(f[4].trim());
            boolean playing = f[5].trim().equalsIgnoreCase("Playing");
            if (length <= 0 || position < 0 || at <= 0) return null; // Spotify didn't fill in the timeline
            long now = System.currentTimeMillis();
            if (playing && position + (now - at) > length + 5_000) return null; // stale: can't be right
            return new Reading(f[1].trim(), position, at, playing, length);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
