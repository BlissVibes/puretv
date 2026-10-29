# PureTV on macOS

This fork builds the desktop app (the `app-windows` module, despite the name) as a
native Apple Silicon `.app`. The shared `core` module and all of the desktop UI are
unchanged; the port touches the places where the two systems differ.

## What differs from the Windows build

| Area | Windows | macOS |
|---|---|---|
| Data folder | `%APPDATA%\PureTwitch` | `~/Library/Application Support/PureTV` |
| Caches | `%LOCALAPPDATA%\PureTwitch` | `~/Library/Caches/PureTV` |
| Video engine | VLC (bundled DLLs) or libmpv | VLC only (bundled arm64 `lib/` + `plugins/`) |
| How video reaches the window | libVLC draws into a native window handle | libVLC decodes into a buffer the app paints (`MacVideoSurfacePanel`) |
| Token key seed | `MachineGuid` from the registry | `IOPlatformUUID` from `ioreg` |
| Self-update | Signed installer from GitHub Releases | Disabled: rebuild from source |
| Start at login | Run registry key | Not available (would be a LaunchAgent) |
| Window drag / snap helpers | user32 calls | Not used; the plain fallbacks apply |
| Packaging | Inno Setup `.exe` | `.dmg` from jpackage (`packageDmg`) |

Everything else (ad blocking, chat, emotes, follows, multi-stream, VODs, themes)
is the same code.

## Build it

Prerequisites: a JDK 17 that is NOT Homebrew's `openjdk@17` (Compose refuses to
package with it, see compose-multiplatform issue 3107). Amazon Corretto 17 works and
installs without admin rights:

```bash
mkdir -p ~/Library/Java/JavaVirtualMachines
curl -sSL https://corretto.aws/downloads/latest/amazon-corretto-17-aarch64-macos-jdk.tar.gz | tar -xz -C ~/Library/Java/JavaVirtualMachines
export JAVA_HOME=~/Library/Java/JavaVirtualMachines/amazon-corretto-17.jdk/Contents/Home
```

An Apple Silicon VLC is needed for the bundle. If the VLC in `/Applications` is the
Intel build (check with `lipo -archs /Applications/VLC.app/Contents/MacOS/lib/libvlc.dylib`),
download the arm64 `.dmg` from https://www.videolan.org/vlc/download-macosx.html, open
it, and point `VLC_APP` at the mounted app. No Android SDK is needed: the Android and
TV modules are skipped automatically when none is found.

```bash
export VLC_APP=/Volumes/VLC\ media\ player/VLC.app   # or /Applications/VLC.app if it is arm64

./gradlew :app-windows:bundleVlcMac          # copies lib/ + plugins/ into resources/macos-arm64/vlc/
./gradlew :app-windows:packageDmg            # -> app-windows/build/compose/binaries/main/dmg/
```

For a development run without packaging:

```bash
./gradlew :app-windows:run
```

## Sign in

Twitch's token refresh needs the app's client secret, which the upstream repo does
not ship. To sign in with your own build, register an application at
https://dev.twitch.tv/console (category "Application Integration", OAuth redirect
`http://localhost:3000`), then put its secret in a gitignored `secrets.properties`
at the repo root:

```
twitch.client.secret=YOUR_SECRET
```

and replace `CLIENT_ID` in `core/src/commonMain/kotlin/com/puretv/twitch/core/api/TwitchConfig.kt`
with your app's client id. Browsing and watching work signed out; following and
chat need the sign-in.

## First launch

The `.app` is not code-signed or notarized. macOS will refuse to open it on the
first double-click: right-click the app, choose **Open**, and confirm once. To sign
it with a Developer ID, fill in the commented `signing { }` block in
`app-windows/build.gradle.kts`.

## Known limitations

- Go-live alerts rely on tray balloon notifications, which macOS does not show;
  the tray icon itself works.
- The mpv engine and GPU upscaling (Anime4K) are Windows-only.
- A second launch brings the running copy forward through a request file rather
  than a window-focus call; it can take up to a second.
