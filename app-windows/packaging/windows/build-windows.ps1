<#
Builds the Windows installer (PureTV-Setup-<version>.exe) on a Windows PC or runner.

Run from anywhere:
    powershell -ExecutionPolicy Bypass -File app-windows\packaging\windows\build-windows.ps1

Needs, already installed:
    JDK 17        winget install EclipseAdoptium.Temurin.17.JDK
    VLC 3.0.24+   winget install VideoLAN.VLC      (the installer build, not the .zip)
    Inno Setup 6  winget install JRSoftware.InnoSetup

-InstallDeps installs whichever of those is missing with winget first.
-SkipMpv leaves out libmpv (the opt-in mpv playback backend); VLC playback is unaffected.

The Twitch client secret comes from the TWITCH_CLIENT_SECRET environment variable or
secrets.properties at the repo root. Without one the build still works, but a signed-in
user is signed out when the access token expires (about every 4 hours).
#>
param(
    [switch]$InstallDeps,
    [switch]$SkipMpv
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
Set-Location $repo

function Step($text) { Write-Host ""; Write-Host "== $text" -ForegroundColor Cyan }

function Invoke-Gradle {
    & "$repo\gradlew.bat" --console=plain @args
    if ($LASTEXITCODE -ne 0) { throw "gradlew $($args -join ' ') failed with exit code $LASTEXITCODE" }
}

function Find-Vlc {
    $dirs = @($env:VLC_DIR, "C:\Program Files\VideoLAN\VLC", "C:\Program Files (x86)\VideoLAN\VLC") | Where-Object { $_ }
    foreach ($d in $dirs) { if (Test-Path (Join-Path $d "libvlc.dll")) { return $d } }
    return $null
}

function Find-Iscc {
    $paths = @(
        (Join-Path ${env:ProgramFiles(x86)} "Inno Setup 6\ISCC.exe"),
        (Join-Path $env:ProgramFiles "Inno Setup 6\ISCC.exe"),
        (Join-Path $env:LOCALAPPDATA "Programs\Inno Setup 6\ISCC.exe")
    )
    foreach ($p in $paths) { if (Test-Path $p) { return $p } }
    return $null
}

function Find-Java17 {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME "bin\java.exe") }
    $onPath = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($onPath) { $candidates += $onPath.Source }
    $candidates += Get-ChildItem "C:\Program Files\Eclipse Adoptium", "C:\Program Files\Amazon Corretto", "C:\Program Files\Java" `
        -Filter java.exe -Recurse -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
    foreach ($java in $candidates) {
        if (-not (Test-Path $java)) { continue }
        # `java -version` writes to stderr; cmd merges it so PowerShell 5 does not turn it into an error.
        $text = (cmd /c "`"$java`" -version 2>&1") -join " "
        if ($text -match 'version "17\.') { return (Split-Path (Split-Path $java)) }
    }
    return $null
}

function Install-WithWinget($id) {
    if (-not (Get-Command winget.exe -ErrorAction SilentlyContinue)) { throw "winget is not available. Install $id by hand." }
    Write-Host "Installing $id with winget"
    winget install --id $id --exact --silent --accept-package-agreements --accept-source-agreements
}

Step "Checking tools"
$javaHome = Find-Java17
if (-not $javaHome -and $InstallDeps) { Install-WithWinget "EclipseAdoptium.Temurin.17.JDK"; $javaHome = Find-Java17 }
if (-not $javaHome) { throw "JDK 17 not found. Run: winget install EclipseAdoptium.Temurin.17.JDK   (or pass -InstallDeps)" }
$env:JAVA_HOME = $javaHome
Write-Host "JDK 17:     $javaHome"

$vlc = Find-Vlc
if (-not $vlc -and $InstallDeps) { Install-WithWinget "VideoLAN.VLC"; $vlc = Find-Vlc }
if (-not $vlc) { throw "VLC not found. Run: winget install VideoLAN.VLC   (or pass -InstallDeps, or set VLC_DIR)" }
if (-not (Test-Path (Join-Path $vlc "vlc-cache-gen.exe"))) { throw "vlc-cache-gen.exe is missing from $vlc. Use VLC's installer build, not the .zip." }
$env:VLC_DIR = $vlc
Write-Host "VLC:        $vlc ($((Get-Item (Join-Path $vlc 'libvlc.dll')).VersionInfo.ProductVersion))"

$iscc = Find-Iscc
if (-not $iscc -and $InstallDeps) { Install-WithWinget "JRSoftware.InnoSetup"; $iscc = Find-Iscc }
if (-not $iscc) { throw "Inno Setup 6 not found. Run: winget install JRSoftware.InnoSetup   (or pass -InstallDeps)" }
Write-Host "Inno Setup: $iscc"

$version = (Select-String -Path "app-windows\build.gradle.kts" -Pattern '^val appVersion = "(.*)"').Matches[0].Groups[1].Value
if (-not $version) { throw "Could not read appVersion from app-windows\build.gradle.kts" }
Write-Host "Version:    $version"

$hasSecret = [bool]$env:TWITCH_CLIENT_SECRET
if (-not $hasSecret -and (Test-Path "secrets.properties")) {
    $hasSecret = [bool](Select-String -Path "secrets.properties" -Pattern '^\s*twitch\.client\.secret\s*=\s*\S' -Quiet)
}
if (-not $hasSecret) {
    Write-Warning "No Twitch client secret (TWITCH_CLIENT_SECRET or secrets.properties). The app will build and play streams, but a signed-in user is signed out about every 4 hours."
}

# Each bundle task is its own Gradle run: the app image reads the folders they write.
Step "Bundling VLC"
Invoke-Gradle ":app-windows:bundleVlc"

if (-not $SkipMpv) {
    Step "Bundling libmpv (optional mpv playback backend)"
    try {
        if (-not ($env:MPV_DIR -and (Test-Path (Join-Path $env:MPV_DIR "libmpv-2.dll")))) {
            $work = Join-Path $repo "app-windows\build\mpv-download"
            if (Test-Path $work) { Remove-Item $work -Recurse -Force }
            New-Item -ItemType Directory -Path $work | Out-Null
            $headers = @{ "User-Agent" = "PureTV-build" }
            if ($env:GITHUB_TOKEN) { $headers["Authorization"] = "Bearer $env:GITHUB_TOKEN" }
            $release = Invoke-RestMethod "https://api.github.com/repos/shinchiro/mpv-winbuild-cmake/releases/latest" -Headers $headers
            $asset = $release.assets | Where-Object { $_.name -like "mpv-dev-x86_64-v3-*.7z" } | Select-Object -First 1
            if (-not $asset) { throw "no mpv-dev-x86_64-v3 archive in the latest shinchiro release" }
            $archive = Join-Path $work $asset.name
            Write-Host "Downloading $($asset.name)"
            Invoke-WebRequest $asset.browser_download_url -OutFile $archive -UseBasicParsing
            $out = Join-Path $work "x"
            New-Item -ItemType Directory -Path $out | Out-Null
            $sevenZip = Get-Command 7z.exe -ErrorAction SilentlyContinue
            if (-not $sevenZip -and (Test-Path "C:\Program Files\7-Zip\7z.exe")) { $sevenZip = Get-Item "C:\Program Files\7-Zip\7z.exe" }
            if ($sevenZip) {
                $sevenZipPath = if ($sevenZip.Source) { $sevenZip.Source } else { $sevenZip.FullName }
                & $sevenZipPath x $archive "-o$out" -y | Out-Null
            } else {
                # Windows 10 and 11 ship bsdtar, which reads .7z.
                & "$env:SystemRoot\System32\tar.exe" -xf $archive -C $out
            }
            if ($LASTEXITCODE -ne 0) { throw "could not extract $($asset.name)" }
            $dll = Get-ChildItem $out -Recurse -Filter libmpv-2.dll | Select-Object -First 1
            if (-not $dll) { throw "libmpv-2.dll not found in $($asset.name)" }
            $env:MPV_DIR = $dll.Directory.FullName
        }
        Invoke-Gradle ":app-windows:bundleMpv"
    } catch {
        Write-Warning "libmpv was not bundled: $($_.Exception.Message). Continuing; VLC playback is unaffected."
    }
}

Step "Building the app"
Invoke-Gradle ":app-windows:createReleaseDistributable"

$image = "app-windows\build\compose\binaries\main-release\app\PureTV for Twitch"
foreach ($needed in @("PureTV for Twitch.exe", "app\resources\vlc\libvlc.dll", "app\resources\vlc\vlc-cache-gen.exe")) {
    if (-not (Test-Path (Join-Path $image $needed))) { throw "The app image is missing $needed" }
}

Step "Building the installer"
& $iscc "/DMyAppVersion=$version" "app-windows\installer\puretv.iss"
if ($LASTEXITCODE -ne 0) { throw "Inno Setup failed with exit code $LASTEXITCODE" }

$installer = Get-Item "app-windows\build\installer\PureTV-Setup-$version.exe"
Step "Done"
Write-Host "$($installer.FullName)  ($([math]::Round($installer.Length / 1MB)) MB)"
Write-Host "SHA256: $((Get-FileHash $installer.FullName -Algorithm SHA256).Hash)"
