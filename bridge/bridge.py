#!/usr/bin/env python3
"""Relays Spotify Chat's IRC to Discord and back, and runs the private guild IRC channel.

Open IRC: #spotifychat on Rizon <-> DISCORD_CHANNEL_ID.
Guild IRC: a hidden, invite-only channel <-> GUILD_DISCORD_CHANNEL_ID. Only players who are in the guild get
in: the mod says its Minecraft name, the bot checks that a Discord member with that name has one of
GUILD_ROLES (the guild's own bot hands those out to real guild members), then asks the mod to prove the
name with Mojang (the same check Minecraft servers do) and invites it. Losing the role gets you kicked.

Settings come from the environment (see spotify-chat-bridge.service):
  DISCORD_TOKEN             bot token (keep it in /etc/spotify-chat-bridge/token.env, never in git)
  DISCORD_CHANNEL_ID        Discord channel for the open IRC
  GUILD_DISCORD_CHANNEL_ID  optional: Discord channel for the guild IRC (no guild IRC without it)
  GUILD_ROLES               Discord roles that count as guild members, comma separated
  RELEASES_CHANNEL_ID       optional: new GitHub releases are posted here
  NEW_PEOPLE_CHANNEL_ID     optional: a welcome card for everyone who joins the Spotify Chat server
  NOW_PLAYING_CHANNEL_ID    optional: songs shared with !spotify in the open IRC, with album covers
  MEMBER_ROLE_ID            optional: role every new member gets
  MEMBER_COUNT_CHANNEL_ID   optional: channel renamed to "👥 Members: N"
  IRC_NICK                  optional, default SpotifyDiscord

`bridge.py --list-channels` prints the text channels the bot can see, to find the channel id.
`bridge.py --set-avatar avatar.png` sets the bot's profile picture.
"""
import asyncio
import hashlib
import logging
import os
import random
import re
import secrets
import ssl
import sys
import time
from urllib.parse import quote

import aiohttp
import discord

IRC_HOST = "irc.rizon.net"
IRC_PORT = 6697
IRC_CHANNEL = "#spotifychat"
IRC_NICK = os.environ.get("IRC_NICK", "SpotifyDiscord")
GUILD_CHANNEL_PREFIX = "#mwf-"
MAX_TEXT_BYTES = 400        # same limit as the mod: fits in 512 with "PRIVMSG #spotifychat :" and the prefix
MAX_LINES_PER_MESSAGE = 3   # a long Discord message becomes at most this many IRC lines
SEND_GAP = 1.2              # seconds between IRC lines, so Rizon doesn't kick us for flooding
REJOIN_DELAY = 10
SPOTIFY_GREEN = 0x1ED760
# The mod proves its Minecraft name by "joining" this server id at Mojang. It's a hash of this text plus our
# random code, so it can never be the id of a real Minecraft server (the mod hashes it the same way).
AUTH_PREFIX = "SpotifyChat guild IRC:"
HELLO_GAP = 20              # seconds between guild requests from one nick
PROVE_TIMEOUT = 120
RECHECK_EVERY = 300
GITHUB_REPO = "Doelloosleven/Spotify-Chat"
RELEASE_CHECK_EVERY = 900   # GitHub allows 60 requests an hour without a login
JAR_NAME = re.compile(r"spotify-chat-[\w.]+\+([\w.]+)\.jar")
NOW_PLAYING = re.compile(r"♫ Now playing: (.{1,300})")  # the mod's default song message

OPEN, GUILD = "open", "guild"

log = logging.getLogger("bridge")

IRC_FORMATTING = re.compile(r"\x03(\d{1,2}(,\d{1,2})?)?|[\x02\x0f\x11\x16\x1d\x1e\x1f]")
CONTROL = re.compile(r"[\x00-\x1f\x7f]")
MC_NAME = re.compile(r"\w{1,16}")


def clean(s: str) -> str:
    """No line breaks or control characters in anything sent to IRC."""
    return CONTROL.sub(" ", s).strip()


def split_bytes(s: str, limit: int) -> list[str]:
    """Splits s into pieces of at most `limit` UTF-8 bytes, preferring spaces."""
    out = []
    while len(s.encode()) > limit:
        cut = s.encode()[:limit].decode("utf-8", "ignore")
        space = cut.rfind(" ")
        if space > len(cut) // 2:
            cut = cut[:space]
        out.append(cut.rstrip())
        s = s[len(cut):].lstrip()
    if s:
        out.append(s)
    return out


def server_id(nonce: str) -> str:
    return hashlib.sha1((AUTH_PREFIX + nonce).encode()).hexdigest()


async def has_joined(name: str, sid: str) -> dict | None:
    """Mojang's answer to "did this player just join server sid?": their profile, or None."""
    url = "https://sessionserver.mojang.com/session/minecraft/hasJoined"
    try:
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10)) as s:
            async with s.get(url, params={"username": name, "serverId": sid}) as r:
                if r.status != 200:
                    return None
                data = await r.json(content_type=None)
                return data if isinstance(data, dict) and isinstance(data.get("name"), str) else None
    except (aiohttp.ClientError, asyncio.TimeoutError, ValueError) as e:
        log.warning("Mojang check failed: %s", e)
        return None


async def latest_release() -> dict | None:
    url = f"https://api.github.com/repos/{GITHUB_REPO}/releases/latest"
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "spotify-chat-bridge"}
    try:
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=15)) as s:
            async with s.get(url, headers=headers) as r:
                if r.status != 200:
                    return None
                data = await r.json()
                return data if isinstance(data, dict) and data.get("tag_name") else None
    except (aiohttp.ClientError, asyncio.TimeoutError, ValueError) as e:
        log.warning("GitHub check failed: %s", e)
        return None


def release_embed(release: dict) -> discord.Embed:
    """The release as a card: what's new (without the download table) and a link per Minecraft version."""
    lines = []
    for line in (release.get("body") or "").splitlines():
        if re.match(r"#+\s*Download", line, re.I):
            break  # the table that follows doesn't work on Discord; the field below replaces it
        heading = re.match(r"#+\s*(.+)", line)
        if heading and not lines:
            continue  # the title is already the embed title
        lines.append(f"**{heading.group(1)}**" if heading else line)
    embed = discord.Embed(title=release.get("name") or release["tag_name"], url=release.get("html_url"),
                          description="\n".join(lines).strip()[:3500], color=SPOTIFY_GREEN)
    downloads = []
    for asset in release.get("assets", []):
        m = JAR_NAME.fullmatch(asset.get("name", ""))
        if m:
            downloads.append(f"Minecraft {m.group(1)}: [{asset['name']}]({asset['browser_download_url']})")
    if downloads:
        embed.add_field(name="Download", value="\n".join(sorted(downloads)), inline=False)
    embed.set_footer(text="Auto-update installs it when you close Minecraft")
    return embed


class Irc:
    def __init__(self, on_message, is_member):
        self.on_message = on_message    # async (kind, nick, text, action)
        self.is_member = is_member      # Minecraft name -> in the guild?
        self.writer = None
        self.nick = IRC_NICK
        self.joined = {OPEN: asyncio.Event(), GUILD: asyncio.Event()}
        self.outbox: asyncio.Queue[tuple[str, str]] = asyncio.Queue(maxsize=30)
        self.guild_channel = ""
        self.guild_op = False
        self.verified: dict[str, str] = {}   # lowercase nick -> Minecraft name Mojang confirmed
        self.present: set[str] = set()       # lowercase nicks in the guild channel, besides us
        self.pending: dict[str, tuple[str, str, float]] = {}  # nick -> (name, code, when)
        self.last_hello: dict[str, float] = {}

    def say(self, kind: str, text: str) -> bool:
        """Queues one line for a channel; False if the bridge is behind and dropped it."""
        try:
            self.outbox.put_nowait((kind, text))
            return True
        except asyncio.QueueFull:
            return False

    async def run(self):
        delay = 5
        while True:
            try:
                await self.session()
            except Exception as e:  # reconnect on anything
                log.warning("IRC disconnected: %s", e)
            self.writer = None
            for e in self.joined.values():
                e.clear()
            await asyncio.sleep(delay)
            delay = min(delay * 2, 300)

    def raw(self, line: str):
        if self.writer is not None:
            self.writer.write((line + "\r\n").encode())

    def notice(self, nick: str, text: str):
        self.raw(f"NOTICE {nick} :\x01SCGUILD {text}\x01")

    def channel_kind(self, channel: str) -> str | None:
        c = channel.lower()
        if c == IRC_CHANNEL:
            return OPEN
        if self.guild_channel and c == self.guild_channel.lower():
            return GUILD
        return None

    def new_guild_channel(self):
        """A fresh, random channel every session: we create it, so we're its only operator. Members' mods ask
        again when they see us join #spotifychat."""
        if self.guild_channel:
            self.raw(f"PART {self.guild_channel}")
        self.guild_channel = GUILD_CHANNEL_PREFIX + secrets.token_hex(5)
        self.guild_op = False
        self.verified.clear()
        self.present.clear()
        self.joined[GUILD].clear()
        self.raw(f"JOIN {self.guild_channel}")

    async def session(self):
        tls = ssl.create_default_context()  # checks the certificate and that it's really irc.rizon.net
        reader, writer = await asyncio.wait_for(
            asyncio.open_connection(IRC_HOST, IRC_PORT, ssl=tls, limit=4096), 30)
        self.writer = writer
        self.nick = IRC_NICK
        self.guild_channel = ""
        self.pending.clear()
        self.raw(f"NICK {self.nick}")
        self.raw("USER bridge 0 * :Spotify Chat Discord bridge")
        sender = asyncio.create_task(self.send_loop(writer))
        waiting_for_pong = False
        try:
            while True:
                try:
                    data = await asyncio.wait_for(reader.readline(), 240)
                except asyncio.TimeoutError:
                    if waiting_for_pong:
                        raise ConnectionError("server stopped answering")
                    self.raw("PING :bridge")
                    waiting_for_pong = True
                    continue
                if not data:
                    raise ConnectionError("connection closed")
                waiting_for_pong = False
                await self.handle(data.decode("utf-8", "replace").rstrip("\r\n"))
        finally:
            sender.cancel()
            writer.close()

    async def handle(self, line: str):
        if line.startswith("PING"):
            self.raw("PONG" + line[4:])
            return
        prefix = ""
        if line.startswith(":"):
            prefix, _, line = line[1:].partition(" ")
        line, sep, trailing = line.partition(" :")
        parts = line.split()
        if not parts:
            return
        command = parts[0]
        sender = prefix.split("!", 1)[0]
        me = sender.lower() == self.nick.lower()
        key = sender.lower()

        if command == "001":
            self.nick = parts[1] if len(parts) > 1 else self.nick
            self.raw(f"JOIN {IRC_CHANNEL}")
            self.new_guild_channel()
        elif command in ("432", "433"):  # nick taken: add a number
            self.nick = f"{IRC_NICK[:24]}_{random.randint(100, 999)}"
            self.raw(f"NICK {self.nick}")
        elif command == "353" and len(parts) > 3 and self.channel_kind(parts[3]) == GUILD:  # NAMES
            for name in trailing.split():
                bare = name.lstrip("@+%&~")
                if bare.lower() == self.nick.lower():
                    self.guild_op = name.startswith(("@", "&", "~"))
                else:
                    self.present.add(bare.lower())
        elif command == "366" and len(parts) > 2:  # end of NAMES: we're in
            kind = self.channel_kind(parts[2])
            if kind == OPEN:
                log.info("joined %s as %s", IRC_CHANNEL, self.nick)
                self.joined[OPEN].set()
            elif kind == GUILD:
                if not self.guild_op:  # someone else has this channel: make another one
                    log.warning("not operator in the guild channel, making a new one")
                    self.new_guild_channel()
                    return
                self.raw(f"MODE {self.guild_channel} +ins")  # invite only, no outside messages, hidden
                for nick in list(self.present):
                    self.raw(f"KICK {self.guild_channel} {nick} :Guild members only")
                log.info("guild channel ready")
                self.joined[GUILD].set()
        elif command == "JOIN" and not me:
            if self.channel_kind(trailing or (parts[1] if len(parts) > 1 else "")) == GUILD:
                if key in self.verified:
                    self.present.add(key)
                else:
                    self.raw(f"KICK {self.guild_channel} {sender} :Guild members only")
        elif command == "PART" and not me and len(parts) > 1 and self.channel_kind(parts[1]) == GUILD:
            self.forget(key)
        elif command == "QUIT":
            self.forget(key)
        elif command == "KICK" and len(parts) > 2:
            kind = self.channel_kind(parts[1])
            if parts[2].lower() != self.nick.lower():
                if kind == GUILD:
                    self.forget(parts[2].lower())
            elif kind == OPEN:
                log.warning("kicked from %s by %s: %s", IRC_CHANNEL, sender, trailing)
                self.joined[OPEN].clear()
                asyncio.get_running_loop().call_later(REJOIN_DELAY, self.raw, f"JOIN {IRC_CHANNEL}")
            elif kind == GUILD:
                log.warning("kicked from the guild channel by %s", sender)
                self.new_guild_channel()
        elif command == "NICK":
            new = (trailing or (parts[1] if len(parts) > 1 else "")).lower()
            if me:
                self.nick = trailing or self.nick
            elif key in self.verified:
                self.verified[new] = self.verified.pop(key)
                if key in self.present:
                    self.present.discard(key)
                    self.present.add(new)
        elif command == "PRIVMSG" and len(parts) > 1:
            if parts[1].lower() == self.nick.lower():
                await self.private(sender, trailing)
                return
            kind = self.channel_kind(parts[1])
            if kind is None or me:
                return
            action = trailing.startswith("\x01ACTION ") and trailing.endswith("\x01")
            text = trailing[8:-1] if action else trailing
            if text.startswith("\x01"):
                return  # other CTCP
            text = IRC_FORMATTING.sub("", text).replace("§", "").strip()
            if text:
                await self.on_message(kind, sender, text, action)
        elif command == "ERROR":
            raise ConnectionError(trailing)

    def forget(self, key: str):
        self.present.discard(key)
        self.verified.pop(key, None)

    async def private(self, sender: str, text: str):
        """The mod's guild check: "SCGUILD HELLO <name>", then "SCGUILD PROVED" after the Mojang join."""
        if not (text.startswith("\x01SCGUILD ") and text.endswith("\x01")):
            return
        words = text[9:-1].split()
        key = sender.lower()
        now = time.monotonic()
        if words[:1] == ["HELLO"] and len(words) == 2:
            if now - self.last_hello.get(key, -HELLO_GAP) < HELLO_GAP:
                return
            self.last_hello[key] = now
            for k in [k for k, p in self.pending.items() if now - p[2] > PROVE_TIMEOUT]:
                del self.pending[k]
            name = words[1]
            if not self.joined[GUILD].is_set() or not MC_NAME.fullmatch(name) or not self.is_member(name):
                self.notice(sender, "NO")
                return
            code = secrets.token_hex(16)
            self.pending[key] = (name, code, now)
            self.notice(sender, f"PROVE {code}")
        elif words == ["PROVED"]:
            p = self.pending.pop(key, None)
            if p and now - p[2] <= PROVE_TIMEOUT:
                asyncio.create_task(self.let_in(sender, p[0], p[1]))

    async def let_in(self, nick: str, name: str, code: str):
        profile = await has_joined(name, server_id(code))
        if profile is None:
            self.notice(nick, "FAIL Mojang didn't confirm your account")
            return
        if not self.is_member(profile["name"]):
            self.notice(nick, "NO")
            return
        self.verified[nick.lower()] = profile["name"]
        self.raw(f"INVITE {nick} {self.guild_channel}")
        log.info("let %s into the guild channel", profile["name"])

    def recheck(self):
        """Kicks everyone who lost their guild role."""
        for key, name in list(self.verified.items()):
            if not self.is_member(name):
                self.verified.pop(key)
                if key in self.present:
                    self.raw(f"KICK {self.guild_channel} {key} :No longer in the guild")
                    log.info("removed %s from the guild channel", name)

    async def send_loop(self, writer):
        while True:
            kind, text = await self.outbox.get()
            try:
                await asyncio.wait_for(self.joined[kind].wait(), 60)
            except asyncio.TimeoutError:
                log.warning("not in the %s channel, dropped a line", kind)
                continue
            if self.writer is not writer:
                return
            target = IRC_CHANNEL if kind == OPEN else self.guild_channel
            self.raw(f"PRIVMSG {target} :{text}")
            await writer.drain()
            await asyncio.sleep(SEND_GAP)


async def deezer_cover(artist: str, song: str) -> str | None:
    """Album cover for a song from Deezer's public search (the mod uses the same), or None."""
    try:
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10)) as s:
            async with s.get("https://api.deezer.com/search", params={"q": f"{artist} {song}", "limit": 1}) as r:
                data = await r.json(content_type=None)
        cover = data["data"][0]["album"]["cover_medium"]
        return cover if isinstance(cover, str) and cover.startswith("https://") else None
    except (aiohttp.ClientError, asyncio.TimeoutError, ValueError, KeyError, IndexError, TypeError):
        return None


def now_playing_embed(nick: str, track: str, cover: str | None) -> discord.Embed:
    """"Song - Artist, Feat - Album" (the mod's default format) as a card with the cover."""
    parts = track.removesuffix(" (paused)").split(" - ")
    song, artists = parts[0], parts[1] if len(parts) > 1 else ""
    album = " - ".join(parts[2:])
    search = quote(f"{song} {artists.split(',')[0]}".strip())
    embed = discord.Embed(title=song[:256], url=f"https://open.spotify.com/search/{search}", color=SPOTIFY_GREEN,
                          description="\n".join(x for x in (f"by **{artists}**" if artists else "",
                                                            f"on *{album}*" if album else "") if x)[:1000],
                          timestamp=discord.utils.utcnow())
    embed.set_author(name=f"{nick} is listening to")
    if cover:
        embed.set_thumbnail(url=cover)
    embed.set_footer(text="Shared in game with !spotify")
    return embed


class Bridge(discord.Client):
    def __init__(self, channels: dict[str, int], guild_roles: set[str], extras: dict[str, int]):
        intents = discord.Intents.none()
        intents.guilds = True
        intents.guild_messages = True
        intents.message_content = True
        intents.members = True  # to see who has a guild role, and who joins
        super().__init__(intents=intents, allowed_mentions=discord.AllowedMentions.none(),
                         activity=discord.Activity(type=discord.ActivityType.listening, name="the in-game IRC"))
        self.channels = channels        # kind -> Discord channel id
        self.kinds = {cid: kind for kind, cid in channels.items()}
        self.guild_roles = {r.lower() for r in guild_roles}
        self.releases_channel = extras.get("releases", 0)
        self.new_people_channel = extras.get("new_people", 0)
        self.now_playing_channel = extras.get("now_playing", 0)
        self.member_role = extras.get("member_role", 0)
        self.member_count_channel = extras.get("member_count", 0)
        self.count_dirty = True
        self.irc = Irc(self.from_irc, self.is_member)

    async def setup_hook(self):
        self.irc_task = asyncio.create_task(self.irc.run())
        self.recheck_task = asyncio.create_task(self.recheck_loop())
        if self.releases_channel:
            self.release_task = asyncio.create_task(self.release_loop())
        if self.member_count_channel:
            self.count_task = asyncio.create_task(self.member_count_loop())

    def home(self) -> discord.Guild | None:
        """The Spotify Chat server: the one with the open IRC channel"""
        channel = self.get_channel(self.channels.get(OPEN, 0))
        return channel.guild if channel else None

    async def on_member_join(self, member: discord.Member):
        if member.guild != self.home() or member.bot:
            return
        self.count_dirty = True
        role = member.guild.get_role(self.member_role)
        try:
            if role:
                await member.add_roles(role, reason="New member")
            channel = self.get_channel(self.new_people_channel)
            if channel:
                await channel.send(content=member.mention, embed=self.welcome_embed(member),
                                   allowed_mentions=discord.AllowedMentions(users=[member]))
        except discord.HTTPException as e:
            log.warning("welcome failed: %s", e)

    def welcome_embed(self, member: discord.Member) -> discord.Embed:
        humans = sum(1 for m in member.guild.members if not m.bot)
        irc = self.channels.get(OPEN, 0)
        lines = [f"Hey {member.mention}, you're member **#{humans}**.", ""]
        welcome = discord.utils.find(lambda c: c.name.endswith("welcome"), member.guild.text_channels)
        if welcome:
            lines.append(f"Get the mod in {welcome.mention}")
        lines.append(f"Chat with players in game in <#{irc}>")
        if self.now_playing_channel:
            lines.append(f"See what everyone's listening to in <#{self.now_playing_channel}>")
        embed = discord.Embed(title="Welcome to Spotify Chat", description="\n".join(lines), color=SPOTIFY_GREEN)
        embed.set_thumbnail(url=member.display_avatar.url)
        embed.set_footer(text="Type !spotify in Minecraft and everyone sees your song",
                         icon_url=self.user.display_avatar.url)
        return embed

    async def member_count_loop(self):
        """Keeps "👥 Members: N" up to date. Discord allows two renames per 10 minutes, so at most every 6."""
        await self.wait_until_ready()
        while True:
            channel = self.get_channel(self.member_count_channel)
            if self.count_dirty and channel:
                self.count_dirty = False
                name = f"👥 Members: {sum(1 for m in channel.guild.members if not m.bot)}"
                if channel.name != name:
                    try:
                        await channel.edit(name=name, reason="Member count")
                    except discord.HTTPException as e:
                        log.warning("member count update failed: %s", e)
            await asyncio.sleep(360)

    async def release_loop(self):
        """Posts each new GitHub release once. What's already posted is read back from the channel."""
        await self.wait_until_ready()
        while True:
            try:
                await self.post_new_release()
            except discord.HTTPException as e:
                log.warning("couldn't post the release: %s", e)
            await asyncio.sleep(RELEASE_CHECK_EVERY)

    async def post_new_release(self):
        channel = self.get_channel(self.releases_channel)
        release = await latest_release()
        if channel is None or release is None:
            return
        url = release.get("html_url")
        async for message in channel.history(limit=20):
            if message.author == self.user and any(e.url == url for e in message.embeds):
                return  # already posted
        embed = release_embed(release)
        embed.set_thumbnail(url=self.user.display_avatar.url)
        await channel.send(embed=embed)
        log.info("posted release %s", release["tag_name"])

    async def on_ready(self):
        for kind, cid in self.channels.items():
            channel = self.get_channel(cid)
            if channel is None:
                log.error("can't see Discord channel %s: check the id and the bot's permissions", cid)
            else:
                log.info("%s IRC <-> #%s in %s", kind, channel.name, channel.guild.name)

    def is_member(self, mc_name: str) -> bool:
        """Does a Discord member named mc_name (nickname or username) have a guild role?"""
        channel = self.get_channel(self.channels.get(GUILD, 0))
        if channel is None or not self.guild_roles:
            return False
        want = mc_name.lower()
        for m in channel.guild.members:
            if not any(r.name.lower() in self.guild_roles for r in m.roles):
                continue
            names = {m.name.lower(), m.display_name.lower()}
            names.update(w.lower() for w in re.findall(r"\w{3,16}", m.display_name))
            if want in names:
                return True
        return False

    async def on_member_update(self, before, after):
        if before.roles != after.roles or before.display_name != after.display_name:
            self.irc.recheck()

    async def on_member_remove(self, member):
        self.irc.recheck()
        if member.guild == self.home():
            self.count_dirty = True

    async def recheck_loop(self):
        while True:
            await asyncio.sleep(RECHECK_EVERY)
            self.irc.recheck()

    async def from_irc(self, kind: str, nick: str, text: str, action: bool):
        """Posts an IRC message as a small card: the name, the message, the time."""
        channel = self.get_channel(self.channels.get(kind, 0))
        if channel is None:
            return
        song = NOW_PLAYING.fullmatch(text) if kind == OPEN and not action else None
        text = discord.utils.escape_markdown(text)
        embed = discord.Embed(description=(f"*{text}*" if action else text)[:4000],
                              color=SPOTIFY_GREEN, timestamp=discord.utils.utcnow())
        embed.set_author(name=nick)
        try:
            await channel.send(embed=embed)
        except discord.HTTPException as e:
            log.warning("Discord send failed: %s", e)
        if song and self.now_playing_channel:
            await self.post_now_playing(nick, song.group(1))

    async def post_now_playing(self, nick: str, track: str):
        """A song shared with !spotify in the open IRC also goes to #now-playing, with its album cover."""
        channel = self.get_channel(self.now_playing_channel)
        if channel is None:
            return
        parts = track.split(" - ")
        cover = await deezer_cover(parts[1].split(",")[0] if len(parts) > 1 else "", parts[0])
        try:
            await channel.send(embed=now_playing_embed(nick, track, cover))
        except discord.HTTPException as e:
            log.warning("now playing post failed: %s", e)

    async def on_message(self, message: discord.Message):
        kind = self.kinds.get(message.channel.id)
        if kind is None or message.author.bot or message.webhook_id:
            return
        name = clean(message.author.display_name)[:32] or "someone"
        parts = [message.clean_content] + [a.url for a in message.attachments]
        lines = [clean(l) for p in parts for l in p.splitlines() if clean(l)]
        out = [piece for l in lines for piece in split_bytes(f"<{name}> {l}", MAX_TEXT_BYTES)]
        if len(out) > MAX_LINES_PER_MESSAGE:
            out = out[:MAX_LINES_PER_MESSAGE]
            out[-1] = split_bytes(out[-1], MAX_TEXT_BYTES - 4)[0] + " ..."
        for line in out:
            if not self.irc.say(kind, line):
                log.warning("IRC outbox full, dropped a line")
                break


async def list_channels(token: str):
    intents = discord.Intents.none()
    intents.guilds = True
    client = discord.Client(intents=intents)

    @client.event
    async def on_ready():
        for guild in client.guilds:
            for ch in guild.text_channels:
                print(f"{ch.id}  {guild.name} / #{ch.name}")
        await client.close()

    await client.start(token)


async def set_avatar(token: str, path: str):
    client = discord.Client(intents=discord.Intents.none())
    async with client:
        await client.login(token)
        with open(path, "rb") as f:
            await client.user.edit(avatar=f.read())
    print("avatar updated")


def main():
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
    token = os.environ.get("DISCORD_TOKEN", "").strip()
    if not token:
        sys.exit("DISCORD_TOKEN is not set")
    if "--list-channels" in sys.argv:
        asyncio.run(list_channels(token))
        return
    if len(sys.argv) == 3 and sys.argv[1] == "--set-avatar":
        asyncio.run(set_avatar(token, sys.argv[2]))
        return
    channels = {}
    for kind, var in ((OPEN, "DISCORD_CHANNEL_ID"), (GUILD, "GUILD_DISCORD_CHANNEL_ID")):
        value = os.environ.get(var, "").strip()
        if value.isdigit():
            channels[kind] = int(value)
    if OPEN not in channels:
        sys.exit("DISCORD_CHANNEL_ID is not set")
    roles = {r.strip() for r in os.environ.get("GUILD_ROLES", "").split(",") if r.strip()}
    extras = {}
    for key, var in (("releases", "RELEASES_CHANNEL_ID"), ("new_people", "NEW_PEOPLE_CHANNEL_ID"),
                     ("now_playing", "NOW_PLAYING_CHANNEL_ID"), ("member_role", "MEMBER_ROLE_ID"),
                     ("member_count", "MEMBER_COUNT_CHANNEL_ID")):
        value = os.environ.get(var, "").strip()
        if value.isdigit():
            extras[key] = int(value)
    Bridge(channels, roles, extras).run(token, log_handler=None)


if __name__ == "__main__":
    main()
