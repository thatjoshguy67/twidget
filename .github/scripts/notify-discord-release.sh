#!/usr/bin/env bash

set -euo pipefail

: "${DISCORD_WEBHOOK_URL:?DISCORD_WEBHOOK_URL is required}"
: "${RELEASE_VERSION:?RELEASE_VERSION is required}"
: "${RELEASE_CHANNEL:?RELEASE_CHANNEL is required}"
: "${RELEASE_URL:?RELEASE_URL is required}"

case "$RELEASE_CHANNEL" in
  stable)
    release_label="Stable release"
    color=5763719
    ;;
  beta)
    release_label="Beta release"
    color=16753920
    ;;
  *)
    echo "Unsupported release channel: $RELEASE_CHANNEL" >&2
    exit 1
    ;;
esac

notes_file=${RELEASE_NOTES_FILE:-release-notes.md}
if [ -s "$notes_file" ]; then
  # Discord limits embed descriptions to 4,096 characters. Leave room for the
  # link appended below, and make the full release notes one click away.
  description=$(jq -Rs '.[0:3500]' < "$notes_file")
else
  description='"Release notes are available on GitHub."'
fi

payload_file=$(mktemp)
response_file=$(mktemp)
trap 'rm -f "$payload_file" "$response_file"' EXIT

jq -n \
  --arg content "${DISCORD_RELEASE_MENTION:-}" \
  --arg title "Twidget v$RELEASE_VERSION" \
  --arg release_label "$release_label" \
  --arg release_url "$RELEASE_URL" \
  --argjson color "$color" \
  --argjson description "$description" \
  '{
    username: "Twidget Updates",
    content: $content,
    allowed_mentions: {
      parse: ["everyone", "roles"],
      users: []
    },
    embeds: [{
      title: $title,
      url: $release_url,
      description: $description,
      color: $color,
      fields: [{
        name: "Channel",
        value: $release_label,
        inline: true
      }],
      footer: {
        text: "Download and read the full notes on GitHub"
      }
    }]
  }
  | if $content == "" then del(.content) else . end' > "$payload_file"

case "$DISCORD_WEBHOOK_URL" in
  *\?*) webhook_url="${DISCORD_WEBHOOK_URL}&wait=true" ;;
  *) webhook_url="${DISCORD_WEBHOOK_URL}?wait=true" ;;
esac

curl \
  --fail-with-body \
  --retry 3 \
  --retry-all-errors \
  --show-error \
  --silent \
  --header 'Content-Type: application/json' \
  --data-binary "@$payload_file" \
  --output "$response_file" \
  "$webhook_url"

jq -e '.id and .channel_id' "$response_file" >/dev/null
