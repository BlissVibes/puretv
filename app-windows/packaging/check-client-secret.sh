#!/usr/bin/env bash
# Fails when the core jar inside an app image still carries the placeholder Twitch client
# secret. Without the real one, refreshing a sign-in fails and users are signed out every
# few hours (issue #25). Usage: check-client-secret.sh <app image or bundle folder>
set -euo pipefail
jar="$(find "$1" -name 'core-desktop*.jar' | head -n1)"
if [ -z "$jar" ]; then echo "::error::core-desktop jar not found under $1"; exit 1; fi
if ! unzip -l "$jar" | grep -q 'com/puretv/twitch/core/api/TwitchSecrets.class'; then
  echo "::error::TwitchSecrets.class missing from $(basename "$jar")"; exit 1
fi
if unzip -p "$jar" com/puretv/twitch/core/api/TwitchSecrets.class | grep -aq 'PASTE_YOUR_CLIENT_SECRET_HERE'; then
  echo "::error::$(basename "$jar") carries the placeholder client secret. Sign-in refresh would fail for every user."
  exit 1
fi
echo "Client secret is present in $(basename "$jar")."
