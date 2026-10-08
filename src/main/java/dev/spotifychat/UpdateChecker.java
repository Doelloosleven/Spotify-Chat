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
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Looks for a newer Spotify Chat on the GitHub releases page once per game start.
 * Notify: a chat message with a download link. Auto-update (off by default): downloads the new jar,
 * checks its Ed25519 signature and that it's really Spotify Chat for this Minecraft version, and swaps it
 * in when the game closes (Windows keeps the running jar locked, so a small hidden PowerShell step waits
 * for Minecraft to exit).
 */
public final class UpdateChecker {
    private UpdateChecker() {}

    private static final String LATEST = "https://api.github.com/repos/Doelloosleven/Spotify-Chat/releases/latest";
    private static final String MOD_ID = "spotifychat";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * Ed25519 public key (base64 X.509) that release jars are signed with; see tools/sign_release.py.
     * GitHub's checksum comes from the same API answer as the download, so it can't prove who published a
     * file; this key can. While it's still the placeholder, auto-update refuses to install anything.
     */
    static final String RELEASE_PUBLIC_KEY = "MCowBQYDK2VwAyEAegFtW6/DjygPIv2CYnMRzQ9q/y0dj9V6l60vAffaokM=";
    private static final String KEY_PLACEHOLDER = "REPLACE_WITH_BASE64_X509_ED25519_PUBLIC_KEY";

    /** Asset names end up in a file path in the mods folder, so only plain jar names are accepted */
    private static final Pattern JAR_NAME = Pattern.compile("^spotify-chat-[\\w.+-]+\\.jar$");
    /** Downloads must start at GitHub itself (it then redirects to its own file servers) */
    private static final Set<String> TRUSTED_HOSTS = Set.of("github.com", "objects.githubusercontent.com");
    /** An Ed25519 signature is always 64 bytes */
    private static final int SIGNATURE_BYTES = 64;

    public record Release(String version, String pageUrl, String jarName, String jarUrl, String sha256,
                          String sigUrl) {}

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
            if (!validJarName(name)) continue;
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
        // The signature is published next to the jar as <jar name>.sig
        String sigUrl = "";
        for (JsonElement el : json.getAsJsonArray("assets")) {
            JsonObject a = el.getAsJsonObject();
            if (!jarName.isEmpty() && str(a, "name").equals(jarName + ".sig")) sigUrl = str(a, "browser_download_url");
        }
        latest = new Release(tag, str(json, "html_url"), jarName, jarUrl, sha, sigUrl);
        state = State.AVAILABLE;
        SpotifyChatClient.LOGGER.info("Spotify Chat {} is available (you have {})", tag, currentVersion());

        if (cfg.autoUpdate && !jarUrl.isBlank()) {
            try {
                install(latest);
                state = State.INSTALLED_ON_RESTART;
            } catch (Exception e) {
                failReason = e.getMessage() == null ? e.toString() : e.getMessage();
                state = State.FAILED;
                SpotifyChatClient.LOGGER.warn("Auto-update refused or failed, nothing was installed: {}", failReason);
            }
        }
    }

    /** True while RELEASE_PUBLIC_KEY is a real key */
    static boolean signingConfigured() {
        return !RELEASE_PUBLIC_KEY.equals(KEY_PLACEHOLDER);
    }

    static boolean validJarName(String name) {
        return JAR_NAME.matcher(name).matches();
    }

    /** https on github.com or objects.githubusercontent.com, nothing else */
    static boolean trustedUrl(String url) {
        try {
            URI u = new URI(url);
            return "https".equalsIgnoreCase(u.getScheme()) && u.getRawUserInfo() == null
                    && (u.getPort() == -1 || u.getPort() == 443)
                    && u.getHost() != null && TRUSTED_HOSTS.contains(u.getHost().toLowerCase(Locale.ROOT));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static void install(Release r) throws Exception {
        if (!signingConfigured()) {
            throw new IllegalStateException("this build has no release signing key yet, so it can't check who "
                    + "published the download");
        }
        if (!validJarName(r.jarName()) || !trustedUrl(r.jarUrl())) {
            throw new IllegalStateException("the download isn't a Spotify Chat jar on github.com");
        }
        if (r.sigUrl().isEmpty() || !trustedUrl(r.sigUrl())) {
            throw new IllegalStateException("the release has no signature (" + r.jarName() + ".sig)");
        }
        Path current = currentJar().orElseThrow(() -> new IllegalStateException("not running from a mod jar"));
        Path mods = current.getParent();
        Path target = mods.resolve(r.jarName());
        if (Files.exists(target)) throw new IllegalStateException(r.jarName() + " is already in the mods folder");
        // Fabric ignores files that don't end in .jar, so the download can wait here safely
        Path pending = mods.resolve(r.jarName() + ".pending");
        Files.deleteIfExists(pending); // left over from an earlier try; the download doesn't overwrite it all

        byte[] signature = downloadSignature(r.sigUrl());
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
            verify(pending, r, signature);
        } catch (Exception e) {
            Files.deleteIfExists(pending);
            throw e;
        }

        if (Util.getPlatform() == Util.OS.WINDOWS) {
            swapProcess(ProcessHandle.current().pid(), current, pending, target)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } else {
            // macOS / Linux allow replacing a jar that's in use; the new one loads next start
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE);
            Files.delete(current);
        }
    }

    /** The .sig file: exactly 64 bytes, anything else counts as no signature */
    private static byte[] downloadSignature(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "SpotifyChat/" + currentVersion())
                .GET().build();
        HttpResponse<InputStream> res = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = res.body()) {
            if (res.statusCode() != 200) throw new IllegalStateException("the release has no signature file");
            byte[] sig = in.readNBytes(SIGNATURE_BYTES + 1);
            if (sig.length != SIGNATURE_BYTES) throw new IllegalStateException("the signature file is broken");
            return sig;
        }
    }

    /**
     * Signature (required), checksum (when GitHub lists one, as an extra), and that it's Spotify Chat in the
     * right version for this Minecraft.
     */
    private static void verify(Path jar, Release r, byte[] signature) throws Exception {
        try (InputStream in = Files.newInputStream(jar)) {
            if (!signatureValid(RELEASE_PUBLIC_KEY, in, signature)) {
                throw new IllegalStateException("the signature doesn't match, so this file may not come from "
                        + "Spotify Chat");
            }
        }
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

    /** True if signature is a valid Ed25519 signature over everything in data, made with the key's private half. */
    static boolean signatureValid(String publicKeyBase64, InputStream data, byte[] signature) throws Exception {
        PublicKey key = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        byte[] buf = new byte[8192];
        int n;
        while ((n = data.read(buf)) > 0) verifier.update(buf, 0, n);
        try {
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false; // malformed signature
        }
    }

    /**
     * Waits for the process to close, then swaps the jars. The old jar is first renamed to .old
     * (Fabric skips it), retrying while Windows still has it locked; if the new jar can't be put in place,
     * the old one is put back. So the mods folder never ends up with no Spotify Chat, or with two.
     * If the old jar stays locked, nothing changes and the next game start tries again.
     * The paths only reach PowerShell as environment variables, never as script text, so no folder name
     * (quotes of any kind, ; or $) can turn into code. .NET's File methods take them literally,
     * where Move-Item would treat [ ] in a destination as wildcards.
     */
    static final String SWAP_SCRIPT = "$p = Get-Process -Id $env:SC_PID -ErrorAction SilentlyContinue; "
            + "if ($p) { $p.WaitForExit() }; "
            + "$moved = $false; "
            + "for ($i = 0; $i -lt 30; $i++) { try { "
            + "if ([IO.File]::Exists($env:SC_BACKUP)) { [IO.File]::Delete($env:SC_BACKUP) }; "
            + "[IO.File]::Move($env:SC_OLD, $env:SC_BACKUP); $moved = $true; break "
            + "} catch { Start-Sleep -Seconds 1 } }; "
            + "if (-not $moved) { exit }; "
            + "try { [IO.File]::Move($env:SC_PENDING, $env:SC_TARGET); [IO.File]::Delete($env:SC_BACKUP) } "
            + "catch { [IO.File]::Move($env:SC_BACKUP, $env:SC_OLD) }";

    static ProcessBuilder swapProcess(long pid, Path oldJar, Path pending, Path target) {
        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-WindowStyle", "Hidden", "-Command", SWAP_SCRIPT);
        Map<String, String> env = pb.environment();
        env.put("SC_PID", Long.toString(pid));
        env.put("SC_OLD", oldJar.toAbsolutePath().toString());
        env.put("SC_BACKUP", oldJar.toAbsolutePath() + ".old");
        env.put("SC_PENDING", pending.toAbsolutePath().toString());
        env.put("SC_TARGET", target.toAbsolutePath().toString());
        return pb;
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
