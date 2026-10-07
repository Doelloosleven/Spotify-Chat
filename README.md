# Spotify Chat (Fabric, Minecraft 26.2)

Type `!spotify` in chat and the mod posts what you're listening to:

> ♫ Now playing: Song Name - Artist

No login or Spotify developer setup needed: the song is read straight from the **Spotify desktop app** on the same PC (Free or Premium). Your `!spotify` message shows up in chat too, followed by the song (in private mode it stays hidden). It's a client-only mod, so it works on any server.

## Install

1. Install **Fabric Loader** for 26.2 and put **Fabric API** for 26.2 in `.minecraft/mods`.
2. Download `spotify-chat-<version>.jar` from [Releases](../../releases) and put it in `.minecraft/mods`.
3. Open the Spotify app, play a song, type `!spotify`.

## Commands

| Command | What it does |
|---|---|
| `!spotify` | Share the current song in the chat you're in |
| `/gc !spotify` / `/pc !spotify` | Share it in guild / party chat (Hypixel) |
| someone else says `!spotify` in guild or party chat | Your song is sent back to that same channel (max once per 10 s) |
| `/spotify` | Open the settings menu |
| `/spotify private` | Only you see the song |
| `/spotify public` | Everyone sees it (default) |
| `/spotify help` | Show the commands |

## Settings menu

`/spotify` (or the config button in **Mod Menu**, if installed) opens a menu in Spotify colors with three tabs:

- **General**: mod on/off, public/private, share paused songs, share "not listening", show your own `!spotify`, use phone/web player login, and after how long paused a song counts as "not listening" (right away, 1-30 min, or never; default 3 min)
- **Guild & Party**: `/gc !spotify`, `/pc !spotify`, answer guild members, answer party members, answer cooldown (0-60 s)
- **Message**: switches for **Song**, **Artist**, **Featured artists** and **Album** (all on by default), the text before the song, and the "not listening" message, with a live preview

Example with everything on:

> ♫ Now playing: TIJDSGEEST - Abel, Sef, IJSLAND - IJSLAND 2

The desktop app's window title only has the song and the main artist, so the featured artists and album are looked up in Deezer's free public music search (no account needed) as soon as a new song starts. If the song can't be found, they're left out. With the phone/web login, Spotify's own info is used.

Everything is saved to `config/spotifychat.json`.

## How the song is found

- **Windows**: the Spotify window title (`Artist - Song`) while a song plays.
- **macOS**: asks the Spotify app via AppleScript.
- **Linux**: `playerctl` (must be installed).

Paused songs are shared with "(paused)". On Windows the paused title doesn't show the song, so the mod checks every 2 seconds and remembers the last song; if Spotify was opened already paused, play a song for a moment first.

## Optional: share from your phone / web player

The desktop app method only sees music playing on this PC. To also share what plays on your phone or in the web player, you can connect through Spotify's Web API. This needs a free developer app, and Spotify requires the **app owner to have Premium**.

1. Go to https://developer.spotify.com/dashboard, click **Create app**.
   - Redirect URI: `http://127.0.0.1:8888/callback` (exactly this)
   - Tick **Web API**
2. Copy the **Client ID** from the app's Settings.
3. In game: `/spotify id <Client ID>` then `/spotify login`.

The login is stored in `.minecraft/config/spotifychat.json`; don't share that file. The desktop app is always checked first; the Web API is used when nothing plays there.

## Building

Needs **JDK 25**. Run `gradlew.bat build`; the jar ends up in `build/libs/`.

## Troubleshooting

- **"Nothing is playing in the Spotify app"**: open the Spotify desktop app (not the web player) and press play.
- **Download of the jar fails (ERR_CONNECTION_RESET)**: antivirus/browser blocking `.jar` from Discord, share the `.zip` instead.
