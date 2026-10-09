package dev.spotifychat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Updates Spotify Chat right before Minecraft starts, for launchers that run a command first (Prism Launcher,
 * MultiMC: Settings > Custom commands > Pre-launch command, see tools/prelaunch-update.ps1). In game the jar is
 * locked, so the in-game updater can only swap it when the game closes; this runs before, so a new release is
 * used right away.
 *
 * Plain Java only (no Minecraft, Fabric or Gson), because it runs on its own from a copy of the installed jar:
 *   java -cp <copy of the installed jar> dev.spotifychat.PreLaunchUpdate <mods folder>
 * Takes the newest release's jar for the same Minecraft version as the installed one, checks its signature
 * and that it's that version of Spotify Chat, and swaps it in. Anything unexpected leaves the installed jar
 * alone; it never stops the game from starting.
 */
public final class PreLaunchUpdate {
    private PreLaunchUpdate() {}

    private static final String REPO = "https://github.com/Doelloosleven/Spotify-Chat";
    /** spotify-chat-<version>+<minecraft>.jar: the only kind of jar this updates */
    static final Pattern JAR = Pattern.compile("spotify-chat-(\\d+(?:\\.\\d+){1,3})\\+(\\d+(?:\\.\\d+){1,3})\\.jar");
    /** Where github.com/.../releases/latest sends you */
    static final Pattern TAG_URL = Pattern.compile(".*/releases/tag/v?(\\d+(?:\\.\\d+){1,3})");
    private static final int SIGNATURE_BYTES = 64;
    private static final int MAX_JAR_BYTES = 20 << 20;

    /** The network, so tests can stand in for GitHub */
    interface Web {
        /** The newest release's version, or null */
        String latestVersion() throws Exception;

        /** The file at url, at most max bytes; null if it isn't there */
        byte[] get(String url, int max) throws Exception;
    }

    public static void main(String[] args) {
        try {
            if (args.length != 1) throw new IllegalArgumentException("usage: PreLaunchUpdate <mods folder>");
            System.out.println("Spotify Chat: " + run(Path.of(args[0]), UpdateChecker.RELEASE_PUBLIC_KEY, github()));
        } catch (Exception e) {
            System.out.println("Spotify Chat: update skipped (" + e.getMessage() + ")");
        }
    }

    /** Updates the Spotify Chat jar in mods if there's a newer release; says what happened */
    static String run(Path mods, String publicKey, Web web) throws Exception {
        List<Path> jars = new ArrayList<>();
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(mods, "spotify-chat-*.jar")) {
            for (Path p : dir) if (JAR.matcher(p.getFileName().toString()).matches()) jars.add(p);
        }
        if (jars.size() != 1) return jars.isEmpty() ? "not installed here" : "more than one jar in mods, left alone";
        Path installed = jars.get(0);
        Matcher m = JAR.matcher(installed.getFileName().toString());
        if (!m.matches()) throw new IllegalStateException("odd jar name");
        String current = m.group(1), minecraft = m.group(2);

        String latest = web.latestVersion();
        if (latest == null) return "couldn't reach GitHub, keeping " + current;
        if (compare(latest, current) <= 0) return current + " is up to date";

        String name = "spotify-chat-" + latest + "+" + minecraft + ".jar";
        String url = REPO + "/releases/download/v" + latest + "/" + name;
        byte[] signature = web.get(url + ".sig", SIGNATURE_BYTES + 1);
        if (signature == null || signature.length != SIGNATURE_BYTES) {
            return latest + " has no signature for Minecraft " + minecraft + ", keeping " + current;
        }
        byte[] jar = web.get(url, MAX_JAR_BYTES);
        if (jar == null) return latest + " has no jar for Minecraft " + minecraft + ", keeping " + current;
        if (!signatureValid(publicKey, new ByteArrayInputStream(jar), signature)) {
            throw new IllegalStateException("the signature of " + name + " doesn't match");
        }
        String meta = modJson(jar);
        if (meta == null || !meta.matches("(?s).*\"id\"\\s*:\\s*\"spotifychat\".*")
                || !meta.matches("(?s).*\"version\"\\s*:\\s*\"" + Pattern.quote(latest) + "\".*")
                || !meta.matches("(?s).*\"minecraft\"\\s*:\\s*\"[~^>=]*" + Pattern.quote(minecraft) + "\".*")) {
            throw new IllegalStateException(name + " isn't Spotify Chat " + latest + " for Minecraft " + minecraft);
        }
        swap(installed, mods.resolve(name), jar);
        return "updated " + current + " -> " + latest;
    }

    /**
     * The new jar goes in as .pending first (Fabric skips that), the old one moves to .old, then the new one
     * takes its place. If that fails the old one is put back, so mods never has none or two.
     */
    static void swap(Path installed, Path target, byte[] jar) throws IOException {
        Path pending = target.resolveSibling(target.getFileName() + ".pending");
        Path backup = installed.resolveSibling(installed.getFileName() + ".old");
        Files.write(pending, jar);
        try {
            Files.deleteIfExists(backup);
            Files.move(installed, backup);
        } catch (IOException e) {
            Files.deleteIfExists(pending);
            throw e; // in use: the game must be running
        }
        try {
            Files.move(pending, target);
        } catch (IOException e) {
            Files.move(backup, installed);
            Files.deleteIfExists(pending);
            throw e;
        }
        Files.deleteIfExists(backup);
    }

    /** 1.5.10 > 1.5.9 > 1.5 */
    static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int c = Integer.compare(i < x.length ? Integer.parseInt(x[i]) : 0, i < y.length ? Integer.parseInt(y[i]) : 0);
            if (c != 0) return c;
        }
        return 0;
    }

    /** fabric.mod.json inside the jar, or null */
    private static String modJson(byte[] jar) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (e.getName().equals("fabric.mod.json")) {
                    return new String(zip.readNBytes(64 << 10), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
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

    private static Web github() {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return new Web() {
            @Override
            public String latestVersion() throws Exception {
                // github.com/.../releases/latest redirects to .../releases/tag/v<version>
                HttpResponse<Void> res = http.send(request(REPO + "/releases/latest")
                        .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.discarding());
                Matcher m = TAG_URL.matcher(res.uri().getPath());
                return res.statusCode() == 200 && m.matches() ? m.group(1) : null;
            }

            @Override
            public byte[] get(String url, int max) throws Exception {
                HttpResponse<InputStream> res = http.send(request(url).GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream in = res.body()) {
                    if (res.statusCode() != 200) return null;
                    byte[] data = in.readNBytes(max + 1);
                    if (data.length > max) throw new IllegalStateException("download too big");
                    return data;
                }
            }

            private HttpRequest.Builder request(String url) {
                return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                        .header("User-Agent", "SpotifyChat-PreLaunch");
            }
        };
    }
}
