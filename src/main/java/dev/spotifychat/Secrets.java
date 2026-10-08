package dev.spotifychat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * The optional Spotify login lives in config/spotifychat-secrets.json instead of spotifychat.json, because
 * people paste spotifychat.json into bug reports. Only SpotifyClient (the login code) writes it.
 * This guards against sharing it by accident, not against other programs running as the same user.
 */
final class Secrets {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("spotifychat-secrets.json");

    /** Spotify Web API refresh token, "" = not logged in */
    String refreshToken = "";

    static Secrets load() {
        try {
            if (Files.exists(PATH)) {
                Secrets s = GSON.fromJson(Files.readString(PATH), Secrets.class);
                if (s != null && s.refreshToken != null) return s;
            }
        } catch (Exception e) {
            SpotifyChatClient.LOGGER.warn("Could not read {}, you may have to log in again", PATH.getFileName());
        }
        return new Secrets();
    }

    void save() {
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(this));
            try {
                Files.setPosixFilePermissions(PATH, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows: no POSIX permissions; the file stays in the user's own folder
            }
        } catch (Exception e) {
            SpotifyChatClient.LOGGER.error("Could not save {}", PATH.getFileName());
        }
    }
}
