package dev.spotifychat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Synced lyrics for the overlay, from lrclib.net (free, no login). Only the song's name and artist are
 * sent there, and only while the lyrics option is on.
 *
 * Songs often have several versions there (album, radio edit, sped up...) whose lines are timed differently,
 * so every version is kept with its length, and the one as long as the song Spotify plays is shown. Without
 * a version within a few seconds of it, nothing is shown: lyrics at the wrong time are worse than none.
 */
public final class Lyrics {
    private Lyrics() {}

    public record Line(long timeMs, String text) {}

    /** One version of the lyrics: lengthMs 0 = LRCLIB doesn't know how long it is */
    record Version(long id, long lengthMs, boolean sameTitle, boolean sameArtist, List<Line> lines) {}

    private static final String SEARCH = "https://lrclib.net/api/search";
    private static final int MAX_CACHED = 100;
    /** A version this close to the song's length is timed for it */
    static final long MATCH_MS = 3_500;
    /** LRCLIB is sometimes busy (503): try again after this, a few times */
    private static final long RETRY_MS = 15_000;
    private static final int TRIES = 4;
    /** "[01:23.45]" or "[1:23]" at the start of a line, possibly several */
    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    /** " - Remastered 2011", " (feat. X)", " [Live]": LRCLIB often has the plain title */
    private static final Pattern EXTRAS = Pattern.compile("\\s+-\\s+.*$|\\s*[(\\[][^)\\]]*[)\\]]");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Spotify Chat lyrics");
        t.setDaemon(true);
        return t;
    });

    /** What's known about one song's lyrics. Written by the worker, read by the render thread. */
    private static final class Entry {
        volatile List<Version> versions; // null = not found out yet
        volatile boolean searchedWide;   // also searched for "artist title" (finds more versions)
        volatile boolean loading;
        volatile long retryAt;
        volatile int tries;
    }

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    /**
     * The lines for this song, or null while they load / if there's no version that fits.
     * lengthMs: how long Spotify says the song is (0 = unknown).
     */
    public static List<Line> get(SpotifyClient.Track t, long lengthMs) {
        String key = PlaybackClock.key(t.song()) + "\n" + t.artist().toLowerCase(Locale.ROOT);
        Entry e = CACHE.get(key);
        if (e == null) {
            if (CACHE.size() >= MAX_CACHED) CACHE.clear();
            e = CACHE.computeIfAbsent(key, k -> new Entry());
        }
        List<Version> versions = e.versions;
        if (versions == null) {
            load(e, t.song(), t.artist(), false);
            return null;
        }
        List<Line> lines = choose(versions, lengthMs);
        // No version fits (or none at all): a wider search often finds the right one
        if (lines == null && !e.searchedWide && (lengthMs > 0 || versions.isEmpty())) {
            load(e, t.song(), t.artist(), true);
        }
        return lines;
    }

    private static void load(Entry e, String song, String artist, boolean wide) {
        long now = System.currentTimeMillis();
        if (e.loading || now < e.retryAt || e.tries >= TRIES) return;
        e.loading = true;
        e.tries++;
        WORKER.execute(() -> {
            try {
                Map<Long, Version> found = new LinkedHashMap<>();
                if (e.versions != null) e.versions.forEach(v -> found.put(v.id(), v));
                for (Version v : fetch(song, artist, wide)) found.putIfAbsent(v.id(), v);
                if (wide) e.searchedWide = true;
                e.versions = List.copyOf(found.values());
                e.tries = 0;
            } catch (Exception ex) {
                SpotifyChatClient.LOGGER.debug("Lyrics not loaded yet for {} - {}", artist, song, ex);
                e.retryAt = System.currentTimeMillis() + RETRY_MS;
            } finally {
                e.loading = false;
            }
        });
    }

    /**
     * The version timed for a song of this length. Without the length: the length most versions with the
     * same title and artist have (that's usually the album version).
     */
    static List<Line> choose(List<Version> versions, long lengthMs) {
        if (versions.isEmpty()) return null;
        if (lengthMs > 0) {
            Version best = null;
            for (Version v : versions) {
                if (v.lengthMs() <= 0 || Math.abs(v.lengthMs() - lengthMs) > MATCH_MS) continue;
                if (best == null || score(v, lengthMs) > score(best, lengthMs)) best = v;
            }
            return best == null ? null : best.lines();
        }
        Map<Long, Integer> seconds = new HashMap<>();
        for (Version v : versions) {
            if (v.sameTitle() && v.sameArtist() && v.lengthMs() > 0) seconds.merge(v.lengthMs() / 1000, 1, Integer::sum);
        }
        long common = seconds.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(-1L);
        return versions.stream()
                .filter(v -> v.sameTitle() && v.sameArtist() && (common < 0 || v.lengthMs() / 1000 == common))
                .findFirst().or(() -> versions.stream().findFirst()).map(Version::lines).orElse(null);
    }

    /** Closer in length is better; the same title and artist break ties */
    private static double score(Version v, long lengthMs) {
        return -Math.abs(v.lengthMs() - lengthMs) / 1000.0 + (v.sameTitle() ? 0.5 : 0) + (v.sameArtist() ? 0.5 : 0);
    }

    /** The line showing at this point of the song: -1 before the first one */
    public static int indexAt(List<Line> lines, long ms) {
        int lo = 0, hi = lines.size() - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).timeMs() <= ms) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    /** Throws when LRCLIB doesn't answer properly, so it's tried again later */
    private static List<Version> fetch(String song, String artist, boolean wide) throws Exception {
        Set<String> titles = new LinkedHashSet<>();
        titles.add(song.trim());
        titles.add(EXTRAS.matcher(song).replaceAll("").trim());
        titles.remove("");
        List<Version> all = new ArrayList<>();
        for (String title : titles) {
            String query = wide ? "q=" + enc((artist + " " + title).trim())
                    : "track_name=" + enc(title) + (artist.isBlank() ? "" : "&artist_name=" + enc(artist));
            all.addAll(versions(search(query), song, artist));
            if (!wide && !all.isEmpty()) break; // the plain title is only for when the full one finds nothing
        }
        return all;
    }

    private static JsonElement search(String query) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(SEARCH + "?" + query)).timeout(Duration.ofSeconds(8))
                .header("User-Agent", "SpotifyChat (https://github.com/Doelloosleven/Spotify-Chat)").GET().build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IOException("lrclib.net answered " + res.statusCode());
        return JsonParser.parseString(res.body());
    }

    /** Every search result with synced lyrics */
    static List<Version> versions(JsonElement results, String title, String artist) {
        List<Version> out = new ArrayList<>();
        if (!results.isJsonArray()) return out;
        JsonArray array = results.getAsJsonArray();
        String plainTitle = EXTRAS.matcher(title).replaceAll("").trim();
        for (JsonElement el : array) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (o.has("instrumental") && !o.get("instrumental").isJsonNull() && o.get("instrumental").getAsBoolean()) {
                continue;
            }
            List<Line> lines = parse(str(o, "syncedLyrics"));
            if (lines.isEmpty()) continue;
            String name = str(o, "trackName");
            boolean sameTitle = name.equalsIgnoreCase(title) || name.equalsIgnoreCase(plainTitle);
            boolean sameArtist = !artist.isBlank()
                    && str(o, "artistName").toLowerCase(Locale.ROOT).contains(artist.toLowerCase(Locale.ROOT));
            long length = o.has("duration") && !o.get("duration").isJsonNull()
                    ? Math.round(o.get("duration").getAsDouble() * 1000) : 0;
            long id = o.has("id") && !o.get("id").isJsonNull() ? o.get("id").getAsLong() : out.size() - 1_000_000L;
            out.add(new Version(id, length, sameTitle, sameArtist, lines));
        }
        return out;
    }

    /** LRC text to lines sorted by time. A line with several time tags is repeated at each. */
    static List<Line> parse(String lrc) {
        List<Line> lines = new ArrayList<>();
        for (String raw : lrc.split("\r?\n")) {
            Matcher m = TIME.matcher(raw);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                long ms = Long.parseLong(m.group(1)) * 60_000 + Long.parseLong(m.group(2)) * 1000;
                if (m.group(3) != null) {
                    String frac = (m.group(3) + "00").substring(0, 3); // "45" = 450 ms, "4" = 400 ms
                    ms += Long.parseLong(frac);
                }
                times.add(ms);
                end = m.end();
            }
            if (times.isEmpty()) continue; // [ar:...] tags and plain text
            String text = raw.substring(end).trim();
            for (long t : times) lines.add(new Line(t, text));
        }
        lines.sort(Comparator.comparingLong(Line::timeMs));
        return List.copyOf(lines);
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
