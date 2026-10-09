#!/usr/bin/env python3
"""One-time layout for a fresh Spotify Chat Discord server: categories, channels, roles, an emoji, the
welcome / rules posts and an invite. Run with DISCORD_TOKEN set, from the folder with avatar.png, emoji.png
(128x128) and banner.png. The bot needs Manage Server, Channels, Roles and Expressions in that server and the
Server Members intent. It prints the ids that go into bridge.env."""
import asyncio
import os

import discord

GUILD_ID = 1558095333987188756
GENERAL_ID = 1558095335589548074
IRC_ID = 1558095816508448778
VOICE_ID = 1558095335589548075
OLD_CATEGORY_IDS = (1558095335589548072, 1558095335589548073)

GREEN = 0x1ED760
RELEASES = "https://github.com/Doelloosleven/Spotify-Chat/releases/latest"
GITHUB = "https://github.com/Doelloosleven/Spotify-Chat"


def welcome_embed() -> discord.Embed:
    e = discord.Embed(
        title="Spotify Chat",
        url=GITHUB,
        color=GREEN,
        description="A Fabric mod that shares what you're listening to on Spotify in Minecraft chat. "
                    "Type `!spotify` and everyone sees your song.\n​",
    )
    e.add_field(name="Get it", value=f"[Latest release]({RELEASES}) for Minecraft 26.1.2, 26.2 and 26.3. "
                                     "Needs Fabric API. No Spotify login needed.", inline=False)
    e.add_field(name="IRC", value=f"<#{IRC_ID}> is linked to the in-game IRC. Press `[` in Minecraft to chat, "
                                  "messages show up on both sides.", inline=False)
    e.add_field(name="Commands",
                value="`!spotify` / `!music` share your song\n"
                      "`/gc` `/pc` `/cc !spotify` guild, party or co-op chat\n"
                      "`!jam` share your Spotify Jam\n"
                      "`[` chat in IRC, `F4` settings", inline=False)
    e.set_thumbnail(url="attachment://avatar.png")
    e.set_image(url="attachment://banner.png")
    e.set_footer(text="Open source on GitHub")
    return e


def rules_embed() -> discord.Embed:
    e = discord.Embed(title="Rules", color=GREEN, description=(
        "**1.** Be chill. No hate, harassment or slurs.\n"
        "**2.** No spam, ads or DM advertising.\n"
        "**3.** Keep it SFW.\n"
        f"**4.** <#{IRC_ID}> also goes into Minecraft, so keep it clean there.\n"
        "**5.** Bugs and ideas go in their channels, with your Minecraft version.\n"
        "**6.** Follow Discord's Terms of Service."))
    return e


async def main():
    intents = discord.Intents.none()
    intents.members = True  # to give everyone already in the server the Listener role
    client = discord.Client(intents=intents)
    async with client:
        await client.login(os.environ["DISCORD_TOKEN"])
        guild = await client.fetch_guild(GUILD_ID)
        me = await guild.fetch_member(client.user.id)
        everyone = guild.default_role
        read_only = {
            everyone: discord.PermissionOverwrite(send_messages=False, create_public_threads=False,
                                                  create_private_threads=False, add_reactions=True),
            me: discord.PermissionOverwrite(send_messages=True, embed_links=True, attach_files=True),
        }
        humans = [m async for m in guild.fetch_members(limit=None) if not m.bot]

        top = await guild.create_category("🎧 SPOTIFY CHAT", position=0)
        info = await guild.create_category("📌 INFO", overwrites=read_only, position=1)
        chat = await guild.create_category("💬 CHAT", position=2)
        support = await guild.create_category("🛠️ SUPPORT", position=3)
        voice = await guild.create_category("🔊 VOICE", position=4)

        # Nobody joins or talks in the counter. The bot needs Connect too: without it Discord hides a voice
        # channel from the bot completely, and it couldn't rename it anymore.
        counter = await guild.create_voice_channel(f"👥 Members: {len(humans)}", category=top, overwrites={
            everyone: discord.PermissionOverwrite(view_channel=True, connect=False, send_messages=False),
            me: discord.PermissionOverwrite(view_channel=True, connect=True, manage_channels=True)})
        welcome = await guild.create_text_channel("👋┃welcome", category=info, position=0,
                                                  topic="What Spotify Chat is and where to get it")
        new_people = await guild.create_text_channel("🎉┃new-people", category=info, position=1,
                                                     topic="Say hi to everyone who just joined")
        rules = await guild.create_text_channel("📜┃rules", category=info, position=2, topic="Keep it chill")
        releases = await guild.create_text_channel("📣┃releases", category=info, position=3,
                                                   topic=f"New versions of Spotify Chat. Download: {RELEASES}")

        now_playing = await guild.create_text_channel(  # the bot's live song feed: read-only like INFO
            "🎧┃now-playing", category=chat, position=2, overwrites=read_only,
            topic="What everyone is listening to in game. Type !spotify in the IRC chat ([) to show up here")
        await guild.create_text_channel("📸┃screenshots", category=chat, position=3,
                                        topic="Your overlay, your setup, your music")
        await guild.create_text_channel("🐛┃bug-reports", category=support, position=0,
                                        topic="What happened, your Minecraft version and Spotify Chat version")
        await guild.create_text_channel("💡┃suggestions", category=support, position=1,
                                        topic="Ideas for Spotify Chat")
        hangout = await guild.create_voice_channel("🔊 Hangout", category=voice, position=1)

        # Discord's default channels move into the new categories. The API moves one channel to another
        # category per call, and editing category + position together doesn't stick without a cache.
        for cid, name, parent in ((GENERAL_ID, "💬┃general", chat), (IRC_ID, "🎵┃irc", chat),
                                  (VOICE_ID, "🎶 Listening Party", voice)):
            channel = await client.fetch_channel(cid)
            await channel.edit(name=name)
            await client.http.bulk_channel_update(GUILD_ID, [{"id": cid, "parent_id": parent.id,
                                                              "lock_permissions": True}])
        await client.http.bulk_channel_update(GUILD_ID, [
            {"id": GENERAL_ID, "position": 0}, {"id": IRC_ID, "position": 1},
            {"id": VOICE_ID, "position": 0}, {"id": hangout.id, "position": 1}])
        for cid in OLD_CATEGORY_IDS:  # the default categories, empty now
            try:
                await (await client.fetch_channel(cid)).delete(reason="Replaced by the new layout")
            except discord.NotFound:
                pass

        dev = await guild.create_role(name="Developer", color=discord.Color(GREEN), hoist=True)
        await (await guild.fetch_member(guild.owner_id)).add_roles(dev)
        listener = await guild.create_role(name="🎧 Listener", color=discord.Color(0x86EFAC), hoist=True)
        for m in humans:
            await m.add_roles(listener)

        with open("emoji.png", "rb") as f:
            await guild.create_custom_emoji(name="spotifycat", image=f.read())

        # Join messages off: the bridge posts its own welcome card in new-people
        await guild.edit(system_channel=new_people,
                         system_channel_flags=discord.SystemChannelFlags(
                             join_notifications=False, premium_subscriptions=True,
                             guild_reminder_notifications=False, join_notification_replies=False),
                         default_notifications=discord.NotificationLevel.only_mentions,
                         explicit_content_filter=discord.ContentFilter.all_members,
                         verification_level=discord.VerificationLevel.low)

        await welcome.send(embed=welcome_embed(),
                           files=[discord.File("banner.png"), discord.File("avatar.png")])
        await rules.send(embed=rules_embed())
        invite = await new_people.create_invite(max_age=0, max_uses=0, unique=False)

        print("Add to /etc/spotify-chat-bridge/bridge.env:")
        print(f"RELEASES_CHANNEL_ID={releases.id}")
        print(f"NEW_PEOPLE_CHANNEL_ID={new_people.id}")
        print(f"NOW_PLAYING_CHANNEL_ID={now_playing.id}")
        print(f"MEMBER_ROLE_ID={listener.id}")
        print(f"MEMBER_COUNT_CHANNEL_ID={counter.id}")
        print("Invite:", invite.url)


asyncio.run(main())
