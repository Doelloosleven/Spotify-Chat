package dev.spotifychat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The Spotify desktop app's window title only has the main artist and no album, so the album and
 * featured artists are looked up in Deezer's free public API (no account or key), with Apple's free iTunes
 * search as a fallback for the album and cover. Results are cached
 * per song, and the background check starts the lookup as soon as a new song plays, so sharing
 * doesn't have to wait for it.
 */
public final class TrackInfoLookup {
    private TrackInfoLookup() {}

    /**
     * album "" = not found; artists = main artist first, then featured artists (empty = not found);
     * coverUrl = album cover image ("" = not found)
     */
    public record Info(String album, List<String> artists, String coverUrl) {
        static final Info NONE = new Info("", List.of(), "");
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private static final Map<String, CompletableFuture<Info>> CACHE = new ConcurrentHashMap<>();

    /** Starts the lookup in the background if it isn't cached yet. */
    public static void prefetch(String artist, String song) {
        lookup(artist, song);
    }

    /** Never waits: the info if the lookup already finished, otherwise null (and the lookup is started). */
    public static Info peek(String artist, String song) {
        CompletableFuture<Info> f = lookup(artist, song);
        return f.isDone() && !f.isCompletedExceptionally() ? f.getNow(null) : null;
    }

    /** Info, or NONE if not found within a few seconds. */
    public static Info find(String artist, String song) {
        try {
            return lookup(artist, song).get(6, TimeUnit.SECONDS);
        } catch (Exception e) {
            return Info.NONE;
        }
    }

    /** A failed lookup (no internet, Deezer busy) is tried again after this, not every frame */
    private static final long RETRY_MS = 15_000;
    private static final Map<String, Long> FAILED_AT = new ConcurrentHashMap<>();

    private static CompletableFuture<Info> lookup(String artist, String song) {
        String key = (artist + "\n" + song).toLowerCase(Locale.ROOT);
        CompletableFuture<Info> cached = CACHE.get(key);
        if (cached == null && System.currentTimeMillis() - FAILED_AT.getOrDefault(key, 0L) < RETRY_MS) {
            return CompletableFuture.failedFuture(new IllegalStateException("tried a moment ago"));
        }
        CompletableFuture<Info> f = CACHE.computeIfAbsent(key,
                k -> CompletableFuture.supplyAsync(() -> search(artist, song)));
        // Network errors aren't cached, so it's tried again a bit later
        f.whenComplete((v, e) -> {
            if (e == null) return;
            FAILED_AT.put(key, System.currentTimeMillis());
            CACHE.remove(key, f);
        });
        return f;
    }

    /**
     * Deezer by "artist title", then by the title alone (the artist may be written differently there),
     * then Apple's iTunes search. Names are compared loosely: "JAŸ-Z" is "Jay-Z", accents don't count.
     */
    private static Info search(String artist, String song) {
        String title = cleanTitle(song);
        try {
            Info info = deezer(artist, title, artist + " " + title);
            if (info == null) info = deezer(artist, title, title);
            if (info == null) info = itunes(artist, title);
            return info == null ? Info.NONE : info;
        } catch (Exception e) {
            SpotifyChatClient.LOGGER.debug("Track info lookup failed for {} - {}", artist, song, e);
            throw new RuntimeException(e);
        }
    }

    /** Null if Deezer doesn't have it; throws if Deezer didn't answer properly */
    private static Info deezer(String artist, String title, String query) throws Exception {
        JsonObject root = get("https://api.deezer.com/search?limit=25&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
        // Deezer answers "quota exceeded" and other errors with 200 and an error object: not a "not found"
        if (root.has("error")) throw new IllegalStateException("Deezer: " + root.get("error"));
        JsonElement data = root.get("data");
        if (data == null || !data.isJsonArray()) return null;

        // Same artist and same song first, otherwise any song by that artist
        boolean byTitle = query.equals(title);
        List<JsonObject> sameSong = new ArrayList<>();
        List<JsonObject> sameArtist = new ArrayList<>();
        for (JsonElement el : data.getAsJsonArray()) {
            JsonObject t = el.getAsJsonObject();
            if (!sameArtist(str(t.getAsJsonObject("artist"), "name"), artist)) continue;
            sameArtist.add(t);
            if (loose(str(t, "title_short")).equals(loose(title))) sameSong.add(t);
        }
        // Searching by the title alone finds that title by anyone: only trust the exact song then
        List<JsonObject> candidates = sameSong.isEmpty() && !byTitle ? sameArtist : sameSong;
        if (candidates.isEmpty()) return null; // rather nothing than a wrong album

        // Prefer a real album over a single named after the song
        JsonObject best = candidates.getFirst();
        String lowerTitle = title.toLowerCase(Locale.ROOT);
        for (JsonObject t : candidates) {
            if (!str(t.getAsJsonObject("album"), "title").toLowerCase(Locale.ROOT).startsWith(lowerTitle)) {
                best = t;
                break;
            }
        }
        JsonObject albumObj = best.getAsJsonObject("album");
        String album = str(albumObj, "title");
        String cover = str(albumObj, "cover_medium"); // 250x250
        // Featured artists are only listed on the song's own page; only trust them for the exact song
        List<String> artists = sameSong.isEmpty() ? List.of() : contributors(str(best, "id"));
        return new Info(album, artists, cover);
    }

    /** Apple's free iTunes search: only the exact song by that artist. Null if not found. */
    private static Info itunes(String artist, String title) throws Exception {
        JsonObject root = get("https://itunes.apple.com/search?entity=song&limit=25&term="
                + URLEncoder.encode(artist + " " + title, StandardCharsets.UTF_8));
        JsonElement results = root.get("results");
        if (results == null || !results.isJsonArray()) return null;
        for (JsonElement el : results.getAsJsonArray()) {
            JsonObject t = el.getAsJsonObject();
            if (!sameArtist(str(t, "artistName"), artist)) continue;
            if (!loose(cleanTitle(str(t, "trackName"))).equals(loose(title))) continue;
            String cover = str(t, "artworkUrl100").replace("100x100bb", "300x300bb");
            return new Info(str(t, "collectionName"), List.of(), cover);
        }
        return null;
    }

    /** Lower case without accents, spaces or punctuation: "JAŸ-Z" and "Jay Z" are both "jayz" */
    static String loose(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replace("$", "s").replace("&", "and");
        return n.replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    /** The same artist, loosely; "Beyonce" matches "Beyoncé", "Tyler" doesn't match "Tyler, The Creator" */
    static boolean sameArtist(String found, String artist) {
        String a = loose(found), b = loose(artist);
        return !a.isEmpty() && a.equals(b);
    }

    /** Deezer track ids are plain numbers; anything else could change the URL it's put into */
    static boolean isTrackId(String id) {
        return id.matches("\\d{1,20}");
    }

    private static List<String> contributors(String trackId) {
        if (!isTrackId(trackId)) return List.of();
        try {
            JsonElement list = get("https://api.deezer.com/track/" + trackId).get("contributors");
            if (list == null || !list.isJsonArray()) return List.of();
            List<String> names = new ArrayList<>();
            for (JsonElement c : list.getAsJsonArray()) {
                String name = str(c.getAsJsonObject(), "name");
                if (!name.isBlank() && names.stream().noneMatch(name::equalsIgnoreCase)) names.add(name);
            }
            return names;
        } catch (Exception e) {
            return List.of(); // the album is still useful without them
        }
    }

    private static JsonObject get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException(url.substring(0, 30) + " answered " + res.statusCode());
        return JsonParser.parseString(res.body()).getAsJsonObject();
    }

    /** "Dreams - 2004 Remaster" / "Get Lucky (feat. Pharrell)" -> "Dreams" / "Get Lucky" */
    static String cleanTitle(String song) {
        String s = song.replaceAll("\\s+-\\s+.*$", "");
        s = s.replaceAll("(?i)\\s*[(\\[][^)\\]]*(feat|ft\\.|with|remaster|version|edit|mix|live)[^)\\]]*[)\\]]", "");
        return s.trim();
    }

    private static String str(JsonObject o, String key) {
        if (o == null) return "";
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }
}
