<p align="center"><img src="docs/banner.png" alt="Spotify Chat"></p>

<h1 align="center">Spotify Chat</h1>

<p align="center">
  <a href="https://github.com/Doelloosleven/Spotify-Chat/releases/latest"><img src="https://img.shields.io/github/v/release/Doelloosleven/Spotify-Chat?style=for-the-badge&label=release&color=1ed760&labelColor=191414" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/Minecraft-26.1.2%20%7C%2026.2%20%7C%2026.3-1ed760?style=for-the-badge&labelColor=191414" alt="Minecraft 26.1.2, 26.2, 26.3">
  <img src="https://img.shields.io/badge/Fabric-client%20side-1ed760?style=for-the-badge&labelColor=191414" alt="Fabric, client side">
  <a href="https://github.com/Doelloosleven/Spotify-Chat/releases"><img src="https://img.shields.io/github/downloads/Doelloosleven/Spotify-Chat/total?style=for-the-badge&color=1ed760&labelColor=191414" alt="Downloads"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-1ed760?style=for-the-badge&labelColor=191414" alt="MIT license"></a>
</p>

<p align="center">
  <b>Type <code>!spotify</code> and everyone in chat sees what you're listening to.</b><br>
  No login, no Spotify developer account, no setup: the song comes straight from the Spotify app on your PC (Free or Premium).
</p>

> ♫ Now playing: TIJDSGEEST - Abel, Sef, IJSLAND - IJSLAND 2

## 📦 Install

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) and [Fabric API](https://modrinth.com/mod/fabric-api) for Minecraft **26.1.2**, **26.2** or **26.3**.
2. Download the jar for your Minecraft version from the [latest release](../../releases/latest) (`spotify-chat-<version>+26.2.jar`, `+26.1.2.jar` or `+26.3.jar`) and put it in `.minecraft/mods`. Remove an older Spotify Chat jar. The `.sig` files are for auto-update; you don't need to download them.
3. Open the Spotify desktop app, play a song and type `!spotify`.

[Mod Menu](https://modrinth.com/mod/modmenu) is optional. It's a client-only mod, so it works on any server.

## ✨ Features

- 🎵 **Share your song**: `!spotify` or `!music` in any chat, and `/gc`, `/pc` or `/cc !spotify` for Hypixel guild, party and SkyBlock co-op chat. Featured artists and the album are filled in automatically; a paused song says "(paused)".
- 🤝 **Answers your friends**: when someone types `!spotify` in guild, party or co-op chat, your song goes back to that same chat (at most once per 10 s, so you don't get muted).
- 🎉 **Spotify Jam**: copy your Jam link in Spotify and type `!jam`. Hypixel punishes links, so the link goes to the Spotify Chat IRC.
- 💬 **IRC chat** with everyone who has Spotify Chat, on any server: press <kbd>[</kbd> and type.
- 🖼️ **Song overlay** with the album cover, in the colors of the album, anywhere on your screen.
- ⏯️ **Media keys** for the Spotify app: <kbd>←</kbd> previous, <kbd>→</kbd> next, <kbd>↓</kbd> play/pause.
- ⚙️ **Settings menu** in Spotify colors (or Ocean, Sunset, Bubblegum, Grape, Cherry): <kbd>F4</kbd> or `/spotify`. Every feature has its own switch.
- 🔄 **Keeps itself up to date**: signed releases are installed when you close Minecraft (you can turn that off).

## ⌨️ Commands & keys

| Command / key | What it does |
|---|---|
| `!spotify` or `!music` | Share the current song in the chat you're in |
| `/gc !spotify` / `/pc !spotify` / `/cc !spotify` | Share it in guild / party / SkyBlock co-op chat (`!music` works too) |
| `!jam` (or `/gc`, `/pc`, `/cc !jam`) | Share your Spotify Jam link |
| <kbd>[</kbd> or `/irc <message>` | Write to the Spotify Chat IRC |
| <kbd>←</kbd> <kbd>→</kbd> <kbd>↓</kbd> or `/spotify previous` / `next` / `pause` | Previous song, next song, play/pause |
| <kbd>F4</kbd> or `/spotify` | Open the settings menu (F4 again closes it; F3+F4 is still Minecraft's) |
| `/spotify overlay` | Show/hide the song overlay |
| `/spotify jam` | Show your Jam link; `/spotify jam <link>` sets it, `/spotify jam clear` forgets it |
| `/spotify public` / `private` | Everyone sees the song (default) / only you do |
| `/spotify help` | All commands |

Someone else's `!spotify`, `!music` or `!jam` in guild, party or co-op chat is answered in that same chat. Every key can be changed in the menu (Keys & Updates) or in Options > Controls > Spotify Chat; Esc removes a key, and a key turns red if another control uses it too. Players from before 1.2.0 get the arrow keys once, unless they had set those keys or another control uses the arrows.

The media keys go straight to Spotify's window on Windows (AppleScript on macOS, `playerctl` on Linux), so they never pause another app. Like in Spotify itself, "previous" first jumps to the start of the song.

## 🎉 Spotify Jam

1. In Spotify, start a Jam and click **Invite** > **Copy link**.
2. Type `!jam` (or `/gc !jam`, `/pc !jam`, `/cc !jam`). The mod takes the link from your clipboard.

Servers like Hypixel punish links in chat ("Advertising is against the rules"), so the **link goes to the Spotify Chat IRC**, where other mod users can click it. Guild, party and co-op chat also get the note "♫ Join my Spotify Jam (link in the Spotify Chat IRC)"; in open chat that note is off by default. With IRC off, the link is posted in chat on servers that allow it, and never on Hypixel. The link is remembered until you close the game, so friends can ask with `!jam`. Only Spotify Jam links (`spotify.link/...` or `open.spotify.com/socialsession/...`) are ever read from the clipboard.

## 💬 IRC chat

- Press <kbd>[</kbd>: the chat opens with an **IRC** label, and what you send goes to IRC instead of the server. <kbd>T</kbd> still opens normal chat.
- Messages show up as `[IRC] Name: message`, with every name in pink and clickable links. Your own message appears once the server has accepted it; if it isn't delivered, you're told.
- One channel for everyone: `#spotifychat` on **Rizon** (`irc.rizon.net`), encrypted, and the mod checks it's really talking to Rizon. Rizon hides your IP; others see your Minecraft name.
- Anyone with an IRC app can join too and names aren't verified, so don't share private things there. Turn IRC off in the IRC tab and the mod doesn't connect at all.

## 🖼️ Overlay

A "now playing" card with the album cover, song, artists and album (or "Paused"). It hides when nothing plays.

- **Album colors** (on by default): a dark gradient of the cover's main colors, with its liveliest color for the bar and text, fading over when the song changes. Off: dark gray with your menu color.
- In the **Overlay** tab: on/off, cover, album name, show while paused, album colors, size (50-200%), **Move overlay** (drag it anywhere, scroll to resize), and the **menu color**.

## 🔄 Updates

- **Update notifications** (on by default): a chat message with a download link when a new version is on the [releases page](../../releases).
- **Auto-update** (on by default; turn it off under Keys & Updates): downloads the new version and installs it when you close Minecraft. Only jars signed with the Spotify Chat release key are installed; each jar has a `.sig` next to it, and without a matching signature nothing is installed and you just get the message. The download must start at github.com, GitHub's SHA-256 checksum is checked too, and the jar must be Spotify Chat for your Minecraft version. On Windows a small hidden PowerShell step swaps the files after Minecraft closes; if that can't finish, the old version is put back and the next start tries again.
- Players who had the mod before 1.2.1 get auto-update switched on once, with a chat message saying how to turn it off. After that the mod never changes it.

## ⚙️ Settings menu

<kbd>F4</kbd>, `/spotify` or the config button in **Mod Menu** opens the menu, with six tabs:

- **General**: mod on/off, public/private, share paused songs, share "not listening", show your own `!spotify`, phone/web player login, and after how long a paused song counts as "not listening" (right away, 1-30 min, or never; default 3 min)
- **Hypixel**: `/gc`, `/pc` and `/cc !spotify`, answer guild / party / co-op members, `!jam`, answer `!jam`, the Jam notes, answer cooldown (0-60 s), and the text before the Jam link
- **Message**: switches for **Song**, **Artist**, **Featured artists** and **Album**, the text before the song, and the "not listening" message, with a live preview
- **Overlay**, **IRC**, **Keys & Updates**: see above

Settings are saved to `config/spotifychat.json`; the optional Spotify login has its own file (see below).

## ❓ FAQ

<details>
<summary><b>How is the song found?</b></summary>

- **Windows**: the Spotify window title (`Artist - Song`) while a song plays. Paused titles don't show the song, so the mod checks every 2 seconds and remembers the last one; if Spotify was opened already paused, play a song for a moment first.
- **macOS**: asks the Spotify app via AppleScript.
- **Linux**: `playerctl` (must be installed).

The window title only has the song and main artist, so featured artists, album and cover are looked up in Deezer's free public music search (only the song and artist name are sent). If the song can't be found, they're left out.
</details>

<details>
<summary><b>It says nothing is playing</b></summary>

Open the Spotify **desktop app** (not the web player) and press play.
</details>

<details>
<summary><b>Can it show music from my phone or the web player?</b></summary>

Yes, through Spotify's Web API. This needs a free developer app, and Spotify requires the **app owner to have Premium**.

1. Go to https://developer.spotify.com/dashboard and click **Create app**. Redirect URI: `http://127.0.0.1:8888/callback` (exactly this); tick **Web API**.
2. Copy the **Client ID** from the app's Settings.
3. In game: `/spotify id <Client ID>`, then `/spotify login`.

The login is stored in `.minecraft/config/spotifychat-secrets.json`. **Never share that file**, for example in a bug report; `spotifychat.json` holds no login and is fine to share. The separate file only protects against sharing it by accident: any program running on your PC as you can still read it. The desktop app is always checked first.
</details>

<details>
<summary><b>What does the mod connect to?</b></summary>

- **Deezer's public music search**: album, featured artists and cover (song and artist name only).
- **IRC** in `#spotifychat` on Rizon, encrypted (can be turned off).
- **GitHub**: checking for and downloading updates.
- **Spotify Web API**: only if you log in yourself.
</details>

<details>
<summary><b>Downloading the jar from Discord fails (ERR_CONNECTION_RESET)</b></summary>

Antivirus or the browser blocks `.jar` files from Discord. Share the `.zip` from the release instead.
</details>

## 🛠️ Building

Needs **JDK 25**. The same code builds for every supported Minecraft version (things Minecraft moved between versions go through `Mc.java`). Jars end up in `build/libs/`, and `gradlew build` also runs the unit tests in `src/test`:

```
gradlew build                                                                                   # 26.2 (default)
gradlew build -Pminecraft_version=26.1.2 -Pfabric_version=0.155.3+26.1.2 -Pminecraft_dep=~26.1.2
gradlew build -Pminecraft_version=26.3 -Pfabric_version=0.162.0+26.3 -Pminecraft_dep=~26.3
```

**Releases** must be signed, or auto-update won't install them: `tools/sign_release.py` writes a `.sig` next to each jar with the Ed25519 private key whose path is in `SPOTIFY_CHAT_SIGNING_KEY`. Upload every `.sig` together with its jar. The matching public key is `UpdateChecker.RELEASE_PUBLIC_KEY` (`python tools/sign_release.py --public-key` prints it). Never commit the private key.

## 📄 License

[MIT](LICENSE)
