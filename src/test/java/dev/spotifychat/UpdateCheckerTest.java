package dev.spotifychat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCheckerTest {
    /** A fresh key pair for each test; nothing is ever written to disk */
    private static KeyPair newKeyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static String publicKeyBase64(KeyPair pair) {
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()); // X.509, like the constant
    }

    private static byte[] sign(KeyPair pair, byte[] data) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(data);
        return signer.sign();
    }

    @Test
    void signatureMatchesOnlyTheSignedBytes() throws Exception {
        KeyPair pair = newKeyPair();
        String publicKey = publicKeyBase64(pair);
        byte[] jar = "pretend this is a release jar".getBytes(StandardCharsets.UTF_8);
        byte[] signature = sign(pair, jar);

        assertTrue(UpdateChecker.signatureValid(publicKey, new ByteArrayInputStream(jar), signature));

        byte[] changed = jar.clone();
        changed[5] ^= 1; // one byte different
        assertFalse(UpdateChecker.signatureValid(publicKey, new ByteArrayInputStream(changed), signature));
    }

    @Test
    void signatureFromAnotherKeyOrGarbageFails() throws Exception {
        KeyPair pair = newKeyPair();
        byte[] jar = "pretend this is a release jar".getBytes(StandardCharsets.UTF_8);
        byte[] signature = sign(newKeyPair(), jar);

        assertFalse(UpdateChecker.signatureValid(publicKeyBase64(pair), new ByteArrayInputStream(jar), signature));
        assertFalse(UpdateChecker.signatureValid(publicKeyBase64(pair), new ByteArrayInputStream(jar), new byte[64]));
    }

    @Test
    void onlyPlainSpotifyChatJarNames() {
        assertTrue(UpdateChecker.validJarName("spotify-chat-1.2.1+26.2.jar"));
        assertTrue(UpdateChecker.validJarName("spotify-chat-1.0.1.jar"));
        assertFalse(UpdateChecker.validJarName("../spotify-chat-1.2.1+26.2.jar"));
        assertFalse(UpdateChecker.validJarName("spotify-chat-1.2.1/../../evil.jar"));
        assertFalse(UpdateChecker.validJarName("spotify-chat-1.2.1+26.2.jar.sig"));
        assertFalse(UpdateChecker.validJarName("spotify-chat-1 2.jar"));
        assertFalse(UpdateChecker.validJarName("other-mod-1.0.jar"));
    }

    @Test
    void onlyHttpsGitHubDownloads() {
        assertTrue(UpdateChecker.trustedUrl("https://github.com/Doelloosleven/Spotify-Chat/releases/download/v1.2.1/a.jar"));
        assertTrue(UpdateChecker.trustedUrl("https://objects.githubusercontent.com/github-production-release-asset/a"));
        assertFalse(UpdateChecker.trustedUrl("http://github.com/a.jar"));
        assertFalse(UpdateChecker.trustedUrl("https://github.com.example.org/a.jar"));
        assertFalse(UpdateChecker.trustedUrl("https://example.org/a.jar"));
        assertFalse(UpdateChecker.trustedUrl("https://user@github.com/a.jar"));
        assertFalse(UpdateChecker.trustedUrl("https://github.com:8443/a.jar"));
        assertFalse(UpdateChecker.trustedUrl("not a url"));
    }

    /**
     * The swap script must treat folder names as plain text. These names broke out of the old script's
     * single quotes (PowerShell also ends a string at U+2019) or are wildcards to Move-Item.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void swapTakesPathsLiterally(@TempDir Path tmp) throws Exception {
        List<String> folders = List.of(
                "O’Brien",
                "x’; Write-Output INJECTED; ’y",
                "[1.21] mods $(Write-Output INJECTED)");
        for (String folder : folders) {
            Path dir = Files.createDirectory(tmp.resolve(folder));
            Path old = dir.resolve("spotify-chat-1.0.0+26.2.jar");
            Path pending = dir.resolve("spotify-chat-1.0.1+26.2.jar.pending");
            Path target = dir.resolve("spotify-chat-1.0.1+26.2.jar");
            Files.writeString(old, "old");
            Files.writeString(pending, "new");

            // Stands in for Minecraft: the swap waits until it has exited
            Process game = new ProcessBuilder("ping", "-n", "2", "127.0.0.1").start();
            Process swap = UpdateChecker.swapProcess(game.pid(), old, pending, target)
                    .redirectErrorStream(true).start();
            String output = new String(swap.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(swap.waitFor(60, TimeUnit.SECONDS), "swap didn't finish for " + folder);

            assertFalse(output.contains("INJECTED"), "folder name ran as code: " + folder + "\n" + output);
            assertEquals("new", Files.readString(target), "not swapped in " + folder + "\n" + output);
            assertFalse(Files.exists(old), folder);
            assertFalse(Files.exists(pending), folder);
            assertFalse(Files.exists(Path.of(old + ".old")), folder);
        }
    }
}
