# PureTV desktop: macOS and Linux readiness

Status of the desktop app (`app-windows`, Kotlin + Compose Multiplatform 1.7, JDK 17,
vlcj 4.8.3 + libVLC 3.0.23, optional libmpv) on Windows, macOS and Linux.

Branch: `cross-platform-readiness`. Started 2026-10-22 from `main` at `98cbeee`
(Windows 1.13.0 released).

Every claim below is marked with how it is known:

- **Verified**: a command was run, or the app was run, and the result is quoted.
- **Code**: read in the source, not executed.
- **Unverified**: not run on the target OS. Never counted as a pass.

## 1. Baseline (before any change)

| Check | Command | Result |
|---|---|---|
| Core unit tests (JVM) | `gradlew :core:desktopTest` | Verified: 561 tests, 0 failures (Windows 11) |
| Desktop unit tests | `gradlew :app-windows:test` | Verified: 444 tests, 0 failures (Windows 11) |
| Linux CI build | `ci.yml` on `ubuntu-latest` runs `:core:allTests :app-windows:test :app-windows:createDistributable` | Code: already green on `main` CI; produces a Linux app image nobody ships |
| macOS build | none | No macOS job, target or artifact exists |
| Release | `release.yml` | Windows only: Inno Setup `.exe` + Ed25519 `.sig` |

## 2. Architecture facts that decide the plan

- The desktop app is one JVM module. The OS-specific code is a thin layer:
  data folder, token key seed, video surface, native window chrome, single-instance
  focus, start-at-login, updater, packaging.
- **Video is drawn by the native engine into a heavyweight AWT `Canvas`.** VLC gets it
  through `videoSurfaces().newVideoSurface(component)` (`VlcEngine.kt:65`). mpv gets
  the same Canvas's handle as `wid` (`MpvPlayer.kt:208`).
  - Windows: an HWND. Works (shipping).
  - Linux: an X11 window id. Java AWT runs on X11 or XWayland, so this works in
    principle. Unverified.
  - **macOS: does not work.** Since Java 7 an AWT Canvas has no NSView that libVLC or
    libmpv can draw into. vlcj's documented answer is a callback surface (libVLC decodes
    into a buffer, Java paints it). mpv has no equivalent here.
- Sign-in uses the Twitch device-code grant (no localhost redirect or URL scheme to
  register), so it ports without protocol handlers.
- The stream proxy (`LocalStreamProxy`, Ktor/Netty on 127.0.0.1) is pure JVM.

## 3. Findings

Severity: P0 critical, P1 high, P2 medium, P3 low. "Confirmed" means shown by code
reading or execution; "Hypothesis" means likely but not shown.

### P1: platform-release blockers

| # | Finding | Evidence | Impact | Fix | Validation |
|---|---|---|---|---|---|
| X1 | **No macOS video.** | Confirmed (code): video binds to a heavyweight Canvas handle (`VlcPlayer.kt:171`, `MpvPlayer.kt:208`). vlcj's macOS discovery exists, but an embedded surface cannot work there. | Black player on every Mac. | On macOS, VLC renders through a callback surface into a Swing panel inside the same `SwingPanel`. mpv is hidden on macOS. | macOS CI build plus a launch smoke test. Playback on a real Mac stays Unverified. |
| X2 | **No macOS or Linux packaging.** | Confirmed: `targetFormats(Msi, Exe)` only (`build.gradle.kts:226`); `bundleVlc` reads `C:/Program Files/VideoLAN`. | Nothing to ship. | Per-OS formats (`.dmg`, `.deb`), a macOS VLC bundle taken from VLC.app, and a Linux `.deb` that depends on the distro's libVLC. | Package on native CI runners. Install the `.deb` in WSL Ubuntu. |
| X3 | **Data lands in `~/PureTwitch`** on macOS and Linux. | Confirmed: 15 copies of `System.getenv("APPDATA") ?: user.home` (grep). | A visible folder in home. The cache lives with the settings. Breaks platform conventions. | One `AppDirs` helper: Windows path unchanged, macOS `~/Library/Application Support/PureTwitch` and `~/Library/Caches/PureTwitch`, Linux `$XDG_CONFIG_HOME/PureTwitch` and `$XDG_CACHE_HOME/PureTwitch`. | Unit tests with the OS and env injected. |
| X4 | **libmpv and libVLC are looked up by Windows file names.** | Confirmed: `libvlc.dll` (`VlcEngine.kt:98`), `libmpv-2.dll` and `Native.load("libmpv-2")` (`MpvPlayer.kt:38,504`). | The bundled VLC is never found on macOS. mpv never loads on Linux (`libmpv.so.2`). | Per-OS names. | Unit tests plus a WSL run. |
| X5 | **Multi-stream tiles request VLC's `directsound` output**, which exists only on Windows. | Confirmed: `VlcEngine.kt:17`, `WindowPlayback.kt:51`. | Unknown off Windows: VLC may fall back to its default output, or a tile may stay silent. | Per-OS output: `directsound` on Windows, `pulse` on Linux (also served by PipeWire), the default on macOS. | WSL run. Per-tile volume by ear stays Unverified. |
| X6 | **The updater would offer a Windows installer to Mac and Linux users.** | Confirmed: `installerAsset()` accepts `.msi`/`.exe` only and the install path runs `wscript.exe` (`UpdateManager.kt:342`). | A Mac user clicks Update, downloads an `.exe`, then fails. | Choose the asset by OS. Outside Windows the update is notify-only and opens the release page. No silent install. | Unit tests on the selector and on the banner state. |
| X7 | **The undecorated window has no native chrome on macOS or Linux.** | Confirmed: `undecorated = true` (`main.kt:288`). Snap and drag go through `user32` (`WindowsNative.kt`). | Linux: no resize edges. macOS: no traffic lights or native fullscreen. Both get Windows-style buttons. | macOS: native frame with a transparent title bar and traffic lights; our window buttons are hidden. Linux: native WM decorations; our buttons are hidden. Fullscreen uses the native mode off Windows. | WSL GUI run (Linux). macOS Unverified beyond launch. |

### P2: noticeable gaps

| # | Finding | Evidence | Fix |
|---|---|---|---|
| X8 | The "Start with Windows" toggle shows whenever `jpackage.app-path` is set, so on macOS and Linux it appears and silently fails (registry calls). | Code: `WindowsStartup.kt` | Per-OS start at login: Windows Run key (unchanged), Linux `~/.config/autostart/*.desktop`, macOS LaunchAgent plist. The label is per OS. |
| X9 | The single-instance focus calls `User32` on every OS. It is caught by `runCatching`, so not a crash, but it is wasted work and untidy. | Code: `SingleInstance.kt:192` | Guard by OS. The file-based activation request already works everywhere. |
| X10 | Shortcuts use Ctrl on macOS (Ctrl+N new window). | Code: `PlayerShortcuts.kt` | Accept Cmd on macOS. |
| X11 | Diagnostics labels the OS line "Windows:", and Settings copy says "Windows" on every OS. | Code: `Diagnostics.kt:49`, `SettingsContent.kt` | Neutral wording. |
| X12 | Reduced motion is read only from Windows (`SPI_GETCLIENTAREAANIMATION`). | Code: `SystemAnimations.kt` | macOS `reduceMotion` default and GNOME `enable-animations` (P3, best effort). |
| X13 | The CI matrix is Linux-only, so a Windows-only or macOS-only compile or test break is invisible until release. | Code: `ci.yml` | Add macOS and Windows test and package jobs. |
| X14 | The release workflow builds Windows only, so no macOS or Linux artifact, checksum or provenance exists. | Code: `release.yml` | Native macOS-14 and Ubuntu jobs. `draft-release` needs all of them. SHA256SUMS. Each job checks for the client secret. |

### P3

- X15: the Linux tray depends on the desktop. GNOME has no XEmbed tray without an
  extension. The code already degrades (no tray means no alerts and minimize stays a plain
  minimize), and Settings says so. The wording is updated to not name Windows.
- X16: the `libmpv` download in `release.yml` takes the latest nightly (not reproducible).
  This was already noted in the workflow. Unchanged here.

## 4. What changed (branch `cross-platform-readiness`)

| Finding | Fix | Files | Tests |
|---|---|---|---|
| X3 data folders | `AppDirs`: Windows paths byte-for-byte unchanged; macOS `~/Library/...`; Linux XDG | `platform/AppDirs.kt` + 15 call sites | `AppDirsTest` (6) |
| X4 library names | `NativeLibs`: per-OS libVLC bundle layout, libmpv names (`libmpv.so.2`, then `.so.1` for Ubuntu 22.04) | `player/NativeLibs.kt`, `VlcEngine`, `MpvPlayer` | `NativeLibsTest` (9) |
| X5 tile audio | DirectSound on Windows, PulseAudio on Linux, VLC default on macOS | `NativeLibs.tileAudioOutput` | `NativeLibsTest` |
| X1 macOS video | libVLC frame callbacks (RV32) painted by `VideoCanvas`; mpv not offered on macOS | `player/VideoCanvas.kt`, `VlcEngine.newVideoSurface` | `VideoCanvasTest` (4) |
| X6 updater | Asset per OS and CPU (`.exe`, `arm64 .dmg`, `amd64 .deb`); macOS/Linux open the release page | `update/UpdateModels.kt`, `UpdateManager`, banner, Settings | `PlatformInstallerAssetTest` (6) |
| X7 window chrome | Windows unchanged; macOS system frame with transparent title bar; Linux WM frame; system fullscreen off Windows; macOS minimizes to the Dock | `platform/WindowChrome.kt`, `main.kt`, `App.kt`, `AppShellController`, secondary and popped windows | Linux run (below) |
| X8 start at login | Windows Run key (unchanged), Linux XDG autostart, macOS LaunchAgent; label per OS | `platform/LoginStartup.kt` | `LoginStartupTest` (5) |
| X9 single instance | User32 focus calls only reached on Windows (they fail harmlessly elsewhere) | `SingleInstance` (unchanged, caught) | existing `SingleInstanceTest` |
| X10 shortcuts | Cmd on macOS for app shortcuts and their hints; Cmd+Q and the Dock go through the app's quit | `platform/ShortcutKey.kt`, `platform/MacAppEvents.kt` | `MotionPreferenceTest` |
| X11 wording | "OS:" in diagnostics; tray and startup copy no longer say Windows | `Diagnostics`, `SettingsContent` | `DiagnosticsTest` |
| X12 reduce motion | macOS Reduce motion, GNOME animations switch | `SystemAnimations` | `MotionPreferenceTest` |
| X13/X14 CI and release | `desktop-platforms.yml` (Windows tests; macOS 14 .dmg, install, launch; Ubuntu 22.04 .deb, apt install, launch, removal), called by `release.yml` with the client secret, jar check, tag check, SHA256SUMS | `.github/workflows/*`, `packaging/` | CI runs (below) |

Found and fixed while testing (not in the first audit):

| # | Severity | Finding | Evidence | Fix |
|---|---|---|---|---|
| X17 | P1 | The `.deb` would not install on minimal systems: jpackage's `postinst` runs `xdg-desktop-menu`, which exits 3 without a system menu folder and leaves the package half-configured. | Verified (WSL): `dpkg: error processing package puretv-for-twitch (--configure)... exit status 3` | `finish-deb.sh` ships the menu entry as a file and runs `update-desktop-database`. Verified: `install ok installed`, `desktop-file-validate` clean, removal leaves nothing. |
| X18 | P1 | The `.deb` pinned Ubuntu 24.04's `t64` library names (`libasound2t64`), so it would not install on 22.04 or Debian 12, and declared no libVLC dependency. | Verified: `dpkg-deb -I` of the jpackage output | `t64` names get their old name as an alternative; `libvlc5, vlc-plugin-base` added; emoji font and libmpv recommended. |
| X19 | P2 | Keyboard: Tab stopped at the rail's last item on Home; the hero's Watch now and everything after a video panel were unreachable. All OSes. | Verified (WSL Tab walk, before/after) | Video `SwingPanel` excluded from focus traversal. |
| X20 | P2 | The stream top bar squeezed the channel name to one letter at the default 1280x720 window with chat open, and the viewer count spilled out. All OSes. | Verified: macOS CI screenshot, Linux screenshot | Narrow bars drop button labels (names kept for screen readers); subtitle ellipsizes as one line. Verified on Linux. |
| X21 | P2 | Sign-in said "We opened your browser" even when nothing opened (Java's launcher is often missing on Linux). | Code + Linux run | `xdg-open` / `open` fallback (https only); wording follows the result. `BrowserCommandTest`. |
| X22 | P2 | Chat emoji, and Japanese/Chinese/Korean names and messages, showed as boxes on Linux without the fonts (minimal installs; Ubuntu Desktop has them). | Verified (WSL and CI 22.04 screenshots) | `.deb` recommends `fonts-noto-color-emoji` and `fonts-noto-cjk`; emoji verified rendered after install. |
| X23 | P2 | mpv on Linux played sound over an empty pane where no GL context could be made (WSLg). | Verified (WSL) | `x11` software output as last resort on Linux. Verified: picture. |
| X25 | P1 | **Native crash on Ubuntu 22.04 about a second after a stream opens.** Skia (libskiko) ships a static libstdc++ and exports it; loaded first, it owns the process-wide C++ facet ids, and 22.04's older system libstdc++ (12.3) sent VLC's C++ HLS demuxer through them. | CI runs 39757626495 and 39759384980 (hs_err: SIGSEGV in libstdc++ data); gdb on WSL Ubuntu 22.04: `std::ostream::_M_insert<unsigned long>` called from `libadaptive_plugin.so`. Not reproducible on 24.04. | `NativePreload` loads the system libstdc++ first thing in `main` on Linux. Verified on 22.04: two passing launch checks, video shown. |
| X26 | P1 | **No picture from the .deb on a clean system**: libVLC's window embedding (`embed-xid`) is in `vlc-plugin-video-output`, which the package did not depend on. It only worked where the full `vlc` package was installed. | VLC verbose log: `looking for vout window module matching "embed-xid,any": 0 candidates` | Added to Depends. Verified on 22.04. |
| X24 | P3 | `--channel <login>` launch option (validated login) for launchers, scripts and the smoke tests. | | `platform/LaunchArgs.kt`, `LaunchArgsTest` |

## 5. Verification evidence

### Windows 11 (this PC)

| Command | Result |
|---|---|
| `gradlew :core:desktopTest :app-windows:test` (baseline) | 561 + 444 tests, 0 failures |
| `gradlew :app-windows:test` after every change on the branch | 0 failures (last run includes all new tests) |
| GitHub Actions `windows-latest`: `:core:desktopTest :app-windows:test` | PASS (runs 39752950707, 39754451563) |
| Launching this branch's Windows build (second pass, `bundleVlc` + `createReleaseDistributable`, APPDATA/LOCALAPPDATA pointed at scratch folders) | PASS: `app.first_window` 1.54 to 1.71 s over five launches, `stream.first_frame` 706 to 752 ms, `player.video_output`; the owner's real `%APPDATA%\PureTwitch` untouched |
| Closing it with the X button | NOT VERIFIED: the app ran on a desktop this session cannot see, so no window could be sent WM_CLOSE. The Windows close path is unchanged by the branch. |
| Windows CPU/memory | NOT MEASURED reliably: with the window on an undisplayed desktop rendering is throttled (a playing stream read 0% CPU), so those numbers are not evidence. |

### Linux: Ubuntu 24.04 under WSL2/WSLg (X11 via XWayland), this PC

| Check | Result |
|---|---|
| `createDistributable` and `packageReleaseDeb` | PASS |
| Launch, browse, live data | PASS (screenshot) |
| Data and cache folders | PASS: `~/.config/PureTwitch`, `~/.cache/PureTwitch` |
| Live playback via the distro's libVLC 3.0.20 into the embedded X11 canvas | PASS: video pixels in the canvas (mean 0.34, sd 0.25); `stream.first_frame` 535-614 ms |
| macOS render path forced (`-Dpuretv.video.callback=true`) | PASS: correct colours and letterboxing. CPU 49% of one core vs 41% native, same 1080p stream, 20 s average |
| Fullscreen (F / Esc) | PASS: `_NET_WM_STATE_FULLSCREEN` 1920x1080, restores to 1270x690 |
| mpv engine | PASS after X23 fix (picture) |
| `.deb`: apt install (deps resolve), menu entry valid, remove, reinstall | PASS after X17/X18 fixes |
| `launch-smoke.sh` against the installed `.deb` | PASS: `stream.first_frame`, `player.video_output`, still running 15 s later |
| Tab traversal | PASS after X19 fix |

### GitHub-hosted runners after every fix (run 39767612350)

| Job | Result |
|---|---|
| Windows: core + desktop tests | PASS |
| macOS 14 arm64: tests, bundled VLC (VLC.app uninstalled), ad-hoc .dmg, install, launch | PASS: `stream.first_frame` 1829 ms, `player.video_output`, `player.frame_painted` |
| Ubuntu 22.04: tests, .deb, apt install, menu entry valid, launch under Xvfb with PulseAudio, removal | PASS: `stream.first_frame` 768 ms, `player.video_output`, picture in the screenshot |

### Linux: the CI-built .deb on Ubuntu 24.04 (WSL), after every fix

The release package is built on Ubuntu 22.04 with Temurin. That exact file (run 39767612350) installs on 24.04 with apt and passes `launch-smoke.sh` (`stream.first_frame` 600 ms, `player.video_output`, still running 15 s later). The earlier 24.04 results above were taken before `NativePreload` existed.

Its Depends use names shared by Ubuntu and Debian (`libasound2`, `libpng16-16`, no `libjpeg-turbo8`); CI also installs it in clean `debian:12` and `ubuntu:24.04` containers (install only, no launch): both `install ok installed` in run 39769132786, which also passed all three OS jobs with the CJK font recommendation.

### Linux: Ubuntu 22.04 under WSL2 (Xvfb + PulseAudio), this PC

| Check | Result |
|---|---|
| The CI-built .deb (Temurin on 22.04) installs with apt | PASS |
| Launch on a live channel before X25/X26 | FAIL: native crash (reproduced, gdb stack above) |
| Same with X25/X26 fixed | PASS twice: `stream.first_frame` 607 ms, `player.video_output`, picture in the screenshot |
| A .deb whose launcher was built with Ubuntu 24.04's own JDK | Does not start on 22.04 (`GLIBCXX_3.4.32 not found` in libapplauncher.so). Release packages are built on Ubuntu 22.04 with Temurin for this reason. |

Caveat: WSLg has no GPU-backed GL for X11 clients (Skia falls back to software, mpv needs X23). A real Linux desktop with GPU drivers was not available.

### macOS 14 on Apple Silicon (GitHub-hosted `macos-14` runner)

Run 39752950707 (first push):

| Check | Result |
|---|---|
| `:core:desktopTest :app-windows:test` | PASS |
| `bundleVlc` from VLC.app, `createReleaseDistributable` | PASS |
| `codesign --sign -` then `codesign --verify --deep --strict` | PASS |
| `.dmg` (UDZO) created, mounted, app copied to /Applications, `lipo -archs` = arm64 | PASS |
| Launch on a live channel | PASS: `stream.first_frame` 1593 ms, `player.video_output`, `player.frame_painted 288x162`; screenshot shows video, traffic lights over the PureTV bar, menu-bar icon |

Caveat on that run: VLC.app was still installed on the runner, so the app could have used it instead of its bundled copy. From commit `077ca4e` the workflow uninstalls VLC.app first. Runs 39756064094, 39757626495 and 39759384980 then passed the same launch check (`stream.first_frame` 1721 ms, `player.video_output`, `player.frame_painted`), which proves the bundled VLC.

## 6. Parity matrix

PASS = verified, FAIL, BLOCKED = needs something not available here, UNVERIFIED = not run on that OS, N/A.

| Capability | Windows | macOS (arm64) | Linux (amd64 .deb) |
|---|---|---|---|
| Build | PASS (CI + local) | PASS (CI) | PASS (CI + WSL) |
| Unit tests | PASS | PASS (CI) | PASS (CI) |
| Package | PASS (existing release.yml, unchanged steps) | PASS (.dmg, ad-hoc signed) | PASS (.deb, finished) |
| Install / uninstall | PASS (shipping) | Install PASS (CI, no quarantine); uninstall = delete the app (UNVERIFIED) | PASS (WSL; CI apt) |
| Launch | PASS (shipping 1.13.0) | PASS (CI) | PASS (WSL; CI Xvfb) |
| Live playback (VLC) | PASS (shipping) | PASS (CI, bundled VLC, frames painted) | PASS (CI 22.04; WSL 22.04 and 24.04 with the CI-built .deb) |
| mpv engine | PASS (shipping) | N/A (not offered) | PASS (WSL, software fallback) |
| Audio, per-tile volume | PASS (shipping) | UNVERIFIED (no audio device on the runner) | UNVERIFIED by ear |
| Sign-in (device code) | PASS (shipping) | UNVERIFIED (needs a person) | UNVERIFIED (needs a person) |
| Sign-in kept across restarts | PASS (shipping) | UNVERIFIED | UNVERIFIED |
| Settings persistence | PASS (shipping) | UNVERIFIED | Folder verified; restart UNVERIFIED |
| Window chrome / fullscreen | PASS (unchanged) | Chrome PASS (screenshot); fullscreen UNVERIFIED | PASS (WSL) |
| Tray, go-live alerts | PASS (shipping) | Menu-bar icon visible (CI); alerts UNVERIFIED | Depends on desktop (GNOME: none); UNVERIFIED |
| Start at login | PASS (shipping) | UNVERIFIED (LaunchAgent from unit-tested code) | UNVERIFIED (autostart entry, unit-tested) |
| Updates | In-place install (shipping) | Notify + release page (unit-tested selector) | Notify + release page (unit-tested selector) |
| Code signing | Not signed (SmartScreen warning, as today) | BLOCKED: no Developer ID; ad-hoc only, not notarized | N/A (.deb unsigned, normal for direct downloads) |
| Keyboard Tab traversal | Fixed (same code, verified on Linux) | Fixed (same code) | PASS (WSL) |

### Release workflow

The `release: true` path (secret handoff to the reusable workflow, tag check, jar check, downloading the macOS and Linux artifacts into the draft, `SHA256SUMS.txt`) has **never run**. It was not dispatched on purpose: `release.yml` also pushes the proxy image to GHCR. The first real `v*` tag is its first test; it only opens a draft, so a failure publishes nothing. That tag needs `appVersion` above 1.13.0, since the guard refuses to rebuild a published version.

## 6b. Second pass (2026-10-22): review, journeys, security

### Code review of the branch (8 findings, all fixed)

| # | Sev | Finding | Fix |
|---|---|---|---|
| R1 | P1 | macOS/Linux: leaving the system fullscreen through the OS (green button, Ctrl+Cmd+F, a window manager shortcut) left the player in its fullscreen layout; the next F did nothing visible. | `NativeFullscreenExit` follows the placement (main window and popped-out streams); unit-tested. |
| R2 | P1 | macOS: the quit handler answered `cancelQuit`, which macOS reads as PureTV refusing to quit, so a logout or restart with PureTV open was interrupted. | The response is left pending and PureTV quits itself. CI now quits the app with a quit Apple event and requires a clean exit. |
| R3 | P2 | Popped-out stream windows had the same fullscreen desync. | Same fix as R1. |
| R4 | P2 | `--channel` on a second launch reached the running copy as a plain "show". | The activation request carries the channel; unit-tested and verified on Linux (J5 below). |
| R5 | P2 | macOS/Linux data folder (history, follows, encrypted sign-in) created 0755. | Made 0700 at start; verified (J1). |
| R6 | P2 | Start at login removed through the OS was written back on the next launch (macOS, Linux). | The setting follows the OS when the entry is gone. |
| R7 | P3 | Stale doc said fullscreen never uses `WindowPlacement.Fullscreen`. | Updated. |
| R8 | P3 | `VideoCanvas.clearFrame` unused. | Removed. |

### Security (section 8 of the plan)

| Check | Result |
|---|---|
| Dependency advisories (OSV, all 173 runtime artifacts of the desktop app) | Before: Netty 4.1.118 (from Ktor 3.1.1) with 30+ advisories (request smuggling, decoder DoS, HTTP/2 floods). Pinned Netty 4.1.139 through its BOM: **0 advisories**. Playback verified on Linux and Windows after the bump. Dependabot is disabled on the repository; turning it on is recommended. |
| Local stream proxy reachable by web pages (DNS rebinding) | Was open: a page whose domain resolves to 127.0.0.1 is same-origin with it. Now refuses any Host but 127.0.0.1/localhost. Verified during playback: `127.0.0.1` 200, `localhost` 200, `Host: evil.example` 403. |
| Proxy SSRF (`/variant`) | Already limited to `*.ttvnw.net`; verified 403 for `169.254.169.254`. |
| `localhost` resolving to `::1` | Player URLs now use 127.0.0.1 (found when WSL's localhost forwarding held [::1]:7979 and the Windows player talked to it). |
| Secrets in the repository | None tracked (`git ls-files`: only `secrets.properties.example` and the checker script). The client secret is injected at build time; release builds check the jar. |
| Credential storage | AES-GCM with a per-machine key (Windows MachineGuid; macOS/Linux a 0600 `.keyseed` in the now-0700 data folder). Not the OS keychain; recommended as a follow-up (macOS Keychain, Secret Service on Linux). |
| Logs | `timing.log` holds channel names and timings; the copyable diagnostics report scrubs tokens and signed URLs; `crash.log` stays local. |
| Telemetry / new network access | None added. The smoke test queries Twitch's public GQL for a live channel (CI only). |

### Linux user journeys (WSL Ubuntu 24.04, Xvfb, normal user, fresh build)

| Journey | Result |
|---|---|
| J1 Watch 40 s, then SIGTERM (session logout) | PASS: video, exited in 4 s, data folder 0700 |
| J2 Settings survive a restart (theme, chat timestamps, quality) | PASS: values kept, Ember theme shown on restart |
| J3 Corrupted `settings.json` | PASS: starts normally; the bad file is kept as `settings.json.corrupt-<time>` |
| J4 No internet at start | PASS: "Couldn't load live channels" with Retry, no crash |
| J5 Second launch with `--channel` | PASS: the open window switched channel, still one process |
| Proxy changes (Netty 4.1.139, Host check, 127.0.0.1) | PASS: launch smoke, `player.video_output`, probes above |

### Performance (what was and was not measured)

| Measure | Result |
|---|---|
| Time to first window | Windows 1.54 to 1.71 s; Linux (WSL) 1.1 to 2.3 s; macOS CI runner 4.5 s cold |
| Stream open to first frame | Windows 0.7 s; Linux 0.5 to 0.8 s; macOS CI 1.6 to 1.8 s |
| macOS frame-callback render cost | +8 points of one core vs native rendering, 1080p, measured on Linux with the forced path |
| Idle/playing CPU and memory on Windows | Not measured reliably from this session (see section 5) |
| Linux memory while playing | about 790 MB RSS (WSL, software rendering) |

## 7. Release recommendation

**Released as 1.14.0 with documented limitations** (owner decision, 2026-10-22). My own
recommendation was to wait for the device checks below; the owner chose to release all
three platforms now. What is still unverified by a person:

- macOS: sign-in, a restart with the session kept, audio, fullscreen and Gatekeeper's
  first-open flow on a real Mac. Notarization is BLOCKED without a Developer ID, so the
  first open will always need "Open Anyway".
- Linux: the same sign-in, restart and audio checks on a real desktop (GNOME and KDE).
- Windows: launch and playback of this branch PASS (second pass). Still for a person:
  the X button, Snap, multi-window and the narrow top bar on the real desktop, since the
  focus and top-bar changes touch shared UI.

Merged to `main` and released as `v1.14.0`, with the README's macOS and Linux sections, so `/releases/latest` carries the `.exe`, `.dmg` and `.deb` together.

### Release-readiness gate (plan section 13)

| Criterion | Status | Evidence |
|---|---|---|
| P0/P1 blockers resolved or dispositioned | PASS | X1-X7, X17, X18, X25, X26, R1, R2 fixed; sign-in on mac/Linux dispositioned as a device check |
| Existing functionality intact | PASS (automated) / person check pending | 0 test failures; Windows launch + playback PASS |
| Tests pass | PASS | Windows local; CI Windows, macOS, Ubuntu 22.04 |
| Lint / type check | N/A | No linter configured; the Kotlin compiler is the type check (clean) |
| Production builds | PASS | Windows app image, macOS .app, Linux .deb |
| Packaging | PASS | .dmg (ad-hoc), .deb (finished), Windows Inno unchanged |
| Startup on each OS | PASS | Windows (this PC), macOS (CI), Linux (CI, WSL) |
| Core journeys | PASS on Linux (J1-J5); PARTIAL on macOS (launch, play, quit); Windows launch/play | |
| Authentication | BLOCKED | Device-code sign-in needs a person on macOS and Linux |
| Playback | PASS | All three OSes |
| Settings/data persistence | PASS on Linux; macOS UNVERIFIED | J2, J3 |
| Shutdown and cleanup | PASS on Linux (SIGTERM, WSL and CI) and macOS (CI run 39864955580: "Quit cleanly" within 20 s; the step sends a quit Apple event and falls back to SIGTERM, and its log does not show which one ran) | |
| Install / upgrade | Install PASS (all); upgrade UNVERIFIED (first mac/Linux release has nothing to upgrade from) | |
| Signing | BLOCKED (macOS notarization, no Developer ID); Windows unsigned as today | |
| Artifacts match platforms | PASS | `lipo -archs` arm64; .deb amd64 |
| Versions, checksums, notes | PARTIAL | SHA256SUMS in release.yml (never run); notes drafted in CHANGELOG "Unreleased"; `appVersion` bump pending |
| Known limitations documented | PASS | This report, README, DEVELOPING.md |

### Release checklist

1. Owner device checks: sign in and restart on a Mac and on a Linux desktop (GNOME and KDE); play with sound; fullscreen in and out with the OS controls; multi-stream audio per tile.
2. On Windows: X button, Snap, Ctrl+N windows, the stream bar at the default size.
3. Bump `appVersion` (above 1.13.0), rename the CHANGELOG "Unreleased" heading to `## <version> - <date>`, merge the branch (README with it).
4. Tag `v<version>`: a draft opens with the .exe, .dmg, .deb and SHA256SUMS.txt, after all three OS jobs pass.
5. Download each artifact from the draft, check its checksum, install, launch; then publish.

### Product opportunities (plan section 9; recommendations, not built)

| Idea | User problem and evidence | How | Effort / risk |
|---|---|---|---|
| Keychain / Secret Service for the sign-in key | The key seed is a file beside the token | Store the seed in macOS Keychain, Linux Secret Service, Windows DPAPI; keep the file as fallback | Medium; migration must not sign anyone out |
| A `twitch.tv` link handler | Links in other apps open the browser, not PureTV | Register a URL scheme (`puretv://`) per OS and route it through `--channel` | Medium (installer work per OS) |
| Pause the Home hero preview when the window is hidden or minimized | Home plays a live preview continuously | Stop it on hide/minimize; resume on show | Low; saves CPU and bandwidth |
| Intel Mac build | Owner scoped it out; some Mac users are on Intel | `macos-13` runner, x64 VLC.app | Low |
| Turn on Dependabot | Netty advisories went unnoticed | Repository setting plus a Gradle ecosystem config | Low |

## 8. Remaining work, by priority

- P1: the device checks above.
- P2: macOS traffic lights sit near the top of the 48 dp bar rather than centred (the
  standard JDK has no API to move them; JetBrains Runtime does).
- P2: the mini player on macOS and Linux keeps the system title bar (a window cannot
  drop its frame without being recreated).
- P2: an Intel macOS build (`macos-13` runner) if Intel Macs matter later.
- P3: `.rpm` for Fedora (VLC is outside Fedora's main repositories; it needs RPM Fusion).
- P3: the release's `libmpv` download on Windows still takes the latest nightly (pre-existing).

## 9. External requirements

- **Apple Developer ID**: none (owner, 2026-10-22). Signing and notarization are BLOCKED
  until a Developer ID Application certificate (as a base64 `.p12` secret plus its
  password) and an App Store Connect API key or app-specific password exist.
- **Intel Macs**: out of scope (owner decision).
