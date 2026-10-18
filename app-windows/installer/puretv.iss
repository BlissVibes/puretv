; Inno Setup script for PureTV.
;
; Wraps the jpackage app image (produced by `gradle :app-windows:createReleaseDistributable`)
; into a per-user installer that, unlike a plain jpackage MSI, LAUNCHES THE APP
; automatically when the install finishes (see the [Run] section).
;
; Version is passed in from CI:  ISCC.exe /DMyAppVersion=1.2.3 puretv.iss

#ifndef MyAppVersion
  #define MyAppVersion "0.0.0"
#endif
#define MyAppName "PureTV"
#define MyAppExe "PureTV for Twitch.exe"

[Setup]
; Stable identity so future versions upgrade in place rather than stacking.
AppId={{a75efb70-c7e9-424a-99b8-b6d9a98a0799}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher=dhawal-ss
DefaultDirName={localappdata}\Programs\PureTV
DefaultGroupName=PureTV
DisableProgramGroupPage=yes
; Per-user install: no UAC prompt, mirrors the previous packaging.
PrivilegesRequired=lowest
OutputDir=..\build\installer
OutputBaseFilename=PureTV-Setup-{#MyAppVersion}
SetupIconFile=..\src\main\resources\icon.ico
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesInstallIn64BitMode=x64
CloseApplications=force
CloseApplicationsFilter=PureTV for Twitch.exe
RestartApplications=no

[Files]
; Never delete the currently runnable app before its replacement is complete.
; Copy the full payload first while preserving the old launcher config, then
; replace the config LAST as the commit marker. If setup is interrupted, the old
; config and its referenced jars remain runnable; a completed install points the
; launcher at the newly copied content-hashed jar set.
;
; BeforeInstall runs once per file on a wildcard entry, so every file is checked
; for a lock right before Setup replaces it (see ReleaseFileFromPureTV).
Source: "..\build\compose\binaries\main-release\app\PureTV for Twitch\*"; DestDir: "{app}"; Excludes: "app\PureTV for Twitch.cfg"; Flags: recursesubdirs createallsubdirs ignoreversion; BeforeInstall: ReleaseFileFromPureTV
Source: "..\build\compose\binaries\main-release\app\PureTV for Twitch\app\PureTV for Twitch.cfg"; DestDir: "{app}\app"; Flags: ignoreversion; BeforeInstall: ReleaseFileFromPureTV

[Icons]
Name: "{group}\PureTV"; Filename: "{app}\{#MyAppExe}"
Name: "{autodesktop}\PureTV"; Filename: "{app}\{#MyAppExe}"

[Run]
; postinstall  -> offered as a (checked) final-page action after a normal install
; nowait       -> the installer closes without waiting for the app
; skipifsilent -> do NOT auto-launch during a silent in-app update; the updater
;                 handles its own relaunch
Filename: "{app}\{#MyAppExe}"; Description: "Launch PureTV now"; Flags: nowait postinstall skipifsilent

[Code]
// PrepareToInstall runs BEFORE the file phase (before the Files
// sections), so this is where we make sure no PureTV process is holding app files.
// Older in-app updaters do not reliably wait for the app to exit: its process can
// linger on background threads (the local stream proxy / VLC) and keep the app
// folder locked, so the in-place upgrade wipes that folder but fails to re-copy it,
// leaving the launcher without its .cfg so the app will not start. Force-closing the
// process here makes the upgrade succeed even when the OLD updater did not wait,
// which is what lets users on a pre-fix build update cleanly.
//
// taskkill by IMAGE NAME with NO /T on purpose: during an in-app update the script
// that launched this installer is a descendant of the very app we are killing, so a
// tree-kill would terminate the installer itself. Best-effort: if PureTV is not
// running, taskkill simply no-ops.
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  ResultCode: Integer;
begin
  Exec('taskkill.exe', '/F /IM "PureTV for Twitch.exe"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Sleep(1500);
  Result := '';
end;

// PrepareToInstall only covers PureTV processes that exist BEFORE the copy. The
// in-app update runs silently, so to the user the app just closes, and reopening
// it a few seconds later lands mid-copy: the old launcher loads its jars, Setup
// hits "file in use" on the next one it replaces, gives up after 4 retries and
// rolls the whole update back. The user ends up on the old version, still being
// offered the update, every time (v1.11.2 -> v1.12.0).
//
// So before each file is replaced, test whether anything holds it open. If so,
// close PureTV again and wait for the file to free up. Anything other than PureTV
// holding it still fails the normal way after the cap, rather than hanging Setup.
const
  GENERIC_READ = $80000000;
  OPEN_EXISTING = 3;
  ERROR_SHARING_VIOLATION = 32;
  INVALID_HANDLE_VALUE = -1;
  LOCK_WAIT_CAP_MS = 10000;

function CreateFile(lpFileName: String; dwDesiredAccess, dwShareMode, lpSecurityAttributes,
  dwCreationDisposition, dwFlagsAndAttributes, hTemplateFile: Cardinal): THandle;
  external 'CreateFileW@kernel32.dll stdcall';
function CloseHandle(hObject: THandle): BOOL;
  external 'CloseHandle@kernel32.dll stdcall';

// True when another process has the file open: an exclusive (share mode 0) open
// fails with a sharing violation. A missing file or any other error is not a lock.
function IsFileLocked(const FileName: String): Boolean;
var
  Handle: THandle;
begin
  Result := False;
  if not FileExists(FileName) then Exit;
  Handle := CreateFile(FileName, GENERIC_READ, 0, 0, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, 0);
  if Handle = THandle(INVALID_HANDLE_VALUE) then
    Result := DLLGetLastError = ERROR_SHARING_VIOLATION
  else
    CloseHandle(Handle);
end;

procedure ReleaseFileFromPureTV();
var
  Dest: String;
  ResultCode, Waited: Integer;
begin
  Dest := ExpandConstant(CurrentFileName);
  if not IsFileLocked(Dest) then Exit;
  Log('In use, closing PureTV before replacing: ' + Dest);
  Waited := 0;
  repeat
    Exec('taskkill.exe', '/F /IM "PureTV for Twitch.exe"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
    Sleep(500);
    Waited := Waited + 500;
  until (not IsFileLocked(Dest)) or (Waited >= LOCK_WAIT_CAP_MS);
  if IsFileLocked(Dest) then
    Log('Still in use after ' + IntToStr(Waited) + ' ms: ' + Dest)
  else
    Log('Released after ' + IntToStr(Waited) + ' ms');
end;

// A successful installer must never leave a launcher without its config. Raise
// an installer failure before the updater can relaunch if that invariant is not
// satisfied. The detached updater also checks this file as a second barrier.
procedure CurStepChanged(CurStep: TSetupStep);
var
  LauncherConfig: String;
begin
  if CurStep = ssPostInstall then
  begin
    LauncherConfig := ExpandConstant('{app}\app\PureTV for Twitch.cfg');
    if not FileExists(LauncherConfig) then
      RaiseException('Installation validation failed: launcher configuration is missing.');
  end;
end;
