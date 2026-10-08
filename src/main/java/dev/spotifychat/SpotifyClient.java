package dev.spotifychat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * Talks to the Spotify Web API using the Authorization Code + PKCE flow,
 * so no client secret is needed inside the mod.
 */
public class SpotifyClient {
    public static final int PORT = 8888;
    public static final String REDIRECT_URI = "http://127.0.0.1:" + PORT + "/callback";
    private static final String SCOPES = "user-read-currently-playing user-read-playback-state";

    /**
     * artists: main artist first, then the featured artists.
     * pausedSince: epoch millis when playback was paused, 0 = playing or unknown.
     * coverUrl: album cover image, "" = unknown.
     */
    public record Track(String song, List<String> artists, String album, boolean playing, long pausedSince,
                        String coverUrl) {
        public Track(String song, List<String> artists, String album, boolean playing) {
            this(song, artists, album, playing, 0, "");
        }

        public Track(String song, List<String> artists, String album, boolean playing, long pausedSince) {
            this(song, artists, album, playing, pausedSince, "");
        }

        /** Main artist */
        public String artist() {
            return artists.isEmpty() ? "" : artists.getFirst();
        }

        /** Featured artists (everyone after the main artist) */
        public List<String> features() {
            return artists.size() <= 1 ? List.of() : artists.subList(1, artists.size());
        }

        public Track paused(long since) {
            return new Track(song, artists, album, false, since, coverUrl);
        }

        public Track withInfo(String newAlbum, List<String> newArtists, String newCoverUrl) {
            return new Track(song, newArtists, newAlbum, playing, pausedSince, newCoverUrl);
        }

        /** Same song by the same artist (ignores play state and looked-up extras) */
        public boolean sameSong(Track other) {
            return other != null && song.equals(other.song) && artist().equals(other.artist());
        }
    }

    private final ModConfig config;
    private final Secrets secrets;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final SecureRandom random = new SecureRandom();

    private String accessToken;
    private long accessTokenExpiresAt;
    private HttpServer loginServer;

    public SpotifyClient(ModConfig config) {
        this.config = config;
        this.secrets = Secrets.load();
        // Older versions kept the login in spotifychat.json: move it out once
        if (config.refreshToken != null) {
            if (!config.refreshToken.isBlank() && secrets.refreshToken.isBlank()) {
                secrets.refreshToken = config.refreshToken;
                secrets.save();
            }
            config.refreshToken = null;
            config.save();
        }
    }

    public boolean isLoggedIn() {
        return !secrets.refreshToken.isBlank();
    }

    /** Forgets the login (log out, or a new Client ID) */
    public synchronized void logout() {
        secrets.refreshToken = "";
        secrets.save();
        accessToken = null;
    }

    // ---------------------------------------------------------------- login

    /**
     * Starts a tiny local web server to catch Spotify's redirect and returns the URL
     * the player must open. The future completes when login has finished.
     */
    public synchronized LoginSession startLogin() throws IOException {
        stopLoginServer();

        String verifier = randomString(64);
        String challenge = base64Url(sha256(verifier));
        String state = randomString(16);

        String authUrl = "https://accounts.spotify.com/authorize"
                + "?client_id=" + enc(config.clientId)
                + "&response_type=code"
                + "&redirect_uri=" + enc(REDIRECT_URI)
                + "&code_challenge_method=S256"
                + "&code_challenge=" + enc(challenge)
                + "&scope=" + enc(SCOPES)
                + "&state=" + enc(state);

        CompletableFuture<Void> done = new CompletableFuture<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "SpotifyChat-Login");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/callback", exchange -> {
            Map<String, String> q = parseQuery(exchange.getRequestURI().getRawQuery());
            // Without our state it isn't Spotify answering this login (another page poking the port, an old
            // tab): it must neither finish nor fail the login
            byte[] given = q.getOrDefault("state", "").getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8), given)) {
                respond(exchange, PAGE_IGNORED);
                return;
            }
            // Fixed pages only: nothing from the request is ever put into the HTML
            String page;
            try {
                if (q.containsKey("error")) {
                    throw new IOException("Spotify said: " + q.get("error"));
                }
                exchangeCode(q.get("code"), verifier);
                page = PAGE_OK;
                done.complete(null);
            } catch (Exception e) {
                page = PAGE_FAILED; // the reason is shown in Minecraft's chat
                done.completeExceptionally(e);
            }
            respond(exchange, page);
            CompletableFuture.runAsync(this::stopLoginServer);
        });
        server.start();
        loginServer = server;

        // Give up after 5 minutes so the port is freed
        done.orTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
                .whenComplete((v, e) -> stopLoginServer());

        return new LoginSession(authUrl, done);
    }

    public record LoginSession(String url, CompletableFuture<Void> result) {}

    private static final String PAGE_OK = "<h2>Logged in! You can close this tab and go back to Minecraft.</h2>";
    private static final String PAGE_FAILED = "<h2>Login failed. The reason is shown in Minecraft.</h2>";
    private static final String PAGE_IGNORED = "<h2>This isn't the Spotify Chat login you started. "
            + "Use the link from Minecraft.</h2>";

    private static void respond(HttpExchange exchange, String page) throws IOException {
        byte[] body = page.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private synchronized void stopLoginServer() {
        if (loginServer != null) {
            loginServer.stop(0);
            loginServer = null;
        }
    }

    private void exchangeCode(String code, String verifier) throws IOException, InterruptedException {
        JsonObject json = postToken(Map.of(
                "grant_type", "authorization_code",
                "code", code,
                "redirect_uri", REDIRECT_URI,
                "client_id", config.clientId,
                "code_verifier", verifier));
        handleTokenResponse(json);
    }

    private synchronized String getAccessToken() throws IOException, InterruptedException {
        if (accessToken != null && System.currentTimeMillis() < accessTokenExpiresAt - 30_000) {
            return accessToken;
        }
        if (!isLoggedIn()) throw new IOException("Not logged in. Type /spotify login");
        JsonObject json = postToken(Map.of(
                "grant_type", "refresh_token",
                "refresh_token", secrets.refreshToken,
                "client_id", config.clientId));
        handleTokenResponse(json);
        return accessToken;
    }

    private synchronized void handleTokenResponse(JsonObject json) {
        accessToken = json.get("access_token").getAsString();
        accessTokenExpiresAt = System.currentTimeMillis() + json.get("expires_in").getAsLong() * 1000L;
        if (json.has("refresh_token")) {
            secrets.refreshToken = json.get("refresh_token").getAsString();
            secrets.save();
        }
    }

    private JsonObject postToken(Map<String, String> form) throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder();
        form.forEach((k, v) -> {
            if (!body.isEmpty()) body.append('&');
            body.append(enc(k)).append('=').append(enc(v));
        });
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://accounts.spotify.com/api/token"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            if (res.body().contains("invalid_grant")) {
                // Refresh token revoked or expired: force a fresh login
                secrets.refreshToken = "";
                secrets.save();
                throw new IOException("Spotify login expired. Type /spotify login");
            }
            throw new IOException("Token request failed (" + res.statusCode() + "): " + res.body());
        }
        return JsonParser.parseString(res.body()).getAsJsonObject();
    }

    // ---------------------------------------------------------- now playing

    /** Runs off the game thread. Empty = nothing is playing right now. */
    public CompletableFuture<Optional<Track>> fetchNowPlaying() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return nowPlaying();
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        });
    }

    private Optional<Track> nowPlaying() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(
                        "https://api.spotify.com/v1/me/player/currently-playing?additional_types=track,episode"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + getAccessToken())
                .GET()
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

        if (res.statusCode() == 204 || res.body().isBlank()) return Optional.empty();
        if (res.statusCode() == 401) {
            synchronized (this) { accessToken = null; }
            throw new IOException("Spotify rejected the token, try again or /spotify login");
        }
        if (res.statusCode() == 403) {
            throw new IOException("Spotify refused (403). Is your Spotify account added under "
                    + "'User Management' in your app, and does the app owner have Premium?");
        }
        if (res.statusCode() == 429) throw new IOException("Too many requests, wait a moment");
        if (res.statusCode() != 200) throw new IOException("Spotify error " + res.statusCode());

        JsonObject root = JsonParser.parseString(res.body()).getAsJsonObject();
        JsonElement itemEl = root.get("item");
        if (itemEl == null || itemEl.isJsonNull()) return Optional.empty();
        JsonObject item = itemEl.getAsJsonObject();
        boolean playing = root.has("is_playing") && root.get("is_playing").getAsBoolean();

        String song = str(item, "name");
        List<String> names = new ArrayList<>();
        String album = "";
        String cover = "";

        if (item.has("artists")) { // music track
            JsonArray artists = item.getAsJsonArray("artists");
            for (JsonElement a : artists) names.add(str(a.getAsJsonObject(), "name"));
            if (item.has("album")) {
                JsonObject albumObj = item.getAsJsonObject("album");
                album = str(albumObj, "name");
                cover = smallestImageAtLeast(albumObj, 128);
            }
        } else if (item.has("show")) { // podcast episode
            JsonObject show = item.getAsJsonObject("show");
            names.add(str(show, "name"));
            album = names.getFirst();
            cover = smallestImageAtLeast(show, 128);
        }
        // "timestamp" = when the playback state last changed, so while paused it's when the pause started
        long changedAt = root.has("timestamp") ? root.get("timestamp").getAsLong() : 0;
        return Optional.of(new Track(song, names, album, playing, playing ? 0 : changedAt, cover));
    }

    /** Spotify lists cover sizes 640/300/64; the overlay only needs a small one. */
    private static String smallestImageAtLeast(JsonObject o, int minSize) {
        if (!o.has("images") || !o.get("images").isJsonArray()) return "";
        String best = "";
        int bestSize = Integer.MAX_VALUE;
        for (JsonElement el : o.getAsJsonArray("images")) {
            JsonObject img = el.getAsJsonObject();
            int size = img.has("width") && !img.get("width").isJsonNull() ? img.get("width").getAsInt() : 0;
            if (best.isEmpty() || (size >= minSize && size < bestSize)) {
                best = str(img, "url");
                bestSize = size >= minSize ? size : Integer.MAX_VALUE;
            }
        }
        return best;
    }

    // -------------------------------------------------------------- helpers

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private String randomString(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return base64Url(b);
    }

    private static String base64Url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null) return map;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i < 0) continue;
            map.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
        }
        return map;
    }
}
