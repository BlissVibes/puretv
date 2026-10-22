#!/usr/bin/env bash
# Launches an installed or packaged PureTV on a channel that is live right now and checks
# that it stays up and plays video. Used by the macOS and Linux CI jobs.
#
# Usage: launch-smoke.sh <launcher> <data-dir> <out-dir>
#   launcher  the app's executable (e.g. ".../PureTV for Twitch.app/Contents/MacOS/PureTV for Twitch")
#   data-dir  where this OS keeps PureTV's files (timing.log, crash.log)
#   out-dir   where logs and a screenshot are written
#
# Passes when, within 120 s: the stream starts playing, libVLC opens a video output, and
# (on macOS, where Java paints the frames) a frame is painted; and the app is still
# running 15 s later, and it then quits within 20 s when the OS asks it to. The screenshot
# is for a human to look at; it is not judged here.
set -uo pipefail
launcher="$1"
data="$2"
out="$3"
mkdir -p "$out"

# The most-watched live channel, from Twitch's public GraphQL endpoint (the same one
# and client id the app uses for anonymous browsing).
channel="${SMOKE_CHANNEL:-}"
[ -n "$channel" ] || channel="$(curl -fsS https://gql.twitch.tv/gql \
  -H 'Client-ID: kimne78kx3ncx6brgo4mv6wki5h1ko' \
  -d '{"query":"{ streams(first: 1) { edges { node { broadcaster { login } } } } }"}' \
  | sed -n 's/.*"login":"\([^"]*\)".*/\1/p')"
if [ -z "$channel" ]; then echo "::error::Could not find a live channel"; exit 1; fi
echo "Live channel: $channel"

rm -f "$data/timing.log" "$data/crash.log"
"$launcher" --channel "$channel" > "$out/app.log" 2>&1 &
pid=$!

need=("stream.first_frame" "player.video_output")
[ "$(uname)" = "Darwin" ] && need+=("player.frame_painted")

ok=0
for _ in $(seq 1 120); do
  if ! kill -0 "$pid" 2>/dev/null; then echo "::error::PureTV exited early"; break; fi
  all=1
  for n in "${need[@]}"; do grep -q "$n" "$data/timing.log" 2>/dev/null || all=0; done
  if [ "$all" = 1 ]; then ok=1; break; fi
  sleep 1
done

if [ "$ok" = 1 ]; then
  sleep 15
  kill -0 "$pid" 2>/dev/null || { echo "::error::PureTV exited after starting playback"; ok=0; }
fi

if [ "$(uname)" = "Darwin" ]; then
  screencapture -x "$out/screen.png" || true
else
  import -window root "$out/screen.png" || true
fi

cp "$data/timing.log" "$out/" 2>/dev/null || true
cp "$data/crash.log" "$out/" 2>/dev/null || true
# A native crash leaves the JVM's hs_err report in the working folder.
cp hs_err_pid*.log "$out/" 2>/dev/null || true
echo "--- timing.log"; cat "$data/timing.log" 2>/dev/null
# Quit the way a user's system does and require a clean exit: on macOS a quit Apple event
# (what Cmd+Q, the Dock and logout send, handled by MacAppEvents), elsewhere SIGTERM (what a
# session logout sends; the shutdown hook flushes the stores).
if [ "$(uname)" = "Darwin" ]; then
  osascript -e 'tell application id "com.puretv.twitch" to quit' >/dev/null 2>&1 || kill "$pid" 2>/dev/null
else
  kill -TERM "$pid" 2>/dev/null
fi
quit=0
for _ in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || { quit=1; break; }; sleep 1; done
if [ "$quit" = 1 ]; then echo "Quit cleanly"; else echo "::error::PureTV still running 20 s after being asked to quit"; kill -9 "$pid" 2>/dev/null; ok=0; fi

if [ "$ok" != 1 ]; then
  echo "::error::Smoke test failed: needed ${need[*]}"
  echo "--- app.log (tail)"; tail -60 "$out/app.log"
  exit 1
fi
echo "Smoke test passed on $channel"
