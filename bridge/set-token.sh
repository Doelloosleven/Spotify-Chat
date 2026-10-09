#!/bin/sh
# Saves a secret for the bridge (root only): the Discord bot token, or with "hypixel" the Hypixel API key.
# Asks for it without showing it, or reads it from stdin when piped (set-token.ps1 does that from a paste box).
set -eu
case "${1:-discord}" in
    discord) what="Discord bot token"; var=DISCORD_TOKEN; file=token.env ;;
    hypixel) what="Hypixel API key"; var=HYPIXEL_API_KEY; file=hypixel.env ;;
    *) echo "Usage: spotify-chat-bridge-token [discord|hypixel]"; exit 2 ;;
esac
if [ -t 0 ]; then
    printf 'Paste the %s (it stays hidden), then press Enter: ' "$what"
    stty -echo
    read -r token || true
    stty echo
    echo
else
    read -r token || true
fi
token=$(printf '%s' "$token" | tr -d '\r\n\t ')
[ -n "$token" ] || { echo "Nothing pasted, nothing changed."; exit 1; }
umask 077
mkdir -p /etc/spotify-chat-bridge
printf '%s=%s\n' "$var" "$token" > "/etc/spotify-chat-bridge/$file"
echo "Saved. The bridge restarts with the new $what."
systemctl restart spotify-chat-bridge 2>/dev/null || true
