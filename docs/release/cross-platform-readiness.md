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

## 4. Platform parity matrix

Legend: PASS (verified), FAIL, BLOCKED (needs something outside this environment),
UNVERIFIED (not run on that OS), N/A.

This table is filled in as the work proceeds. See section 6 for the final state.

## 5. External requirements

- **Apple Developer ID**: none (owner, 2026-10-22). The macOS `.dmg` is unsigned
  (ad-hoc), not notarized, and not Gatekeeper-ready. Users must allow it under
  System Settings > Privacy & Security > Open Anyway. Signing and notarization are
  BLOCKED until a Developer ID certificate and an app-specific password or API key exist.
- **Intel Macs**: out of scope (owner decision). Apple Silicon (arm64) only.
- **macOS playback**: no Mac in this environment. CI runners prove build, package and
  launch only.
