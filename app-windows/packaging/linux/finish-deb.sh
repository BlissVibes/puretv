#!/usr/bin/env bash
# Finishes the .deb jpackage made (gradle :app-windows:packageReleaseDeb):
#  - adds what jpackage cannot know about: libVLC (playback), and as recommendations
#    a colour emoji font (chat) and libmpv (the optional mpv engine);
#  - lets each library from the 64-bit time_t transition ("libfoo2t64", which jpackage
#    copies from the build machine) also be satisfied by its older name ("libfoo2"),
#    so one package installs on Ubuntu 22.04, 24.04 and Debian 12;
#  - ships the menu entry as a plain file in /usr/share/applications. jpackage's scripts
#    run xdg-desktop-menu, which exits with an error where no system menu folder exists
#    (minimal installs, WSL, containers) and leaves the package half-installed. The entry
#    also names the window class, so docks group PureTV's windows under its icon.
# Usage: finish-deb.sh <jpackage.deb> <output.deb>
set -euo pipefail
in="$1"
out="$2"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

dpkg-deb -R "$in" "$work/pkg"
control="$work/pkg/DEBIAN/control"

awk '
  /^Depends:/ {
    sub(/^Depends:[ ]*/, "")
    n = split($0, deps, /,[ ]*/)
    line = ""
    for (i = 1; i <= n; i++) {
      d = deps[i]; gsub(/^[ ]+|[ ]+$/, "", d)
      if (d == "") continue
      if (d ~ /t64$/) { old = d; sub(/t64$/, "", old); d = d " | " old }
      line = line (line == "" ? "" : ", ") d
    }
    print "Depends: " line ", libvlc5, vlc-plugin-base"
    print "Recommends: fonts-noto-color-emoji, libmpv2 | libmpv1"
    next
  }
  # A blank line would start a second stanza, which dpkg-deb rejects once Homepage is added.
  /^[[:space:]]*$/ { next }
  { print }
' "$control" > "$control.new"
mv "$control.new" "$control"
grep -q '^Homepage:' "$control" || echo "Homepage: https://github.com/dhawal-ss/puretv" >> "$control"

entry="$(ls "$work"/pkg/opt/*/lib/*.desktop | head -n1)"
icon="$(sed -n 's/^Icon=//p' "$entry")"
mkdir -p "$work/pkg/usr/share/applications"
{
  grep -vE '^(MimeType=|Icon=)$' "$entry" | grep -v '^Icon='
  echo "Icon=$icon"
  echo "StartupWMClass=com-puretv-twitch-desktop-MainKt"
  echo "Keywords=twitch;stream;live;chat;"
} > "$work/pkg/usr/share/applications/puretv-for-twitch.desktop"
chmod 644 "$work/pkg/usr/share/applications/puretv-for-twitch.desktop"
sed -i 's|^xdg-desktop-menu install .*|command -v update-desktop-database >/dev/null 2>\&1 \&\& update-desktop-database -q /usr/share/applications \|\| true|' "$work/pkg/DEBIAN/postinst"
sed -i 's|^xdg-desktop-menu uninstall .*|:|' "$work/pkg/DEBIAN/prerm"

dpkg-deb --root-owner-group -Zxz -b "$work/pkg" "$out" >/dev/null
echo "Finished $out"
dpkg-deb -f "$out" Package Version Architecture Depends Recommends
