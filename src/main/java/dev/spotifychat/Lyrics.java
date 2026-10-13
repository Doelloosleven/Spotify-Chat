package dev.spotifychat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
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
 * sent there, and only while the lyrics option is on. Songs without synced lyrics show none.
 */
public final class Lyrics {
    private Lyrics() {}

    public record Line(long timeMs, String text) {}

    private static final String SEARCH = "https://lrclib.net/api/search";
    private static final int MAX_CACHED = 100;
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
    /** song key -> lines (empty = this song has no synced lyrics) */
    private static final Map<String, List<Line>> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();

    /** The synced lines for this song, or null while they load / if it has none. Starts loading them. */
    public static List<Line> get(SpotifyClient.Track t) {
        String key = PlaybackClock.key(t.song()) + "\n" + t.artist().toLowerCase(Locale.ROOT);
        List<Line> lines = CACHE.get(key);
        if (lines != null) return lines.isEmpty() ? null : lines;
        if (LOADING.add(key)) {
            String song = t.song(), artist = t.artist();
            WORKER.execute(() -> {
                List<Line> found = List.of();
                try {
                    found = fetch(song, artist);
                } catch (Exception e) {
                    SpotifyChatClient.LOGGER.debug("No lyrics for {} - {}", artist, song, e);
                }
                if (CACHE.size() >= MAX_CACHED) CACHE.clear();
                CACHE.put(key, found);
                LOADING.remove(key);
            });
        }
        return null;
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

    private static List<Line> fetch(String song, String artist) throws Exception {
        Set<String> titles = new LinkedHashSet<>();
        titles.add(song.trim());
        titles.add(EXTRAS.matcher(song).replaceAll("").trim());
        for (String title : titles) {
            if (title.isEmpty()) continue;
            String url = SEARCH + "?track_name=" + enc(title) + (artist.isBlank() ? "" : "&artist_name=" + enc(artist));
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "SpotifyChat (https://github.com/Doelloosleven/Spotify-Chat)").GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) continue;
            List<Line> lines = pick(JsonParser.parseString(res.body()), title, artist);
            if (!lines.isEmpty()) return lines;
        }
        return List.of();
    }

    /** The best search result with synced lyrics: same title and artist first */
    static List<Line> pick(JsonElement results, String title, String artist) {
        if (!results.isJsonArray()) return List.of();
        JsonArray array = results.getAsJsonArray();
        List<Line> best = List.of();
        int bestScore = -1;
        for (JsonElement el : array) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (o.has("instrumental") && o.get("instrumental").getAsBoolean()) continue;
            String lrc = str(o, "syncedLyrics");
            if (lrc.isBlank()) continue;
            int score = (str(o, "trackName").equalsIgnoreCase(title) ? 2 : 0)
                    + (str(o, "artistName").toLowerCase(Locale.ROOT).contains(artist.toLowerCase(Locale.ROOT)) ? 1 : 0);
            if (score > bestScore) {
                List<Line> lines = parse(lrc);
                if (!lines.isEmpty()) {
                    best = lines;
                    bestScore = score;
                }
            }
        }
        return best;
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
