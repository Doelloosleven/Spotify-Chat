package dev.spotifychat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import net.minecraft.util.Util;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Looks for a newer Spotify Chat on the GitHub releases page once per game start.
 * Notify: a chat message with a download link. Auto-update (off by default): downloads the new jar,
 * checks it's really Spotify Chat for this Minecraft version, and swaps it in when the game closes
 * (Windows keeps the running jar locked, so a small hidden PowerShell step waits for Minecraft to exit).
 */
public final class UpdateChecker {
    private UpdateChecker() {}

    private static final String LATEST = "https://api.github.com/repos/Doelloosleven/Spotify-Chat/releases/latest";
    private static final String MOD_ID = "spotifychat";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public record Release(String version, String pageUrl, String jarName, String jarUrl, String sha256) {}

    public enum State { NONE, AVAILABLE, INSTALLED_ON_RESTART, FAILED }

    private static volatile Release latest;
    private static volatile State state = State.NONE;
    private static volatile String failReason = "";

    public static Release latest() {
        return latest;
    }

    public static State state() {
        return state;
    }

    public static String failReason() {
        return failReason;
    }

    private static String minecraftVersion() {
        return FabricLoader.getInstance().getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }

    public static String currentVersion() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }

    /** Background check; does nothing if both notifications and auto-update are off. */
    public static void start(ModConfig cfg) {
        if (!cfg.updateNotify && !cfg.autoUpdate) return;
        CompletableFuture.runAsync(() -> {
            try {
                check(cfg);
            } catch (Exception e) {
                SpotifyChatClient.LOGGER.info("Update check failed: {}", e.toString());
            }
        });
    }

    private static void check(ModConfig cfg) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(LATEST))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "SpotifyChat/" + currentVersion())
                .GET().build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) return;
        JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();

        String tag = str(json, "tag_name").replaceFirst("^[vV]", "");
        if (tag.isBlank() || json.has("draft") && json.get("draft").getAsBoolean()
                || json.has("prerelease") && json.get("prerelease").getAsBoolean()) return;
        if (Version.parse(tag).compareTo(Version.parse(currentVersion())) <= 0) return; // up to date

        // Releases have one jar per Minecraft version: spotify-chat-<mod>+<minecraft>.jar.
        // Take the one for this Minecraft. On a hotfix like 26.2.1 the 26.2 jar is next best
        // (verify() still checks it accepts this exact version); a jar without "+..." (older releases) last.
        String mc = minecraftVersion();
        String mcBase = mc.replaceFirst("^(\\d+\\.\\d+)\\.\\d+$", "$1");
        String jarName = "", jarUrl = "", sha = "";
        int rank = 0; // 3 = exact, 2 = hotfix's base version, 1 = no "+" in the name
        for (JsonElement el : json.getAsJsonArray("assets")) {
            JsonObject a = el.getAsJsonObject();
            String name = str(a, "name");
            if (!name.startsWith("spotify-chat-")) continue;
            int r = name.endsWith("+" + mc + ".jar") ? 3
                    : name.endsWith("+" + mcBase + ".jar") ? 2
                    : name.matches("spotify-chat-[\\w.-]+\\.jar") ? 1 : 0;
            if (r > rank) {
                rank = r;
                jarName = name;
                jarUrl = str(a, "browser_download_url");
                String digest = str(a, "digest"); // "sha256:..." on newer GitHub releases
                sha = digest.startsWith("sha256:") ? digest.substring(7).toLowerCase(Locale.ROOT) : "";
            }
        }
        latest = new Release(tag, str(json, "html_url"), jarName, jarUrl, sha);
        state = State.AVAILABLE;
        SpotifyChatClient.LOGGER.info("Spotify Chat {} is available (you have {})", tag, currentVersion());

        if (cfg.autoUpdate && !jarUrl.isBlank()) {
            try {
                install(latest);
                state = State.INSTALLED_ON_RESTART;
            } catch (Exception e) {
                failReason = e.getMessage() == null ? e.toString() : e.getMessage();
                state = State.FAILED;
                SpotifyChatClient.LOGGER.warn("Auto-update failed", e);
            }
        }
    }

    private static void install(Release r) throws Exception {
        Path current = currentJar().orElseThrow(() -> new IllegalStateException("not running from a mod jar"));
        Path mods = current.getParent();
        Path target = mods.resolve(r.jarName());
        if (Files.exists(target)) throw new IllegalStateException(r.jarName() + " is already in the mods folder");
        // Fabric ignores files that don't end in .jar, so the download can wait here safely
        Path pending = mods.resolve(r.jarName() + ".pending");
        Files.deleteIfExists(pending); // left over from an earlier try; the download doesn't overwrite it all

        HttpRequest req = HttpRequest.newBuilder(URI.create(r.jarUrl()))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "SpotifyChat/" + currentVersion())
                .GET().build();
        HttpResponse<Path> res = HTTP.send(req, HttpResponse.BodyHandlers.ofFile(pending));
        if (res.statusCode() != 200) {
            Files.deleteIfExists(pending);
            throw new IllegalStateException("download failed (" + res.statusCode() + ")");
        }
        try {
            verify(pending, r);
        } catch (Exception e) {
            Files.deleteIfExists(pending);
            throw e;
        }

        if (Util.getPlatform() == Util.OS.WINDOWS) {
            swapAfterExit(current, pending, target);
        } else {
            // macOS / Linux allow replacing a jar that's in use; the new one loads next start
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE);
            Files.delete(current);
        }
    }

    /** Checksum (when GitHub lists one), and that it's Spotify Chat in the right version for this Minecraft. */
    private static void verify(Path jar, Release r) throws Exception {
        if (!r.sha256().isEmpty()) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(jar)) {
                in.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), md));
            }
            String actual = HexFormat.of().formatHex(md.digest());
            if (!actual.equals(r.sha256())) throw new IllegalStateException("checksum doesn't match");
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry == null) throw new IllegalStateException("not a Fabric mod");
            JsonObject meta;
            try (InputStream in = zip.getInputStream(entry)) {
                meta = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            }
            if (!MOD_ID.equals(str(meta, "id"))) throw new IllegalStateException("not Spotify Chat");
            if (!r.version().equals(str(meta, "version"))) throw new IllegalStateException("unexpected version");
            JsonObject depends = meta.has("depends") ? meta.getAsJsonObject("depends") : new JsonObject();
            String mc = str(depends, "minecraft");
            Version mcVersion = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                    .getMetadata().getVersion();
            if (!mc.isBlank() && !VersionPredicate.parse(mc).test(mcVersion)) {
                throw new IllegalStateException("made for Minecraft " + mc + ", you have " + mcVersion);
            }
        }
    }

    /**
     * Waits for this Minecraft to close, then swaps the jars. The old jar is first renamed to .old
     * (Fabric skips it), retrying while Windows still has it locked; if the new jar can't be put in place,
     * the old one is put back. So the mods folder never ends up with no Spotify Chat, or with two.
     * If the old jar stays locked, nothing changes and the next game start tries again.
     */
    private static void swapAfterExit(Path oldJar, Path pending, Path target) throws Exception {
        long pid = ProcessHandle.current().pid();
        String old = ps(oldJar), backup = ps(oldJar) + ".old";
        String script = "$p = Get-Process -Id " + pid + " -ErrorAction SilentlyContinue; "
                + "if ($p) { $p.WaitForExit() }; "
                + "for ($i = 0; $i -lt 30; $i++) { "
                + "try { Move-Item -LiteralPath '" + old + "' -Destination '" + backup + "' -Force -ErrorAction Stop; "
                + "break } catch { Start-Sleep -Seconds 1 } }; "
                + "if (Test-Path -LiteralPath '" + old + "') { exit }; "
                + "try { Move-Item -LiteralPath '" + ps(pending) + "' -Destination '" + ps(target)
                + "' -Force -ErrorAction Stop; Remove-Item -LiteralPath '" + backup + "' -Force } "
                + "catch { Move-Item -LiteralPath '" + backup + "' -Destination '" + old + "' -Force }";
        new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                "-Command", script)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    /** Single-quoted PowerShell string content */
    private static String ps(Path p) {
        return p.toAbsolutePath().toString().replace("'", "''");
    }

    /** The jar this mod was loaded from, if it's a normal jar in a folder (not a dev build). */
    private static Optional<Path> currentJar() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(ModContainer::getOrigin)
                .filter(o -> o.getKind() == ModOrigin.Kind.PATH)
                .flatMap(o -> o.getPaths().stream()
                        .filter(p -> p.toString().endsWith(".jar") && Files.isRegularFile(p))
                        .findFirst());
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }
}
