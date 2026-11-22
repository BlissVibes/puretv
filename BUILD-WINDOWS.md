# Building the Windows installer (this fork)

The Windows installer can only be built on Windows. One script does all of it.
It has not been run on a real Windows PC yet, so the first run is also its test.

## First time

Open PowerShell and run these one at a time.

1. Install Git (skip if you have it), then close and reopen PowerShell:

       winget install Git.Git

2. Get the code. Keep the path short; the Windows build tools fail on long paths:

       git clone --branch macos https://github.com/BlissVibes/puretv.git C:\puretv

3. Build. `-InstallDeps` installs JDK 17, VLC and Inno Setup with winget if they are
   missing; Windows may ask you to allow each one:

       powershell -ExecutionPolicy Bypass -File C:\puretv\app-windows\packaging\windows\build-windows.ps1 -InstallDeps

The first build takes about 10 to 15 minutes because it downloads the build tools.

## Result

    C:\puretv\app-windows\build\installer\PureTV-Setup-<version>.exe

Double-click it to install. Windows SmartScreen will warn because the installer is not
code-signed: click **More info**, then **Run anyway**.

## Building again after changes

    git -C C:\puretv pull
    powershell -ExecutionPolicy Bypass -File C:\puretv\app-windows\packaging\windows\build-windows.ps1

## If it stops with an error

Copy the last 20 or so lines of the PowerShell output. The usual causes:

- **JDK 17 / VLC / Inno Setup not found**: run again with `-InstallDeps`, or install the
  one it names (the error prints the winget command).
- **vlc-cache-gen.exe is missing**: VLC was installed from the .zip. Install it with
  `winget install VideoLAN.VLC` instead.
- **libmpv was not bundled** (a warning, not an error): the optional mpv playback backend
  is left out. Normal VLC playback is unaffected. `-SkipMpv` skips the attempt.

## Things to know

- **Untested on Windows.** The fork's changes (corner player while browsing, bars that
  hide in the window, fullscreen with chat, the chat row layout, the player bar) were
  built and tested on macOS only. Check those first.
- **It installs over upstream PureTV.** The installer has the same app identity as the
  official one, so it replaces an existing PureTV install on the same PC and keeps its
  settings.
- **Sign-in drops about every 4 hours.** The build carries no Twitch client secret, so
  it cannot refresh a sign-in. Streams play without one. To fix it, put a secret in
  `secrets.properties` at the repo root (`twitch.client.secret=...`) or in the
  `TWITCH_CLIENT_SECRET` environment variable before building; it has to belong to the
  Twitch app whose client id is in `TwitchConfig.kt`.
- **Updates come from this fork**, not from upstream: the in-app updater reads
  `BlissVibes/puretv` releases, so an upstream release cannot replace this build.

## Building on GitHub instead

With no Windows PC at hand, push a tag that starts with `win-` and GitHub builds the
same script on a Windows runner (`.github/workflows/windows-fork.yml`); the installer
is attached to the run as an artifact. No branch push starts a build.

    git tag win-1.17.0-1
    git push fork win-1.17.0-1
