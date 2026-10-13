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
import java.util.Base64;
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
 * Synced lyrics for the overlay. Only the song's name and artist are sent, and only while the lyrics option
 * is on. The sources are asked one after another until one has the song: lrclib.net (free, open), then the
 * lyrics of NetEase Cloud Music and Kugou, two big music services that also have most western songs.
 *
 * Songs often have several versions (album, radio edit, sped up...) whose lines are timed differently,
 * so every version is kept with its length, and the one as long as the song Spotify plays is shown. Without
 * a version within a few seconds of it, the next source is asked, and in the end nothing is shown: lyrics at
 * the wrong time are worse than none.
 */
public final class Lyrics {
    private Lyrics() {}

    public record Line(long timeMs, String text) {}

    /** One version of the lyrics: lengthMs 0 = the source doesn't know how long it is */
    record Version(long id, long lengthMs, boolean sameTitle, boolean sameArtist, List<Line> lines) {}

    /** A search result of NetEase or Kugou that is this song: its id (and Kugou's key) and length */
    record Candidate(String id, String key, long lengthMs) {}

    private static final String LRCLIB_SEARCH = "https://lrclib.net/api/search";
    private static final String NETEASE_SEARCH = "https://music.163.com/api/cloudsearch/pc?type=1&limit=10&s=";
    private static final String NETEASE_LYRIC = "https://music.163.com/api/song/lyric?lv=1&id=";
    private static final String KUGOU_SEARCH = "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=";
    private static final String KUGOU_DOWNLOAD = "https://lyrics.kugou.com/download?ver=1&client=pc&fmt=lrc&charset=utf8&id=";
    /** LRCLIB, LRCLIB searched wider ("artist title"), NetEase, Kugou */
    private static final int SOURCES = 4;
    /** Version ids of the other sources, so they never clash with LRCLIB's */
    private static final long KUGOU_IDS = 100_000_000_000L;
    /** At most this many lyrics downloaded per source and song */
    private static final int MAX_DOWNLOADS = 3;
    private static final int MAX_CACHED = 100;
    /** A version this close to the song's length is timed for it */
    static final long MATCH_MS = 3_500;
    /** A source is sometimes busy (503): try again after this, a few times, then go on to the next one */
    private static final long RETRY_MS = 15_000;
    private static final int TRIES = 3;
    /** "[01:23.45]" or "[1:23]" at the start of a line, possibly several */
    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    /** " - Remastered 2011", " (feat. X)", " [Live]", " (Explicit)": sources often have the plain title */
    private static final Pattern EXTRAS = Pattern.compile("\\s+-\\s+.*$|\\s*[(\\[][^)\\]]*[)\\]]");
    /** Credit lines some sources put in the lyrics: "作词 : X" (lyrics by), "Composed by：X" */
    private static final Pattern CREDIT = Pattern.compile("^\\s*(?:作词|作曲|编曲|词|曲|制作人|制作|监制|混音|母带|和声|录音"
            + "|吉他|贝斯|鼓|弦乐|键盘|出品|发行|企划|统筹|OP|SP|(?:lyrics|music|words|written|composed|produced|arranged"
            + "|mixed|mastered|recorded)\\s+by|composers?|lyricists?|producers?|arrangers?|writers?)\\s*[:：]",
            Pattern.CASE_INSENSITIVE);
    /** Kugou lists several singers in one text */
    private static final Pattern SINGERS = Pattern.compile("\\s*(?:、|,|&|/|;)\\s*");

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
        volatile int source;             // the next source to ask
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
            load(e, t.song(), t.artist(), lengthMs);
            return null;
        }
        List<Line> lines = choose(versions, lengthMs);
        // No version fits (or none at all): the next source may have the right one
        if (lines == null && (lengthMs > 0 || versions.isEmpty())) load(e, t.song(), t.artist(), lengthMs);
        return lines;
    }

    private static void load(Entry e, String song, String artist, long lengthMs) {
        long now = System.currentTimeMillis();
        if (e.loading || now < e.retryAt || e.source >= SOURCES) return;
        e.loading = true;
        int source = e.source;
        WORKER.execute(() -> {
            try {
                Map<Long, Version> found = new LinkedHashMap<>();
                if (e.versions != null) e.versions.forEach(v -> found.put(v.id(), v));
                for (Version v : fetch(source, song, artist, lengthMs)) found.putIfAbsent(v.id(), v);
                e.versions = List.copyOf(found.values());
                e.source = source + 1;
                e.tries = 0;
            } catch (Exception ex) {
                SpotifyChatClient.LOGGER.debug("Lyrics source {} didn't answer for {} - {}", source, artist, song, ex);
                e.retryAt = System.currentTimeMillis() + RETRY_MS;
                if (++e.tries >= TRIES) { // that source is down: on to the next one
                    e.source = source + 1;
                    e.tries = 0;
                }
            } finally {
                e.loading = false;
            }
        });
    }

    /** Throws when the source doesn't answer properly, so it's tried again later */
    private static List<Version> fetch(int source, String song, String artist, long lengthMs) throws Exception {
        return switch (source) {
            case 0 -> lrclib(song, artist, false);
            case 1 -> lrclib(song, artist, true);
            case 2 -> netease(song, artist, lengthMs);
            default -> kugou(song, artist, lengthMs);
        };
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

    // ---------------------------------------------------------------- LRCLIB

    private static List<Version> lrclib(String song, String artist, boolean wide) throws Exception {
        Set<String> titles = new LinkedHashSet<>();
        titles.add(song.trim());
        titles.add(plain(song));
        titles.remove("");
        List<Version> all = new ArrayList<>();
        for (String title : titles) {
            String query = wide ? "q=" + enc((artist + " " + title).trim())
                    : "track_name=" + enc(title) + (artist.isBlank() ? "" : "&artist_name=" + enc(artist));
            all.addAll(versions(json(LRCLIB_SEARCH + "?" + query), song, artist));
            if (!wide && !all.isEmpty()) break; // the plain title is only for when the full one finds nothing
        }
        return all;
    }

    /** Every LRCLIB search result with synced lyrics */
    static List<Version> versions(JsonElement results, String title, String artist) {
        List<Version> out = new ArrayList<>();
        if (!results.isJsonArray()) return out;
        JsonArray array = results.getAsJsonArray();
        String plainTitle = plain(title);
        for (JsonElement el : array) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (o.has("instrumental") && !o.get("instrumental").isJsonNull() && o.get("instrumental").getAsBoolean()) {
                continue;
            }
            List<Line> lines = clean(parse(str(o, "syncedLyrics")), title, artist);
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

    // --------------------------------------------------------------- NetEase

    private static List<Version> netease(String song, String artist, long lengthMs) throws Exception {
        JsonElement found = json(NETEASE_SEARCH + enc((artist + " " + plain(song)).trim()));
        List<Version> out = new ArrayList<>();
        for (Candidate c : neteaseCandidates(found, song, artist, lengthMs)) {
            JsonElement res = json(NETEASE_LYRIC + c.id());
            if (!res.isJsonObject()) continue;
            JsonElement lrc = res.getAsJsonObject().get("lrc");
            String text = lrc != null && lrc.isJsonObject() ? str(lrc.getAsJsonObject(), "lyric") : "";
            List<Line> lines = clean(parse(text), song, artist);
            if (!lines.isEmpty()) out.add(new Version(-Long.parseLong(c.id()), c.lengthMs(), true, true, lines));
        }
        return out;
    }

    /** NetEase's search results that are this song by this artist, the ones closest in length first */
    static List<Candidate> neteaseCandidates(JsonElement root, String song, String artist, long lengthMs) {
        List<Candidate> out = new ArrayList<>();
        JsonArray songs = array(root, "result", "songs");
        if (songs == null) return out;
        for (JsonElement el : songs) {
            if (!el.isJsonObject()) continue;
            JsonObject s = el.getAsJsonObject();
            String id = str(s, "id");
            if (!id.matches("\\d{1,15}")) continue; // goes into a URL
            List<String> artists = new ArrayList<>();
            JsonElement ar = s.get("ar");
            if (ar != null && ar.isJsonArray()) {
                for (JsonElement a : ar.getAsJsonArray()) if (a.isJsonObject()) artists.add(str(a.getAsJsonObject(), "name"));
            }
            if (sameSong(str(s, "name"), artists, song, artist)) out.add(new Candidate(id, "", number(s, "dt")));
        }
        return closest(out, lengthMs);
    }

    // ----------------------------------------------------------------- Kugou

    private static List<Version> kugou(String song, String artist, long lengthMs) throws Exception {
        String keyword = (artist.isBlank() ? "" : artist + " - ") + plain(song);
        JsonElement found = json(KUGOU_SEARCH + enc(keyword) + (lengthMs > 0 ? "&duration=" + lengthMs : ""));
        List<Version> out = new ArrayList<>();
        for (Candidate c : kugouCandidates(found, song, artist, lengthMs)) {
            JsonElement res = json(KUGOU_DOWNLOAD + c.id() + "&accesskey=" + c.key());
            if (!res.isJsonObject()) continue;
            String text;
            try {
                text = new String(Base64.getDecoder().decode(str(res.getAsJsonObject(), "content")), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            List<Line> lines = clean(parse(text), song, artist);
            if (!lines.isEmpty()) out.add(new Version(-(KUGOU_IDS + Long.parseLong(c.id())), c.lengthMs(), true, true, lines));
        }
        return out;
    }

    /** Kugou's lyrics for this song by this artist, the ones closest in length first */
    static List<Candidate> kugouCandidates(JsonElement root, String song, String artist, long lengthMs) {
        List<Candidate> out = new ArrayList<>();
        JsonArray candidates = array(root, "candidates");
        if (candidates == null) return out;
        for (JsonElement el : candidates) {
            if (!el.isJsonObject()) continue;
            JsonObject c = el.getAsJsonObject();
            String id = str(c, "id"), key = str(c, "accesskey");
            if (!id.matches("\\d{1,15}") || !key.matches("[0-9A-Fa-f]{1,64}")) continue; // they go into a URL
            List<String> singers = List.of(SINGERS.split(str(c, "singer")));
            if (sameSong(str(c, "song"), singers, song, artist)) out.add(new Candidate(id, key, number(c, "duration")));
        }
        return closest(out, lengthMs);
    }

    // --------------------------------------------------------------- helpers

    /** The same song: the title without extras matches, and one of the artists is ours (when we know it) */
    static boolean sameSong(String foundTitle, List<String> foundArtists, String song, String artist) {
        String a = TrackInfoLookup.loose(plain(foundTitle)), b = TrackInfoLookup.loose(plain(song));
        if (a.isEmpty() || !a.equals(b)) return false;
        return artist.isBlank() || foundArtists.stream().anyMatch(f -> TrackInfoLookup.sameArtist(f, artist));
    }

    /** With the song's length: only the ones that could fit, closest first. At most MAX_DOWNLOADS. */
    private static List<Candidate> closest(List<Candidate> found, long lengthMs) {
        List<Candidate> list = new ArrayList<>(found);
        if (lengthMs > 0) {
            list.removeIf(c -> c.lengthMs() <= 0 || Math.abs(c.lengthMs() - lengthMs) > MATCH_MS);
            list.sort(Comparator.comparingLong(c -> Math.abs(c.lengthMs() - lengthMs)));
        }
        return List.copyOf(list.subList(0, Math.min(MAX_DOWNLOADS, list.size())));
    }

    /**
     * Without the credits ("作词 : X", "Composed by：X"), "Song - Artist" title lines and "纯音乐，请欣赏"
     * ("instrumental, enjoy"). Empty if nothing but those is left.
     */
    static List<Line> clean(List<Line> lines, String title, String artist) {
        String t = TrackInfoLookup.loose(plain(title)), a = TrackInfoLookup.loose(artist);
        List<Line> out = new ArrayList<>();
        for (Line l : lines) {
            String text = l.text();
            if (CREDIT.matcher(text).find() || text.contains("纯音乐")) continue;
            String loose = TrackInfoLookup.loose(text);
            if (l.timeMs() < 15_000 && !t.isEmpty() && !a.isEmpty() && loose.contains(t) && loose.contains(a)) continue;
            out.add(l);
        }
        return out.stream().anyMatch(l -> !l.text().isBlank()) ? List.copyOf(out) : List.of();
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

    /** "Song (feat. X) - Remastered" -> "Song" */
    private static String plain(String title) {
        String p = EXTRAS.matcher(title).replaceAll("").trim();
        return p.isEmpty() ? title.trim() : p;
    }

    private static JsonElement json(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                .header("User-Agent", "SpotifyChat (https://github.com/Doelloosleven/Spotify-Chat)").GET().build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IOException(URI.create(url).getHost() + " answered " + res.statusCode());
        return JsonParser.parseString(res.body());
    }

    /** root.a.b... as an array, or null */
    private static JsonArray array(JsonElement root, String... path) {
        JsonElement e = root;
        for (String key : path) {
            if (e == null || !e.isJsonObject()) return null;
            e = e.getAsJsonObject().get(key);
        }
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    private static long number(JsonObject o, String key) {
        try {
            JsonElement e = o.get(key);
            return e == null || e.isJsonNull() ? 0 : e.getAsLong();
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
