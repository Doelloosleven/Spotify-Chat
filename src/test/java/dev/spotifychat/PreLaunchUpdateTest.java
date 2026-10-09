package dev.spotifychat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreLaunchUpdateTest {
    private static final String URL = "https://github.com/Doelloosleven/Spotify-Chat/releases/download/";

    private final KeyPair keys = newKeys();
    private final String publicKey = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
    /** Pretend GitHub: url -> file */
    private final Map<String, byte[]> files = new HashMap<>();
    private String latest = "1.5.2";

    private final PreLaunchUpdate.Web web = new PreLaunchUpdate.Web() {
        @Override
        public String latestVersion() {
            return latest;
        }

        @Override
        public byte[] get(String url, int max) {
            return files.get(url);
        }
    };

    private static KeyPair newKeys() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] sign(byte[] data) throws Exception {
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(keys.getPrivate());
        s.update(data);
        return s.sign();
    }

    private static byte[] modJar(String version, String minecraft) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(("{\"id\": \"spotifychat\", \"version\": \"" + version + "\", \"depends\": {\"minecraft\": \"~"
                    + minecraft + "\"}}").getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    /** Publishes a release jar (signed with the test key unless signature is given) */
    private byte[] publish(String version, String minecraft, byte[] jar, byte[] signature) throws Exception {
        String name = "spotify-chat-" + version + "+" + minecraft + ".jar";
        files.put(URL + "v" + version + "/" + name, jar);
        files.put(URL + "v" + version + "/" + name + ".sig", signature != null ? signature : sign(jar));
        return jar;
    }

    private static List<String> names(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void installsTheNewJarForTheSameMinecraft(@TempDir Path mods) throws Exception {
        Files.writeString(mods.resolve("spotify-chat-1.5.1+26.2.jar"), "old");
        Files.writeString(mods.resolve("fabric-api.jar"), "other mod");
        publish("1.5.2", "26.3", modJar("1.5.2", "26.3"), null);
        byte[] jar = publish("1.5.2", "26.2", modJar("1.5.2", "26.2"), null);

        assertEquals("updated 1.5.1 -> 1.5.2", PreLaunchUpdate.run(mods, publicKey, web));
        assertEquals(List.of("fabric-api.jar", "spotify-chat-1.5.2+26.2.jar"), names(mods));
        assertArrayEquals(jar, Files.readAllBytes(mods.resolve("spotify-chat-1.5.2+26.2.jar")));
    }

    @Test
    void upToDateOrNoGitHubChangesNothing(@TempDir Path mods) throws Exception {
        Files.writeString(mods.resolve("spotify-chat-1.5.2+26.2.jar"), "current");
        assertEquals("1.5.2 is up to date", PreLaunchUpdate.run(mods, publicKey, web));
        latest = null;
        assertTrue(PreLaunchUpdate.run(mods, publicKey, web).startsWith("couldn't reach GitHub"));
        assertEquals(List.of("spotify-chat-1.5.2+26.2.jar"), names(mods));
    }

    @Test
    void refusesWhatIsntSignedOrIsForAnotherMinecraft(@TempDir Path mods) throws Exception {
        Path old = Files.writeString(mods.resolve("spotify-chat-1.5.1+26.2.jar"), "old");
        byte[] jar = modJar("1.5.2", "26.2");
        publish("1.5.2", "26.2", jar, sign("something else".getBytes()));
        assertThrows(IllegalStateException.class, () -> PreLaunchUpdate.run(mods, publicKey, web));

        publish("1.5.2", "26.2", modJar("1.5.2", "26.3"), null); // signed, but built for 26.3
        assertThrows(IllegalStateException.class, () -> PreLaunchUpdate.run(mods, publicKey, web));

        publish("1.5.2", "26.2", modJar("1.5.1", "26.2"), null); // an old jar under the new name
        assertThrows(IllegalStateException.class, () -> PreLaunchUpdate.run(mods, publicKey, web));

        assertEquals(List.of("spotify-chat-1.5.1+26.2.jar"), names(mods));
        assertEquals("old", Files.readString(old));
    }

    @Test
    void leavesOddSetupsAlone(@TempDir Path mods) throws Exception {
        assertEquals("not installed here", PreLaunchUpdate.run(mods, publicKey, web));
        Files.writeString(mods.resolve("spotify-chat-1.5.0+26.2.jar"), "a");
        Files.writeString(mods.resolve("spotify-chat-1.5.1+26.2.jar"), "b");
        assertEquals("more than one jar in mods, left alone", PreLaunchUpdate.run(mods, publicKey, web));
        Files.delete(mods.resolve("spotify-chat-1.5.0+26.2.jar"));
        assertTrue(PreLaunchUpdate.run(mods, publicKey, web).startsWith("1.5.2 has no signature for Minecraft 26.2"));
    }

    @Test
    void versionsCompareAsNumbers() {
        assertTrue(PreLaunchUpdate.compare("1.5.10", "1.5.9") > 0);
        assertTrue(PreLaunchUpdate.compare("1.6", "1.5.9") > 0);
        assertEquals(0, PreLaunchUpdate.compare("1.5", "1.5.0"));
        assertTrue(PreLaunchUpdate.compare("1.4.0", "1.5.0") < 0);
    }

    @Test
    void readsTheLatestTagFromGitHubsRedirect() {
        var m = PreLaunchUpdate.TAG_URL.matcher("/Doelloosleven/Spotify-Chat/releases/tag/v1.5.1");
        assertTrue(m.matches());
        assertEquals("1.5.1", m.group(1));
        assertTrue(!PreLaunchUpdate.TAG_URL.matcher("/Doelloosleven/Spotify-Chat/releases").matches());
        assertTrue(!PreLaunchUpdate.JAR.matcher("spotify-chat-1.5.1+26.2.jar.old").matches());
        assertTrue(!PreLaunchUpdate.JAR.matcher("spotify-chat-1.5.1.jar").matches());
    }
}
