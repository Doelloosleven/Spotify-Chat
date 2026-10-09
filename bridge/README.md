# Discord bridge

Links the in-game IRC (`#spotifychat` on Rizon) to `#irc` on the [Spotify Chat Discord](https://discord.gg/3pNGbfkVgJ), and runs a private IRC for every Hypixel guild. Runs on a small Linux server as a systemd service.

- `bridge.py`: the bot (Python 3.12, `discord.py`)
- `spotify-chat-bridge.service`: systemd unit, reads `/etc/spotify-chat-bridge/bridge.env`, `token.env` and `hypixel.env`
- `set-token.sh` / `set-token.ps1`: put the Discord bot token (or with `hypixel` / `-Hypixel` the Hypixel API key) on the server without it showing up anywhere
- `setup_server.py`: one-time channel layout for the Discord server
- `avatar.png`: the bot's picture

## Setup

```
sudo apt install python3-venv
sudo python3 -m venv /opt/spotify-chat-bridge/venv
sudo /opt/spotify-chat-bridge/venv/bin/pip install "discord.py>=2.4,<3"
sudo install -m 644 bridge.py /opt/spotify-chat-bridge/
sudo install -m 755 set-token.sh /usr/local/sbin/spotify-chat-bridge-token
sudo install -m 644 spotify-chat-bridge.service /etc/systemd/system/
```

`/etc/spotify-chat-bridge/bridge.env`:

```
DISCORD_CHANNEL_ID=<channel for the open IRC>
GUILD_DISCORD_CHANNEL_ID=<channel linked to one guild's IRC, optional>
GUILD_HYPIXEL_NAME=<the Hypixel guild of that channel>
GUILD_ROLES=<without a Hypixel API key: Discord roles that count as members of that guild, comma separated>
RELEASES_CHANNEL_ID=<channel where new GitHub releases are posted, optional>
NEW_PEOPLE_CHANNEL_ID=<welcome card for everyone who joins, optional>
NOW_PLAYING_CHANNEL_ID=<songs shared with !spotify in IRC, with album covers, optional>
MEMBER_ROLE_ID=<role every new member gets, optional>
MEMBER_COUNT_CHANNEL_ID=<channel renamed to "Members: N", optional>
```

Then `sudo spotify-chat-bridge-token` (paste the token) and `sudo systemctl enable --now spotify-chat-bridge`. The bot needs the Message Content and Server Members intents. For every guild to get its own IRC, add a Hypixel API key with `sudo spotify-chat-bridge-token hypixel`.

## Guild IRC

When a player joins `#spotifychat`, the mod asks the bot to let them into their guild's IRC. The bot looks up the player's Hypixel guild, the mod proves the name with Mojang (the same check a server does when you join), and the bot invites them into that guild's hidden, invite-only channel. The mod hashes the bot's code with a fixed prefix before giving it to Mojang, so the proof can't be used to log in anywhere else.

Each guild's channel is made when its first member comes online and closed once it's been empty for 10 minutes, with a new random name every time. Leaving the guild gets you kicked within the hour (guild member lists are cached that long, as Hypixel's API policy asks). One guild's channel can be linked to a Discord channel (`GUILD_DISCORD_CHANNEL_ID` + `GUILD_HYPIXEL_NAME`).

The API key stays on the server: the mod never sees it. Without a key the bot goes by the guild name the mod read in game (`/g online`, mod 1.5.0+). A modded client could claim any guild that way, so the linked guild, whose channel goes to Discord, also needs a Discord member with that Minecraft name and one of `GUILD_ROLES`.
