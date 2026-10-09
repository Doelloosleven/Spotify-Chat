<p align="center"><img src="docs/banner.png" alt="Spotify Chat"></p>

<h1 align="center">Spotify Chat</h1>

<p align="center">
  <a href="https://github.com/Doelloosleven/Spotify-Chat/releases/latest"><img src="https://img.shields.io/github/v/release/Doelloosleven/Spotify-Chat?style=for-the-badge&label=release&color=1ed760&labelColor=191414" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/Minecraft-26.1.2%20%7C%2026.2%20%7C%2026.3-1ed760?style=for-the-badge&labelColor=191414" alt="Minecraft 26.1.2, 26.2, 26.3">
</p>

Fabric mod that shares what you're listening to on Spotify in Minecraft chat. Type `!spotify`:

> ♫ Now playing: TIJDSGEEST - Abel, Sef, IJSLAND - IJSLAND 2

No login needed, it reads the song from the Spotify desktop app. Client-side, works on any server.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) and [Fabric API](https://modrinth.com/mod/fabric-api).
2. Grab the jar for your Minecraft version from the [latest release](../../releases/latest) and put it in `.minecraft/mods`.
3. Open Spotify, play a song, type `!spotify`.

## Commands

| | |
|---|---|
| `!spotify` / `!music` | Share your song |
| `/gc`, `/pc`, `/cc !spotify` | Share it in guild, party or co-op chat (Hypixel) |
| `!jam` | Share your Spotify Jam link (copy it in Spotify first) |
| <kbd>[</kbd> or `/irc <msg>` | Chat in the Spotify Chat IRC (`!spotify` there shares your song in IRC) |
| <kbd>]</kbd> or `/girc <msg>` | Chat in your Hypixel guild's private IRC |
| <kbd>←</kbd> <kbd>→</kbd> <kbd>↓</kbd> | Previous / next / play-pause |
| <kbd>F4</kbd> or `/spotify` | Settings |
| `/spotify help` | All commands |

When a guild, party or co-op member types `!spotify`, your song gets sent back.

## Good to know

- **IRC** is `#spotifychat` on Rizon and is linked to `#irc` on the [Spotify Chat Discord](https://discord.gg/3pNGbfkVgJ). Jam links go there too, because Hypixel punishes links in chat.
- **Guild IRC**: a private IRC for every Hypixel guild. When you join Hypixel the mod checks your guild with a hidden `/g online`, confirms your Minecraft account with Mojang, and you're let into your guild's chat automatically.
- **Overlay** shows the song and album cover in the album's colors. Move it in the settings.
- **Auto-update** is on by default and only installs signed releases; the new version is used from the next start. Turn it off under Keys & Updates. On Prism Launcher or MultiMC it can update right before the game starts instead: save [tools/prelaunch-update.ps1](tools/prelaunch-update.ps1) somewhere and set Settings > Custom commands > Pre-launch command to `powershell -NoProfile -ExecutionPolicy Bypass -File "C:\path\to\prelaunch-update.ps1"`.
- **Phone / web player**: optional. Make an app at [developer.spotify.com](https://developer.spotify.com/dashboard) with redirect URI `http://127.0.0.1:8888/callback` (the app owner needs Premium), then `/spotify id <client id>` and `/spotify login`. The login is saved in `config/spotifychat-secrets.json`, don't share that file.

## Building

JDK 25. Jars go to `build/libs/`:

```
gradlew build                                                                                   # 26.2
gradlew build -Pminecraft_version=26.1.2 -Pfabric_version=0.155.3+26.1.2 -Pminecraft_dep=~26.1.2
gradlew build -Pminecraft_version=26.3 -Pfabric_version=0.162.0+26.3 -Pminecraft_dep=~26.3
```

Sign release jars with `tools/sign_release.py` and upload the `.sig` next to each jar, or auto-update skips them.

## License

[MIT](LICENSE). Not affiliated with or endorsed by Hypixel, Mojang or Spotify.
