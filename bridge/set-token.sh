#!/bin/sh
# Saves the Discord bot token for the bridge (root only). Asks for it without showing it,
# or reads it from stdin when piped (set-token.ps1 does that from a paste box on Windows).
set -eu
if [ -t 0 ]; then
    printf 'Paste the Discord bot token (it stays hidden), then press Enter: '
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
printf 'DISCORD_TOKEN=%s\n' "$token" > /etc/spotify-chat-bridge/token.env
echo "Saved. The bridge restarts with the new token."
systemctl restart spotify-chat-bridge 2>/dev/null || true
