# Discord bridge

Links the in-game IRC (`#spotifychat` on Rizon) to `#irc` on the [Spotify Chat Discord](https://discord.gg/v5GUAw9VkV), and runs the private guild IRC. Runs on a small Linux server as a systemd service.

- `bridge.py`: the bot (Python 3.12, `discord.py`)
- `spotify-chat-bridge.service`: systemd unit, reads `/etc/spotify-chat-bridge/bridge.env` and `token.env`
- `set-token.sh` / `set-token.ps1`: put the Discord bot token on the server without it showing up anywhere
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
GUILD_DISCORD_CHANNEL_ID=<channel for the guild IRC, optional>
GUILD_ROLES=<Discord roles that count as guild members, comma separated>
RELEASES_CHANNEL_ID=<channel where new GitHub releases are posted, optional>
NEW_PEOPLE_CHANNEL_ID=<welcome card for everyone who joins, optional>
NOW_PLAYING_CHANNEL_ID=<songs shared with !spotify in IRC, with album covers, optional>
MEMBER_ROLE_ID=<role every new member gets, optional>
MEMBER_COUNT_CHANNEL_ID=<channel renamed to "Members: N", optional>
```

Then `sudo spotify-chat-bridge-token` (paste the token) and `sudo systemctl enable --now spotify-chat-bridge`. The bot needs the Message Content and Server Members intents.

## Guild IRC

The bot makes a hidden, invite-only channel. When a player joins `#spotifychat`, the mod asks the bot to let them in. The bot checks that a Discord member with that Minecraft name has a guild role, then the mod proves the name with Mojang (the same check a server does when you join) and gets invited. The mod hashes the bot's code with a fixed prefix before giving it to Mojang, so the proof can't be used to log in anywhere else.
