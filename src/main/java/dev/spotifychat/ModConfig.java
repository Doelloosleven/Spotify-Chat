package dev.spotifychat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Saved to .minecraft/config/spotifychat.json. Everything here can be changed in the menu (/spotify). */
public class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("spotifychat.json");

    // ---- General
    /** Master switch: off = the mod ignores every !spotify */
    public boolean enabled = true;
    /** true = everyone sees the song, false = only you see it */
    public boolean publicMessages = true;
    /** Also send your own "!spotify" message to chat before the song */
    public boolean showTrigger = true;
    /** Share the last song (marked "paused") when Spotify is paused */
    public boolean sharePaused = true;
    /**
     * After this many minutes paused, the song counts as "not listening" instead of "paused".
     * 0 = right away, PAUSED_NEVER = stays "paused" forever.
     */
    public int pausedToNothingMinutes = 3;
    public static final int PAUSED_NEVER = 31;
    /** Share notPlayingFormat when nothing is playing at all */
    public boolean shareNothing = true;
    /** Use the Web API login (phone / web player) when the desktop app has nothing */
    public boolean useWebApi = true;
    /** Menu color preset, by name (see SpotifyUi.THEMES) */
    public String menuTheme = "Spotify";

    // ---- Hypixel guild, party & SkyBlock co-op ("!music" works everywhere "!spotify" does)
    /** "/gc !spotify" shares your song in guild chat */
    public boolean guildChat = true;
    /** "/pc !spotify" shares your song in party chat */
    public boolean partyChat = true;
    /** "/cc !spotify" shares your song in SkyBlock co-op chat */
    public boolean coopChat = true;
    /** Someone else's "!spotify" in guild chat gets your song back in guild chat */
    public boolean answerGuild = true;
    /** Someone else's "!spotify" in party chat gets your song back in party chat */
    public boolean answerParty = true;
    /** Someone else's "!spotify" in co-op chat gets your song back in co-op chat */
    public boolean answerCoop = true;
    /** Minimum seconds between answers to other players (spam protection) */
    public int answerCooldownSeconds = 10;

    // ---- Spotify Jam
    /** "!jam", "/gc !jam", "/pc !jam", "/cc !jam" share your Jam invite link (copied in Spotify) */
    public boolean jamEnabled = true;
    /** Someone else's "!jam" in guild, party or co-op chat gets your Jam link back (only after you shared one) */
    public boolean answerJam = true;
    /** Text before the Jam link */
    public String jamPrefix = "♫ Join my Spotify Jam:";
    /**
     * With IRC on, the link goes to IRC; these send a note without the link to the server chat too.
     * jamNoteGuildParty covers co-op chat as well (the name is kept so older settings still load).
     */
    public boolean jamNoteGuildParty = true;
    public boolean jamNoteAllChat = false;

    // ---- IRC chat (between Spotify Chat users in #spotifychat on Rizon, outside the Minecraft server)
    public boolean ircEnabled = true;
    /** Join your guild's private IRC channel when the bridge bot confirms you're in the guild */
    public boolean guildIrcEnabled = true;
    /** Your Hypixel guild from the last /g online or /g list: null = not seen yet, "" = not in a guild */
    public String hypixelGuild = null;

    // ---- Updates
    /** Chat message when a newer version is on GitHub */
    public boolean updateNotify = true;
    /** Download new versions automatically (only signed releases); they're installed when Minecraft closes */
    public boolean autoUpdate = true;

    // ---- Overlay
    public boolean overlayEnabled = true;
    public boolean overlayShowCover = true;
    public boolean overlayShowAlbum = true;
    public boolean overlayShowWhenPaused = true;
    /** Background and accent color taken from the album cover */
    public boolean overlayAlbumColors = true;
    /** Top-left corner as a fraction of the screen size, so it stays put when the window is resized */
    public double overlayX = 0.01;
    public double overlayY = 0.30;
    /** Size in percent */
    public int overlayScale = 100;

    // ---- Message
    /** Text before the song: "♫ Now playing: Song - Artist, Feat - Album" */
    public String prefix = "♫ Now playing:";
    public boolean showSong = true;
    public boolean showArtist = true;
    /** Also list featured artists after the main artist (only when the artist is shown) */
    public boolean showFeatures = true;
    public boolean showAlbum = true;
    /** Between song, artist and album */
    public String separator = " - ";
    public String pausedSuffix = " (paused)";
    public String notPlayingFormat = "♫ Not listening to anything right now";

    // ---- Changed defaults
    /** Which round of new default keys this player already got (see SpotifyChatClient.applyNewKeyDefaults) */
    public int keyDefaultsVersion = 0;
    /** Which round of changed defaults this config already got (see applyNewDefaults) */
    public int defaultsVersion = 0;
    private static final int DEFAULTS_VERSION = 1;

    /**
     * Settings files store every option, so a changed default never reaches players who already had the mod.
     * Once per round: 1 (1.2.1) = auto-update on for everyone. Returns true if auto-update was switched on,
     * so the player can be told (they can turn it off again).
     */
    boolean applyNewDefaults() {
        if (defaultsVersion >= DEFAULTS_VERSION) return false;
        boolean switchedOn = !autoUpdate;
        autoUpdate = true;
        defaultsVersion = DEFAULTS_VERSION;
        save();
        return switchedOn;
    }

    // ---- Web API login (optional)
    /** Client ID from your app at developer.spotify.com/dashboard */
    public String clientId = "";
    /**
     * Only read from settings files of older versions, so SpotifyClient can move the login to
     * spotifychat-secrets.json. Null is left out when saving.
     */
    public String refreshToken = null;

    public static ModConfig load() {
        try {
            if (Files.exists(PATH)) {
                ModConfig cfg = GSON.fromJson(Files.readString(PATH), ModConfig.class);
                if (cfg != null) {
                    cfg.save(); // writes options added in newer versions
                    return cfg;
                }
            }
        } catch (Exception e) {
            SpotifyChatClient.LOGGER.warn("Could not read {}, using defaults", PATH, e);
        }
        ModConfig cfg = new ModConfig();
        cfg.defaultsVersion = DEFAULTS_VERSION; // a new player already has the current defaults
        cfg.save();
        return cfg;
    }

    public void save() {
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(this));
        } catch (IOException e) {
            SpotifyChatClient.LOGGER.error("Could not save {}", PATH, e);
        }
    }
}
