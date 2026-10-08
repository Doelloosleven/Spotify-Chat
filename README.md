<p align="center"><img src="docs/banner.png" alt="Spotify Chat"></p>

# Spotify Chat (Fabric, Minecraft 26.1.2 / 26.2 / 26.3)

Type `!spotify` (or `!music`) in chat and the mod posts what you're listening to:

> ♫ Now playing: Song Name - Artist

No login or Spotify developer setup needed: the song is read straight from the **Spotify desktop app** on the same PC (Free or Premium). Your `!spotify` message shows up in chat too, followed by the song (in private mode it stays hidden). It's a client-only mod, so it works on any server.

## Install

1. Install **Fabric Loader** for your Minecraft version and put **Fabric API** for that version in `.minecraft/mods`.
2. Download the jar for your Minecraft version from [Releases](../../releases): `spotify-chat-<version>+26.1.2.jar`, `+26.2.jar` or `+26.3.jar`, and put it in `.minecraft/mods`.
3. Open the Spotify app, play a song, type `!spotify`.

## Commands

| Command | What it does |
|---|---|
| `!spotify` or `!music` | Share the current song in the chat you're in |
| `/gc !spotify` / `/pc !spotify` / `/cc !spotify` | Share it in guild / party / SkyBlock co-op chat (Hypixel); `!music` works there too |
| someone else says `!spotify` or `!music` in guild, party or co-op chat | Your song is sent back to that same channel (max once per 10 s) |
| `!jam` | Share your Spotify Jam invite link in the chat you're in |
| `/gc !jam` / `/pc !jam` / `/cc !jam` | Share your Jam link in guild / party / co-op chat |
| someone else says `!jam` in guild, party or co-op chat | Your Jam link is sent back (only after you shared it once this session) |
| `/spotify jam` | Show your Jam link; `/spotify jam <link>` sets it, `/spotify jam clear` forgets it |
| `/spotify overlay` | Show/hide the song overlay |
| **↓** / **→** / **←** | Play/pause, next song, previous song in the Spotify app (changeable) |
| `/spotify pause` / `next` / `previous` | The same, as commands |
| `/spotify` or **F4** | Open the settings menu (F4 again closes it) |
| `/spotify private` | Only you see the song |
| `/spotify public` | Everyone sees it (default) |
| `/spotify help` | Show the commands |

## Spotify Jam

1. In Spotify, start a Jam and click **Invite** > **Copy link**.
2. Type `!jam` (or `/gc !jam`, `/pc !jam`, `/cc !jam`). The mod takes the link from your clipboard.

Servers like Hypixel punish links in chat ("Advertising is against the rules"), so the **link goes to the Spotify Chat IRC** (see below), where other mod users can click it. In guild, party and co-op chat you also get the note "♫ Join my Spotify Jam (link in the Spotify Chat IRC)"; in open chat that note is off by default, so `!jam` there only goes to IRC. Both are switches in the Hypixel tab. With IRC off, the link is posted in chat on servers that allow it, and never on Hypixel.

The link is remembered until you close the game, so friends can type `!jam` in guild, party or co-op chat to get it again (in IRC). Only Spotify Jam links (`spotify.link/...` or `open.spotify.com/socialsession/...`) are ever read from the clipboard.

## IRC chat

Chat with other Spotify Chat users outside the Minecraft server.

- Press **[** (changeable under Keys & Updates): the chat opens with a green **IRC** label, and the message you send goes to IRC instead of the server. **T** still opens normal chat. (`!spotify`, `!jam` and commands work as usual from either.)
- `/irc <message>` does the same as a command.
- Messages from IRC show up in your chat as `[IRC] Name: message`, and links in them are clickable.

- Server: **Rizon** (`irc.rizon.net`), encrypted (TLS). Rizon hides your IP address, so other people only see your Minecraft name.
- There's one channel for everyone: `#spotifychat`. Every Spotify Chat user with IRC on is in it.
- Anyone with an IRC app can join it too, and names aren't verified, so don't share private things there.
- Turn it off in the IRC tab; then the mod doesn't connect at all.

## Overlay

A "now playing" card on your screen with the album cover, song, artists and album (or "Paused"). The bar on the left turns gray when paused. It hides when nothing plays, and after a song has been paused long enough to count as "not listening".

**Album colors** (on by default): the card takes its colors from the album cover. The cover is split into its main colors; the background is a dark gradient from its biggest color to its second one, and its liveliest color is used for the bar and to tint the text. The colors fade over when the song changes. Black-and-white covers stay black and white. With album colors off, the card is dark gray with your menu color.

In the menu's **Overlay** tab you can turn it on/off, hide the cover or album name, keep it hidden while paused, turn album colors on/off, change the size (50-200%), and **Move overlay**: drag it anywhere, scroll to resize.

The **menu color** is in the Overlay tab too: Spotify (green), Ocean, Sunset, Bubblegum, Grape or Cherry. It colors the settings menu, the buttons, the IRC label, and the overlay when album colors are off.

## Keys

In the menu's **Keys & Updates** tab (or Options > Controls > Spotify Chat) you can pick keys for:

- **Open/close Spotify Chat menu** (default **F4**; F3+F4 still opens Minecraft's game mode switcher)
- **Write to IRC chat** (default **[**)
- **Play / pause** (default **↓**), **Next song** (default **→**), **Previous song** (default **←**): control the Spotify desktop app without leaving the game
- **Show/hide song overlay** (no key by default)

Click a key, press the new one; Esc removes it. A key turns red if another control uses it too. If you had Spotify Chat before 1.2.0, the arrow keys are added once, unless you already picked keys for these or another control uses the arrow keys.

On Windows the command goes straight to Spotify's window, so it never pauses another app by accident. macOS uses AppleScript, Linux `playerctl`. Like in Spotify itself, "previous" first jumps to the start of the song.

## Updates

- **Update notifications** (on by default): a chat message with a download link when a new version is on the [GitHub releases page](https://github.com/Doelloosleven/Spotify-Chat/releases).
- **Auto-update** (off by default): downloads the new version for you and installs it when you close Minecraft. The download only comes from the official GitHub releases, is checked against GitHub's SHA-256 checksum, and must be Spotify Chat for your Minecraft version. Windows keeps a running mod locked, so a small hidden PowerShell step waits for Minecraft to close and then swaps the files. If the swap can't finish, the old version is put back and the next start tries again.

## Settings menu

`/spotify` or **F4** (or the config button in **Mod Menu**, if installed) opens a menu in Spotify colors (or another menu color) with six tabs:

- **General**: mod on/off, public/private, share paused songs, share "not listening", show your own `!spotify`, use phone/web player login, and after how long paused a song counts as "not listening" (right away, 1-30 min, or never; default 3 min)
- **Hypixel**: `/gc`, `/pc` and `/cc !spotify`, answer guild / party / co-op members, `!jam`, answer `!jam`, the Jam notes, answer cooldown (0-60 s), and the text before the Jam link
- **Message**: switches for **Song**, **Artist**, **Featured artists** and **Album** (all on by default), the text before the song, and the "not listening" message, with a live preview
- **Overlay**, **IRC**, **Keys & Updates**: see above

Example with everything on:

> ♫ Now playing: TIJDSGEEST - Abel, Sef, IJSLAND - IJSLAND 2

The desktop app's window title only has the song and the main artist, so the featured artists, album and album cover are looked up in Deezer's free public music search (no account needed) as soon as a new song starts. If the song can't be found, they're left out. With the phone/web login, Spotify's own info is used.

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

Needs **JDK 25**. The same code builds for every supported Minecraft version (things Minecraft moved between versions go through `Mc.java`). Jars end up in `build/libs/`:

```
gradlew build                                                                                   # 26.2 (default)
gradlew build -Pminecraft_version=26.1.2 -Pfabric_version=0.155.3+26.1.2 -Pminecraft_dep=~26.1.2
gradlew build -Pminecraft_version=26.3 -Pfabric_version=0.162.0+26.3 -Pminecraft_dep=~26.3
```

## Troubleshooting

- **"Nothing is playing in the Spotify app"**: open the Spotify desktop app (not the web player) and press play.
- **Download of the jar fails (ERR_CONNECTION_RESET)**: antivirus/browser blocking `.jar` from Discord, share the `.zip` instead.
