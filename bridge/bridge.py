#!/usr/bin/env python3
"""Relays Spotify Chat's IRC to Discord and back, and runs a private IRC channel for every Hypixel guild.

Open IRC: #spotifychat on Rizon <-> DISCORD_CHANNEL_ID.
Guild IRC: every guild gets its own hidden, invite-only channel. The mod says its Minecraft name, the bot looks
up the player's Hypixel guild, asks the mod to prove the name with Mojang (the same check Minecraft servers do)
and invites it into that guild's channel. Leaving the guild gets you kicked. One guild's channel can also be
linked to a Discord channel (GUILD_DISCORD_CHANNEL_ID + GUILD_HYPIXEL_NAME).

Without a Hypixel API key the bot goes by the guild name the mod read in game (/g online). Mods before 1.5.0
don't send it; they only get into the linked guild, by Discord role: GUILD_ROLES, which the guild's own bot
hands out to real guild members.

Settings come from the environment (see spotify-chat-bridge.service):
  DISCORD_TOKEN             bot token (keep it in /etc/spotify-chat-bridge/token.env, never in git)
  HYPIXEL_API_KEY           optional: Hypixel API key, for every guild (in hypixel.env, never in git)
  DISCORD_CHANNEL_ID        Discord channel for the open IRC
  GUILD_DISCORD_CHANNEL_ID  optional: Discord channel for one guild's IRC
  GUILD_HYPIXEL_NAME        the Hypixel guild that channel belongs to
  GUILD_ROLES               without an API key: Discord roles that count as members of that guild, comma separated
  RELEASES_CHANNEL_ID       optional: new GitHub releases are posted here
  NEW_PEOPLE_CHANNEL_ID     optional: a welcome card for everyone who joins the Spotify Chat server
  NOW_PLAYING_CHANNEL_ID    optional: songs shared with !spotify in the open IRC, with album covers
  MEMBER_ROLE_ID            optional: role every new member gets
  MEMBER_COUNT_CHANNEL_ID   optional: channel renamed to "👥 Members: N"
  IRC_KEEP_DAYS             optional: messages in the open IRC's Discord channel are deleted after this many
                            days (pinned ones stay); not set = kept forever
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
from datetime import timedelta
from urllib.parse import quote

import aiohttp
import discord

IRC_HOST = "irc.rizon.net"
IRC_PORT = 6697
IRC_CHANNEL = "#spotifychat"
IRC_NICK = os.environ.get("IRC_NICK", "SpotifyDiscord")
GUILD_CHANNEL_PREFIX = "#g-"
MAX_ROOMS = 200             # Rizon lets one nick into 250 channels
ROOM_IDLE = 600             # an empty guild channel is closed after about this many seconds
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
CLEANUP_EVERY = 3600        # how often old IRC messages are deleted on Discord (IRC_KEEP_DAYS)
HYPIXEL_GUILD_URL = "https://api.hypixel.net/v2/guild"
HYPIXEL_CACHE = 3600        # seconds a guild's member list is trusted; Hypixel asks for about once an hour
UUID_CACHE = 3600
ROLE_GUILD = "discord-roles"  # the room key without an API key: the guild the Discord roles belong to
GITHUB_REPO = "Doelloosleven/Spotify-Chat"
RELEASE_CHECK_EVERY = 900   # GitHub allows 60 requests an hour without a login
JAR_NAME = re.compile(r"spotify-chat-[\w.]+\+([\w.]+)\.jar")
NOW_PLAYING = re.compile(r"♫ Now playing: (.{1,300})")  # the mod's default song message

OPEN, GUILD_LINK = "open", "guild"  # Discord channel kinds; IRC targets are OPEN or a guild's key

log = logging.getLogger("bridge")

IRC_FORMATTING = re.compile(r"\x03(\d{1,2}(,\d{1,2})?)?|[\x02\x0f\x11\x16\x1d\x1e\x1f]")
CONTROL = re.compile(r"[\x00-\x1f\x7f]")
MC_NAME = re.compile(r"[A-Za-z0-9_]{1,16}")
GUILD_NAME = re.compile(r"[A-Za-z0-9_ ]{1,32}")
UUID = re.compile(r"[0-9a-f]{32}")


class CheckFailed(Exception):
    """Can't tell right now which guild a player is in: an API didn't answer or is rate limited."""


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
                ok = isinstance(data, dict) and isinstance(data.get("name"), str) \
                    and UUID.fullmatch(str(data.get("id")))
                return data if ok else None
    except (aiohttp.ClientError, asyncio.TimeoutError, ValueError) as e:
        log.warning("Mojang check failed: %s", e)
        return None


async def mojang_uuid(name: str) -> str | None:
    """A Minecraft name's account id, or None if nobody has that name."""
    url = f"https://api.mojang.com/users/profiles/minecraft/{name}"
    try:
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10)) as s:
            async with s.get(url) as r:
                if r.status in (204, 404):
                    return None
                if r.status != 200:
                    raise CheckFailed(f"Mojang said {r.status}")
                data = await r.json(content_type=None)
    except (aiohttp.ClientError, asyncio.TimeoutError, ValueError) as e:
        raise CheckFailed(f"Mojang didn't answer: {e}") from e
    uuid = data.get("id") if isinstance(data, dict) else None
    return uuid if isinstance(uuid, str) and UUID.fullmatch(uuid) else None


class Hypixel:
    """Which Hypixel guild a player is in. A lookup returns the whole guild, so its member list is kept for a
    while and answers for everyone in it: a guild coming online all at once costs one request."""

    def __init__(self, key: str):
        self.key = key
        self.guilds: dict[str, tuple[float, str, set[str]]] = {}  # guild id -> (when, name, member uuids)
        self.players: dict[str, tuple[float, str | None]] = {}     # uuid -> (when, guild id or None)
        self.lock = asyncio.Lock()
        self.busy_until = 0.0

    async def guild_of(self, uuid: str) -> tuple[str, str] | None:
        """(guild id, guild name) for a player, or None if they're not in a guild"""
        async with self.lock:  # one at a time, so the rest of a guild is answered from the first lookup
            now = time.monotonic()
            when, gid = self.players.get(uuid, (0.0, None))
            if now - when < HYPIXEL_CACHE:
                if gid is None:
                    return None
                cached = self.guilds.get(gid)
                if cached and now - cached[0] < HYPIXEL_CACHE:
                    return gid, cached[1]
            if len(self.players) > 50_000:
                self.players = {u: p for u, p in self.players.items() if now - p[0] < HYPIXEL_CACHE}
            guild = await self.lookup(player=uuid)
            if guild is None:
                self.players[uuid] = (now, None)
                return None
            return self.keep(guild, now)

    def keep(self, guild: dict, now: float) -> tuple[str, str]:
        gid, name = guild.get("_id"), guild.get("name")
        if not isinstance(gid, str) or not isinstance(name, str):
            raise CheckFailed("Hypixel sent a guild without an id or name")
        members = {m["uuid"] for m in guild.get("members") or [] if isinstance(m, dict)
                   and isinstance(m.get("uuid"), str)}
        for u, (_, g) in list(self.players.items()):
            if g == gid and u not in members:  # left the guild
                del self.players[u]
        for u in members:
            self.players[u] = (now, gid)
        self.guilds[gid] = (now, name, members)
        return gid, name

    async def lookup(self, **params) -> dict | None:
        """The guild for player=<uuid> or name=<guild name>, or None if there's none"""
        if time.monotonic() < self.busy_until:
            raise CheckFailed("Hypixel rate limit")
        try:
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10)) as s:
                async with s.get(HYPIXEL_GUILD_URL, params=params, headers={"API-Key": self.key}) as r:
                    if r.status == 429:
                        reset = r.headers.get("RateLimit-Reset", "")
                        self.busy_until = time.monotonic() + (int(reset) if reset.isdigit() else 60)
                        raise CheckFailed("Hypixel rate limit")
                    status = r.status
                    data = await r.json(content_type=None)
        except (aiohttp.ClientError, asyncio.TimeoutError, ValueError) as e:
            raise CheckFailed(f"Hypixel didn't answer: {e}") from e
        if status != 200 or not isinstance(data, dict) or not data.get("success"):
            cause = data.get("cause") if isinstance(data, dict) else None
            raise CheckFailed(f"Hypixel said {status}: {cause}")
        guild = data.get("guild")
        return guild if isinstance(guild, dict) else None


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


class Room:
    """One guild's hidden channel. It's made when the first member comes in and closed once it's been empty for
    a while, with a new random name every time."""

    def __init__(self, key: str, name: str):
        self.key = key              # Hypixel guild id, or ROLE_GUILD
        self.name = name            # the guild's name
        self.channel = ""
        self.op = False
        self.ready = asyncio.Event()
        # lowercase nick -> (Minecraft name, uuid) Mojang confirmed, and the guild name their mod said
        self.verified: dict[str, tuple[str, str, str | None]] = {}
        self.present: set[str] = set()                   # lowercase nicks in the channel, besides us
        self.last_used = time.monotonic()


class Irc:
    def __init__(self, on_message, guild_of):
        self.on_message = on_message    # async (target, nick, text, action): target is OPEN or a Room
        # async (Minecraft name, uuid or None, guild name the mod said or None) -> (guild key, guild name) or None
        self.guild_of = guild_of
        self.writer = None
        self.nick = IRC_NICK
        self.joined = asyncio.Event()   # in #spotifychat
        self.outbox: asyncio.Queue[tuple[str, str]] = asyncio.Queue(maxsize=30)
        self.rooms: dict[str, Room] = {}  # guild key -> its channel
        self.pending: dict[str, tuple[str, str, float, str | None]] = {}  # nick -> (name, code, when, guild)
        self.last_hello: dict[str, float] = {}
        self.tasks: set[asyncio.Task] = set()

    def say(self, target: str, text: str) -> bool:
        """Queues one line for #spotifychat (OPEN) or a guild's channel (its key); False if the bridge is
        behind and dropped it."""
        try:
            self.outbox.put_nowait((target, text))
            return True
        except asyncio.QueueFull:
            return False

    def spawn(self, coro):
        task = asyncio.create_task(coro)
        self.tasks.add(task)
        task.add_done_callback(self.tasks.discard)

    async def run(self):
        delay = 5
        while True:
            try:
                await self.session()
            except Exception as e:  # reconnect on anything
                log.warning("IRC disconnected: %s", e)
            self.writer = None
            self.joined.clear()
            for room in self.rooms.values():
                room.ready.clear()
            await asyncio.sleep(delay)
            delay = min(delay * 2, 300)

    def raw(self, line: str):
        if self.writer is not None:
            self.writer.write((line + "\r\n").encode())

    def notice(self, nick: str, text: str):
        self.raw(f"NOTICE {nick} :\x01SCGUILD {text}\x01")

    def room_for(self, channel: str) -> Room | None:
        c = channel.lower()
        return next((r for r in self.rooms.values() if r.channel.lower() == c), None)

    def open_room(self, room: Room):
        """(Re)makes a guild's channel under a fresh random name: we create it, so we're its only operator."""
        if room.channel:
            self.raw(f"PART {room.channel}")
        room.channel = GUILD_CHANNEL_PREFIX + secrets.token_hex(5)
        room.op = False
        room.present.clear()
        room.ready.clear()
        self.raw(f"JOIN {room.channel}")

    def room_ready(self, room: Room):
        if not room.op:  # someone else has this channel: make another one
            log.warning("not operator in %s, making a new one", room.channel)
            self.open_room(room)
            return
        self.raw(f"MODE {room.channel} +ins")  # invite only, no outside messages, hidden
        for nick in room.present - room.verified.keys():
            self.raw(f"KICK {room.channel} {nick} :Guild members only")
        room.ready.set()
        for nick in room.verified.keys() - room.present:  # members waiting to get in
            self.raw(f"INVITE {nick} {room.channel}")
        log.info("%s's channel is ready", room.name)

    def close_room(self, room: Room):
        if self.rooms.get(room.key) is room:
            del self.rooms[room.key]
        self.raw(f"PART {room.channel}")
        room.ready.clear()
        log.info("closed %s's channel", room.name)

    async def session(self):
        tls = ssl.create_default_context()  # checks the certificate and that it's really irc.rizon.net
        reader, writer = await asyncio.wait_for(
            asyncio.open_connection(IRC_HOST, IRC_PORT, ssl=tls, limit=4096), 30)
        self.writer = writer
        self.nick = IRC_NICK
        self.rooms.clear()  # the old channels went away with the old connection; members' mods ask again
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
        elif command in ("432", "433"):  # nick taken: add a number
            self.nick = f"{IRC_NICK[:24]}_{random.randint(100, 999)}"
            self.raw(f"NICK {self.nick}")
        elif command == "353" and len(parts) > 3 and (room := self.room_for(parts[3])):  # NAMES
            for name in trailing.split():
                bare = name.lstrip("@+%&~")
                if bare.lower() == self.nick.lower():
                    room.op = name.startswith(("@", "&", "~"))
                else:
                    room.present.add(bare.lower())
        elif command == "366" and len(parts) > 2:  # end of NAMES: we're in
            if parts[2].lower() == IRC_CHANNEL:
                log.info("joined %s as %s", IRC_CHANNEL, self.nick)
                self.joined.set()
            elif room := self.room_for(parts[2]):
                self.room_ready(room)
        elif command == "JOIN" and not me:
            room = self.room_for(trailing or (parts[1] if len(parts) > 1 else ""))
            if room and key in room.verified:
                room.present.add(key)
            elif room:
                self.raw(f"KICK {room.channel} {sender} :Guild members only")
        elif command == "PART" and not me and len(parts) > 1 and (room := self.room_for(parts[1])):
            self.forget(room, key)
        elif command == "QUIT":
            for room in self.rooms.values():
                self.forget(room, key)
        elif command == "KICK" and len(parts) > 2:
            room = self.room_for(parts[1])
            if parts[2].lower() != self.nick.lower():
                if room:
                    self.forget(room, parts[2].lower())
            elif parts[1].lower() == IRC_CHANNEL:
                log.warning("kicked from %s by %s: %s", IRC_CHANNEL, sender, trailing)
                self.joined.clear()
                asyncio.get_running_loop().call_later(REJOIN_DELAY, self.raw, f"JOIN {IRC_CHANNEL}")
            elif room:
                log.warning("kicked from %s's channel by %s", room.name, sender)
                self.open_room(room)
        elif command == "NICK":
            new = (trailing or (parts[1] if len(parts) > 1 else "")).lower()
            if me:
                self.nick = trailing or self.nick
            else:
                for room in self.rooms.values():
                    if key in room.verified:
                        room.verified[new] = room.verified.pop(key)
                    if key in room.present:
                        room.present.discard(key)
                        room.present.add(new)
        elif command == "PRIVMSG" and len(parts) > 1:
            if parts[1].lower() == self.nick.lower():
                await self.private(sender, trailing)
                return
            target = OPEN if parts[1].lower() == IRC_CHANNEL else self.room_for(parts[1])
            if target is None or me:
                return
            action = trailing.startswith("\x01ACTION ") and trailing.endswith("\x01")
            text = trailing[8:-1] if action else trailing
            if text.startswith("\x01"):
                return  # other CTCP
            text = IRC_FORMATTING.sub("", text).replace("§", "").strip()
            if text:
                await self.on_message(target, sender, text, action)
        elif command in ("405", "437", "471", "473", "474", "475") and len(parts) > 2 \
                and (room := self.room_for(parts[2])):  # couldn't make the channel
            log.warning("couldn't join %s for %s: %s", room.channel, room.name, trailing)
            self.close_room(room)
        elif command == "ERROR":
            raise ConnectionError(trailing)

    def forget(self, room: Room, key: str):
        room.present.discard(key)
        room.verified.pop(key, None)

    async def private(self, sender: str, text: str):
        """The mod's guild check: "SCGUILD HELLO <name> [<guild name>]", then "SCGUILD PROVED" after the Mojang
        join. Mods before 1.5.0 don't send the guild name."""
        if not (text.startswith("\x01SCGUILD ") and text.endswith("\x01")):
            return
        words = text[9:-1].split()
        key = sender.lower()
        now = time.monotonic()
        if words[:1] == ["HELLO"] and len(words) >= 2:
            if now - self.last_hello.get(key, -HELLO_GAP) < HELLO_GAP:
                return
            if len(self.last_hello) > 10_000:
                self.last_hello = {k: t for k, t in self.last_hello.items() if now - t < HELLO_GAP}
            self.last_hello[key] = now
            for k in [k for k, p in self.pending.items() if now - p[2] > PROVE_TIMEOUT]:
                del self.pending[k]
            claim = " ".join(words[2:]) or None
            if MC_NAME.fullmatch(words[1]) and (claim is None or GUILD_NAME.fullmatch(claim)):
                self.spawn(self.offer(sender, words[1], claim))
            else:
                self.notice(sender, "NO")
        elif words == ["PROVED"]:
            p = self.pending.pop(key, None)
            if p and now - p[2] <= PROVE_TIMEOUT:
                self.spawn(self.let_in(sender, p[0], p[1], p[3]))

    async def offer(self, nick: str, name: str, claim: str | None):
        """A guild member gets a code to prove their name with; everyone else a no."""
        try:
            guild = await self.guild_of(name, None, claim)
        except CheckFailed as e:
            log.warning("guild check for %s failed: %s", name, e)
            self.notice(nick, "FAIL couldn't check your guild right now")
            return
        if guild is None:
            self.notice(nick, "NO")
            return
        code = secrets.token_hex(16)
        self.pending[nick.lower()] = (name, code, time.monotonic(), claim)
        self.notice(nick, f"PROVE {code}")

    async def let_in(self, nick: str, name: str, code: str, claim: str | None):
        profile = await has_joined(name, server_id(code))
        if profile is None:
            self.notice(nick, "FAIL Mojang didn't confirm your account")
            return
        try:
            guild = await self.guild_of(profile["name"], profile["id"], claim)
        except CheckFailed as e:
            log.warning("guild check for %s failed: %s", profile["name"], e)
            self.notice(nick, "FAIL couldn't check your guild right now")
            return
        if guild is None:
            self.notice(nick, "NO")
            return
        key = nick.lower()
        for other in self.rooms.values():  # one guild per player: out of the old one after switching guilds
            if other.key != guild[0] and other.verified.pop(key, None) and key in other.present:
                self.raw(f"KICK {other.channel} {key} :Moved to another guild")
        room = self.rooms.get(guild[0])
        if room is None:
            if len(self.rooms) >= MAX_ROOMS:
                log.warning("too many guild channels, can't make one for %s", guild[1])
                self.notice(nick, "FAIL the guild IRC is full right now")
                return
            room = self.rooms[guild[0]] = Room(*guild)
            self.open_room(room)
        room.verified[key] = (profile["name"], profile["id"], claim)
        room.last_used = time.monotonic()
        if room.ready.is_set():
            self.raw(f"INVITE {nick} {room.channel}")
        log.info("let %s into %s's channel", profile["name"], room.name)

    async def recheck(self):
        """Kicks everyone who left their guild, and closes channels that have been empty for a while."""
        now = time.monotonic()
        for room in list(self.rooms.values()):
            if room.present:
                room.last_used = now
            elif now - room.last_used > ROOM_IDLE:
                self.close_room(room)
                continue
            for key, (name, uuid, claim) in list(room.verified.items()):
                try:
                    guild = await self.guild_of(name, uuid, claim)
                except CheckFailed:
                    continue  # can't tell right now: leave them be
                if (guild is None or guild[0] != room.key) and room.verified.pop(key, None):
                    if key in room.present:
                        self.raw(f"KICK {room.channel} {key} :No longer in the guild")
                    log.info("removed %s from %s's channel", name, room.name)

    async def send_loop(self, writer):
        while True:
            target, text = await self.outbox.get()
            room = None if target == OPEN else self.rooms.get(target)
            if target != OPEN and room is None:
                continue  # nobody from that guild is online
            try:
                await asyncio.wait_for((self.joined if room is None else room.ready).wait(), 60)
            except asyncio.TimeoutError:
                log.warning("not in %s, dropped a line", IRC_CHANNEL if room is None else room.channel)
                continue
            if self.writer is not writer:
                return
            self.raw(f"PRIVMSG {IRC_CHANNEL if room is None else room.channel} :{text}")
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
    def __init__(self, channels: dict[str, int], guild_roles: set[str], extras: dict[str, int],
                 hypixel_key: str, linked_guild: str):
        intents = discord.Intents.none()
        intents.guilds = True
        intents.guild_messages = True
        intents.message_content = True
        intents.members = True  # to see who has a guild role, and who joins
        super().__init__(intents=intents, allowed_mentions=discord.AllowedMentions.none(),
                         activity=discord.Activity(type=discord.ActivityType.listening, name="the in-game IRC"))
        self.channels = channels        # OPEN / GUILD_LINK -> Discord channel id
        self.guild_roles = {r.lower() for r in guild_roles}
        self.hypixel = Hypixel(hypixel_key) if hypixel_key else None
        self.linked_guild = linked_guild  # the Hypixel guild of the Discord guild channel
        self.uuids: dict[str, tuple[float, str | None]] = {}  # lowercase Minecraft name -> (when, uuid)
        self.releases_channel = extras.get("releases", 0)
        self.new_people_channel = extras.get("new_people", 0)
        self.now_playing_channel = extras.get("now_playing", 0)
        self.member_role = extras.get("member_role", 0)
        self.member_count_channel = extras.get("member_count", 0)
        self.keep_days = extras.get("keep_days", 0)
        self.count_dirty = True
        self.irc = Irc(self.from_irc, self.guild_of)

    async def setup_hook(self):
        self.irc_task = asyncio.create_task(self.irc.run())
        self.recheck_task = asyncio.create_task(self.recheck_loop())
        if self.releases_channel:
            self.release_task = asyncio.create_task(self.release_loop())
        if self.member_count_channel:
            self.count_task = asyncio.create_task(self.member_count_loop())
        if self.keep_days:
            self.cleanup_task = asyncio.create_task(self.cleanup_loop())
        if self.hypixel and self.linked_guild:
            self.link_task = asyncio.create_task(self.check_link())

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

    async def cleanup_loop(self):
        await self.wait_until_ready()
        while True:
            try:
                await self.delete_old_messages()
            except discord.HTTPException as e:
                log.warning("deleting old IRC messages failed: %s", e)
            await asyncio.sleep(CLEANUP_EVERY)

    async def delete_old_messages(self):
        """Deletes what's older than IRC_KEEP_DAYS in the open IRC's Discord channel; pinned messages stay."""
        channel = self.get_channel(self.channels.get(OPEN, 0))
        if channel is None:
            return
        cutoff = discord.utils.utcnow() - timedelta(days=self.keep_days)
        gone = await channel.purge(limit=None, before=cutoff, check=lambda m: not m.pinned,
                                   reason=f"IRC messages are kept {self.keep_days} days")
        if gone:
            log.info("deleted %d IRC messages older than %d days", len(gone), self.keep_days)

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
        for name, cid in self.channels.items():
            channel = self.get_channel(cid)
            if channel is None:
                log.error("can't see Discord channel %s: check the id and the bot's permissions", cid)
            else:
                log.info("%s IRC <-> #%s in %s", name, channel.name, channel.guild.name)
        log.info("guild IRC: %s", "Hypixel API" if self.hypixel
                 else "guild names from the mod (Discord roles for mods before 1.5.0)")

    async def check_link(self):
        """Logs which Hypixel guild the Discord guild channel belongs to, to catch a typo in its name early
        (and a bad API key)."""
        try:
            guild = await self.hypixel.lookup(name=self.linked_guild)
        except CheckFailed as e:
            log.warning("couldn't look up %s on Hypixel: %s", self.linked_guild, e)
            return
        if guild is None:
            log.warning("there's no Hypixel guild named %r: check GUILD_HYPIXEL_NAME", self.linked_guild)
        else:
            log.info("Discord guild channel is linked to Hypixel guild %s", guild.get("name"))

    async def guild_of(self, name: str, uuid: str | None, claim: str | None) -> tuple[str, str] | None:
        """The guild a Minecraft player is in, as (key, name), or None. CheckFailed when it can't tell right now.

        With an API key that's Hypixel's answer. Without one it's the guild name the mod read in game (claim):
        good enough until the key comes, though a modded client could say anything. Mods before 1.5.0 send no
        claim; they only get the linked guild, by Discord role."""
        if self.hypixel is None:
            if claim is None:
                return (ROLE_GUILD, self.linked_guild or "the guild") if self.is_member(name) else None
            if self.linked_guild and claim.lower() == self.linked_guild.lower():
                return ROLE_GUILD, self.linked_guild  # the same channel as the Discord-role members
            return "claim:" + claim.lower(), claim
        if uuid is None:
            now = time.monotonic()
            when, uuid = self.uuids.get(name.lower(), (0.0, None))
            if now - when >= UUID_CACHE:
                uuid = await mojang_uuid(name)
                if len(self.uuids) > 50_000:
                    self.uuids.clear()
                self.uuids[name.lower()] = (now, uuid)
            if uuid is None:
                return None
        return await self.hypixel.guild_of(uuid)

    def is_member(self, mc_name: str) -> bool:
        """Does a Discord member named mc_name (nickname or username) have a guild role?"""
        channel = self.get_channel(self.channels.get(GUILD_LINK, 0))
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

    def linked(self, room: Room) -> bool:
        """Is this guild's channel the one linked to Discord?"""
        if room.key == ROLE_GUILD:
            return True
        return bool(self.linked_guild) and room.name.lower() == self.linked_guild.lower()

    async def on_member_update(self, before, after):
        if self.hypixel is None and (before.roles != after.roles or before.display_name != after.display_name):
            self.irc.spawn(self.irc.recheck())

    async def on_member_remove(self, member):
        if self.hypixel is None:
            self.irc.spawn(self.irc.recheck())
        if member.guild == self.home():
            self.count_dirty = True

    async def recheck_loop(self):
        while True:
            await asyncio.sleep(RECHECK_EVERY)
            await self.irc.recheck()

    async def from_irc(self, target, nick: str, text: str, action: bool):
        """Posts an IRC message as a small card: the name, the message, the time."""
        if target == OPEN:
            channel = self.get_channel(self.channels.get(OPEN, 0))
        else:
            channel = self.get_channel(self.channels.get(GUILD_LINK, 0)) if self.linked(target) else None
        if channel is None:
            return
        song = NOW_PLAYING.fullmatch(text) if target == OPEN and not action else None
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
        if message.author.bot or message.webhook_id:
            return
        if message.channel.id == self.channels.get(OPEN):
            target = OPEN
        elif message.channel.id == self.channels.get(GUILD_LINK):
            room = next((r for r in self.irc.rooms.values() if self.linked(r)), None)
            if room is None:
                return  # nobody from the guild is in game
            target = room.key
        else:
            return
        name = clean(message.author.display_name)[:32] or "someone"
        parts = [message.clean_content] + [a.url for a in message.attachments]
        lines = [clean(l) for p in parts for l in p.splitlines() if clean(l)]
        out = [piece for l in lines for piece in split_bytes(f"<{name}> {l}", MAX_TEXT_BYTES)]
        if len(out) > MAX_LINES_PER_MESSAGE:
            out = out[:MAX_LINES_PER_MESSAGE]
            out[-1] = split_bytes(out[-1], MAX_TEXT_BYTES - 4)[0] + " ..."
        for line in out:
            if not self.irc.say(target, line):
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
    for kind, var in ((OPEN, "DISCORD_CHANNEL_ID"), (GUILD_LINK, "GUILD_DISCORD_CHANNEL_ID")):
        value = os.environ.get(var, "").strip()
        if value.isdigit():
            channels[kind] = int(value)
    if OPEN not in channels:
        sys.exit("DISCORD_CHANNEL_ID is not set")
    roles = {r.strip() for r in os.environ.get("GUILD_ROLES", "").split(",") if r.strip()}
    extras = {}
    for key, var in (("releases", "RELEASES_CHANNEL_ID"), ("new_people", "NEW_PEOPLE_CHANNEL_ID"),
                     ("now_playing", "NOW_PLAYING_CHANNEL_ID"), ("member_role", "MEMBER_ROLE_ID"),
                     ("member_count", "MEMBER_COUNT_CHANNEL_ID"), ("keep_days", "IRC_KEEP_DAYS")):
        value = os.environ.get(var, "").strip()
        if value.isdigit():
            extras[key] = int(value)
    Bridge(channels, roles, extras, os.environ.get("HYPIXEL_API_KEY", "").strip(),
           os.environ.get("GUILD_HYPIXEL_NAME", "").strip()).run(token, log_handler=None)


if __name__ == "__main__":
    main()
