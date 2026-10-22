#!/usr/bin/env bash
# Fails when the core jar inside an app image still carries the placeholder Twitch client
# secret. Without the real one, refreshing a sign-in fails and users are signed out every
# few hours (issue #25). Usage: check-client-secret.sh <app image or bundle folder>
set -euo pipefail
jar="$(find "$1" -name 'core-desktop*.jar' | head -n1)"
if [ -z "$jar" ]; then echo "::error::core-desktop jar not found under $1"; exit 1; fi
# Read each listing in full before searching it: with pipefail, `unzip | grep -q` fails
# whenever grep stops early and unzip dies on the closed pipe, which would turn a match
# into a miss (and let a placeholder secret through).
listing="$(unzip -l "$jar")"
if ! grep -q 'com/puretv/twitch/core/api/TwitchSecrets.class' <<<"$listing"; then
  echo "::error::TwitchSecrets.class missing from $(basename "$jar")"; exit 1
fi
class_file="$(mktemp)"
trap 'rm -f "$class_file"' EXIT
unzip -p "$jar" com/puretv/twitch/core/api/TwitchSecrets.class > "$class_file"
if grep -aq 'PASTE_YOUR_CLIENT_SECRET_HERE' "$class_file"; then
  echo "::error::$(basename "$jar") carries the placeholder client secret. Sign-in refresh would fail for every user."
  exit 1
fi
echo "Client secret is present in $(basename "$jar")."
