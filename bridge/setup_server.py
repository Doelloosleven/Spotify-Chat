#!/usr/bin/env python3
"""One-time layout for the Spotify Chat Discord server: categories, channels, a role, an emoji and the
welcome / rules posts. Run with DISCORD_TOKEN set, from the folder with avatar.png, emoji.png and banner.png.
The bot needs Manage Channels, Manage Roles and Manage Expressions in that server."""
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
    client = discord.Client(intents=discord.Intents.none())
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

        info = await guild.create_category("📌 INFO", overwrites=read_only, position=0)
        chat = await guild.create_category("💬 CHAT", position=1)
        support = await guild.create_category("🛠️ SUPPORT", position=2)
        voice = await guild.create_category("🔊 VOICE", position=3)

        welcome = await guild.create_text_channel("👋┃welcome", category=info, position=0,
                                                  topic="What Spotify Chat is and where to get it")
        rules = await guild.create_text_channel("📜┃rules", category=info, position=1, topic="Keep it chill")
        await guild.create_text_channel("📣┃releases", category=info, position=2,
                                        topic=f"New versions of Spotify Chat. Download: {RELEASES}")

        general = await client.fetch_channel(GENERAL_ID)
        await general.edit(name="💬┃general", category=chat, position=0, sync_permissions=True,
                           topic="Talk about anything")
        irc = await client.fetch_channel(IRC_ID)
        await irc.edit(name="🎵┃irc", category=chat, position=1, sync_permissions=True)
        await guild.create_text_channel("🎧┃now-playing", category=chat, position=2,
                                        topic="Share what you're listening to")
        await guild.create_text_channel("📸┃screenshots", category=chat, position=3,
                                        topic="Your overlay, your setup, your music")

        await guild.create_text_channel("🐛┃bug-reports", category=support, position=0,
                                        topic="What happened, your Minecraft version and Spotify Chat version")
        await guild.create_text_channel("💡┃suggestions", category=support, position=1,
                                        topic="Ideas for Spotify Chat")

        old_voice = await client.fetch_channel(VOICE_ID)
        await old_voice.edit(name="🎶 Listening Party", category=voice, position=0, sync_permissions=True)
        await guild.create_voice_channel("🔊 Hangout", category=voice, position=1)

        for cid in OLD_CATEGORY_IDS:  # the empty default categories
            try:
                old = await client.fetch_channel(cid)
                if not getattr(old, "channels", []):
                    await old.delete(reason="Replaced by the new layout")
            except discord.NotFound:
                pass

        dev = await guild.create_role(name="Developer", color=discord.Color(GREEN), hoist=True,
                                      reason="Spotify Chat developer")
        owner = await guild.fetch_member(guild.owner_id)
        await owner.add_roles(dev)

        with open("emoji.png", "rb") as f:
            await guild.create_custom_emoji(name="spotifycat", image=f.read())

        await guild.edit(system_channel=general,
                         default_notifications=discord.NotificationLevel.only_mentions,
                         explicit_content_filter=discord.ContentFilter.all_members)

        await welcome.send(embed=welcome_embed(),
                           files=[discord.File("banner.png"), discord.File("avatar.png")])
        await rules.send(embed=rules_embed())
        print("done")


asyncio.run(main())
