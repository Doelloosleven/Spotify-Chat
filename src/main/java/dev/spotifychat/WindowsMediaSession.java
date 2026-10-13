package dev.spotifychat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;

/**
 * What Windows' media controls (the ones next to the volume popup) know about Spotify: where it is in the
 * song (for the lyrics; the window title doesn't say) and Spotify's own album cover (for songs the online
 * lookups don't find). Plain Java can't ask them, so a small PowerShell script does, once a second. It only
 * runs while it's needed, and stops by itself when Minecraft closes.
 */
final class WindowsMediaSession {
    private WindowsMediaSession() {}

    private static final String SCRIPT = """
            param([int]$parent)
            $ErrorActionPreference = 'Stop'
            [Console]::OutputEncoding = [Text.Encoding]::UTF8
            Add-Type -AssemblyName System.Runtime.WindowsRuntime
            $asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
            function Await($op, $type) { $t = $asTask.MakeGenericMethod($type).Invoke($null, @($op)); [void]$t.Wait(3000); $t.Result }
            [void][Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]
            [void][Windows.Storage.Streams.IRandomAccessStreamWithContentType,Windows.Storage.Streams,ContentType=WindowsRuntime]
            $mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
            $props = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties]
            $toStream = [System.IO.WindowsRuntimeStreamExtensions].GetMethod('AsStreamForRead', [Type[]]@([Windows.Storage.Streams.IInputStream]))
            $coverTitle = ''; $since = 0
            while (Get-Process -Id $parent -ErrorAction SilentlyContinue) {
              try {
                $s = $mgr.GetSessions() | Where-Object { $_.SourceAppUserModelId -like '*Spotify*' } | Select-Object -First 1
                if ($s) {
                  $t = $s.GetTimelineProperties(); $p = $s.GetPlaybackInfo(); $m = Await ($s.TryGetMediaPropertiesAsync()) $props
                  $title = ($m.Title -replace '[\\t\\r\\n]', ' ')
                  [Console]::Out.WriteLine('S' + "`t" + $title + "`t" + [long]$t.Position.TotalMilliseconds + "`t" + $t.LastUpdatedTime.ToUnixTimeMilliseconds() + "`t" + [long]$t.EndTime.TotalMilliseconds + "`t" + $p.PlaybackStatus)
                  # The cover, 2 and 6 seconds into the song: right at the change it can still be the last song's
                  if ($title -ne $coverTitle) { $coverTitle = $title; $since = 0 }
                  $since++
                  if (($since -eq 2 -or $since -eq 6) -and $m.Thumbnail) {
                    $ras = Await ($m.Thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
                    $ms = New-Object System.IO.MemoryStream
                    $toStream.Invoke($null, @($ras)).CopyTo($ms)
                    if ($ms.Length -gt 0 -and $ms.Length -lt 2000000) { [Console]::Out.WriteLine('C' + "`t" + $title + "`t" + [Convert]::ToBase64String($ms.ToArray())) }
                  }
                } else { [Console]::Out.WriteLine('N') }
                [Console]::Out.Flush()
              } catch { } # e.g. the song changed halfway through reading it: just read again
              Start-Sleep -Milliseconds 1000
            }
            """;

    /** Cover "URLs" CoverTextures gets for Spotify's own covers: the bytes come from here, not the internet */
    static final String COVER_PREFIX = "spotify-app:cover/";

    private record Cover(String song, byte[] bytes, int version) {}

    private static volatile Cover cover;
    private static int coverVersion;
    private static Process process;
    private static long nextStart;

    /** Game thread, every half second: runs the script while it's wanted */
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
            Path script = Path.of(System.getProperty("java.io.tmpdir"), "spotifychat-media-2.ps1");
            if (!Files.exists(script) || !Files.readString(script).equals(SCRIPT)) Files.writeString(script, SCRIPT);
            Process started = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-File", script.toString(), Long.toString(ProcessHandle.current().pid()))
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            process = started;
            Thread reader = new Thread(() -> read(started), "Spotify Chat media session");
            reader.setDaemon(true);
            reader.start();
        } catch (Exception e) {
            SpotifyChatClient.LOGGER.debug("Couldn't read Windows' media controls", e);
        }
    }

    /** Spotify's own cover for this song as a CoverTextures URL, or null if there's none (yet) */
    static String coverUrl(String song) {
        Cover c = cover;
        return c != null && c.song().equals(PlaybackClock.key(song)) ? COVER_PREFIX + c.version() : null;
    }

    /** The image behind a coverUrl, or null if a newer cover replaced it */
    static byte[] coverBytes(String url) {
        Cover c = cover;
        return c != null && url.equals(COVER_PREFIX + c.version()) ? c.bytes() : null;
    }

    private static void read(Process p) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("C\t")) {
                    readCover(line);
                    continue;
                }
                Reading r = parse(line);
                if (r != null) PlaybackClock.reportPlayer(r.title(), r.positionMs(), r.atMs(), r.playing(), r.lengthMs());
            }
        } catch (Exception ignored) {
        } finally {
            p.destroy();
        }
    }

    /** "C<tab>title<tab>image as base64" */
    static synchronized void readCover(String line) {
        int tab = line.indexOf('\t', 2);
        if (tab < 0) return;
        try {
            String song = PlaybackClock.key(line.substring(2, tab));
            byte[] bytes = Base64.getDecoder().decode(line.substring(tab + 1).trim());
            Cover c = cover;
            if (c != null && c.song().equals(song) && Arrays.equals(c.bytes(), bytes)) return; // the same picture
            cover = new Cover(song, bytes, ++coverVersion);
        } catch (IllegalArgumentException ignored) {
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
