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
 * featured artists are looked up in Deezer's free public API (no account or key). Results are cached
 * per song, and the background check starts the lookup as soon as a new song plays, so sharing
 * doesn't have to wait for it.
 */
public final class TrackInfoLookup {
    private TrackInfoLookup() {}

    /** album "" = not found; artists = main artist first, then featured artists (empty = not found) */
    public record Info(String album, List<String> artists) {
        static final Info NONE = new Info("", List.of());
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private static final Map<String, CompletableFuture<Info>> CACHE = new ConcurrentHashMap<>();

    /** Starts the lookup in the background if it isn't cached yet. */
    public static void prefetch(String artist, String song) {
        lookup(artist, song);
    }

    /** Info, or NONE if not found within a few seconds. */
    public static Info find(String artist, String song) {
        try {
            return lookup(artist, song).get(6, TimeUnit.SECONDS);
        } catch (Exception e) {
            return Info.NONE;
        }
    }

    private static CompletableFuture<Info> lookup(String artist, String song) {
        String key = (artist + "\n" + song).toLowerCase(Locale.ROOT);
        CompletableFuture<Info> f = CACHE.computeIfAbsent(key,
                k -> CompletableFuture.supplyAsync(() -> search(artist, song)));
        // Network errors aren't cached, so the next share tries again
        f.whenComplete((v, e) -> { if (e != null) CACHE.remove(key, f); });
        return f;
    }

    private static Info search(String artist, String song) {
        String title = cleanTitle(song);
        try {
            JsonElement data = get("https://api.deezer.com/search?limit=15&q="
                    + URLEncoder.encode(artist + " " + title, StandardCharsets.UTF_8)).get("data");
            if (data == null || !data.isJsonArray()) return Info.NONE;

            // Same artist and same song first, otherwise any song by that artist
            List<JsonObject> sameSong = new ArrayList<>();
            List<JsonObject> sameArtist = new ArrayList<>();
            for (JsonElement el : data.getAsJsonArray()) {
                JsonObject t = el.getAsJsonObject();
                if (!str(t.getAsJsonObject("artist"), "name").equalsIgnoreCase(artist)) continue;
                sameArtist.add(t);
                if (str(t, "title_short").equalsIgnoreCase(title)) sameSong.add(t);
            }
            List<JsonObject> candidates = sameSong.isEmpty() ? sameArtist : sameSong;
            if (candidates.isEmpty()) return Info.NONE; // rather nothing than a wrong album

            // Prefer a real album over a single named after the song
            JsonObject best = candidates.getFirst();
            String lowerTitle = title.toLowerCase(Locale.ROOT);
            for (JsonObject t : candidates) {
                if (!str(t.getAsJsonObject("album"), "title").toLowerCase(Locale.ROOT).startsWith(lowerTitle)) {
                    best = t;
                    break;
                }
            }
            String album = str(best.getAsJsonObject("album"), "title");
            // Featured artists are only listed on the song's own page; only trust them for the exact song
            List<String> artists = sameSong.isEmpty() ? List.of() : contributors(str(best, "id"));
            return new Info(album, artists);
        } catch (Exception e) {
            SpotifyChatClient.LOGGER.debug("Track info lookup failed for {} - {}", artist, song, e);
            throw new RuntimeException(e);
        }
    }

    private static List<String> contributors(String trackId) {
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
        if (res.statusCode() != 200) throw new IllegalStateException("Deezer " + res.statusCode());
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
