; Inno Setup 6 script for Remindly for Windows.
; Compile: ISCC.exe /DMyAppVersion=2.11.0 /DSourceDir=<folder holding Remindly.exe> /DOutputDir=<folder> Remindly.iss
; GitHub Actions does this on every build — see .github/workflows/windows.yml.
;
; A previous version that is RUNNING is detected (its single-instance lock "Remindly.SingleInstance"),
; asked to save and close, replaced, and started again — no questions asked, so the same Setup serves
; a double-click install, a silent install and Remindly's own background auto-update.

#define MyAppName "Remindly"
#ifndef MyAppVersion
  #define MyAppVersion "2.11.0"
#endif
#define MyAppPublisher "Krishna Bhunia"
#define MyAppURL "https://github.com/krishnabhunia/remindly-app"
#define MyAppExeName "Remindly.exe"
#ifndef SourceDir
  #define SourceDir "..\out\portable"
#endif
#ifndef OutputDir
  #define OutputDir "..\out\installer"
#endif

[Setup]
AppId={{6F3A2C1E-8B4D-4E7A-9C21-5D0B7E3F4A62}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}/issues
AppUpdatesURL={#MyAppURL}/releases
VersionInfoVersion={#MyAppVersion}
VersionInfoProductVersion={#MyAppVersion}
DefaultDirName={autopf}\Remindly
DefaultGroupName=Remindly
DisableProgramGroupPage=yes
DisableDirPage=auto
; Per-user by default (no UAC prompt, so background updates stay silent); "all users" can be chosen.
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog commandline
OutputDir={#OutputDir}
OutputBaseFilename=Remindly-Setup-{#MyAppVersion}
SetupIconFile=..\src\Remindly.App\Assets\app.ico
UninstallDisplayIcon={app}\{#MyAppExeName}
UninstallDisplayName={#MyAppName}
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
; Remindly is closed by the [Code] below (gracefully, then by force); the Restart Manager is not needed.
CloseApplications=no
RestartApplications=no
ShowLanguageDialog=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked
Name: "autostart"; Description: "Start Remindly with Windows (hidden in the notification area, so reminders always fire)"; GroupDescription: "Windows integration:"

[Files]
Source: "{#SourceDir}\{#MyAppExeName}"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Registry]
; Marker the app reads to know it was installed by Setup (→ its updates run the new Setup, not a file swap).
Root: HKA; Subkey: "Software\Remindly"; ValueType: string; ValueName: "InstallDir"; ValueData: "{app}"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Remindly"; ValueType: string; ValueName: "Version"; ValueData: "{#MyAppVersion}"
; Start with Windows (the app's Settings → General toggle manages the same per-user value).
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "Remindly"; ValueData: """{app}\{#MyAppExeName}"" --tray"; Tasks: autostart; Flags: uninsdeletevalue

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#MyAppName}}"; Flags: nowait postinstall skipifsilent; Check: not WasRunningBefore

[UninstallDelete]
Type: files; Name: "{app}\Remindly.old.exe"

[Code]
const
  // Held by every running Remindly (Local\Remindly.SingleInstance in SingleInstance.cs).
  RemindlyMutex = 'Remindly.SingleInstance';

var
  WasRunning: Boolean;

function WasRunningBefore(): Boolean;
begin
  Result := WasRunning;
end;

// Remindly's own background update starts Setup with /REMINDLYUPDATE=1 (and /RELAUNCH=tray when its window was hidden).
function IsSelfUpdate(): Boolean;
begin
  Result := ExpandConstant('{param:REMINDLYUPDATE|0}') = '1';
end;

function RemindlyProcessRunning(): Boolean;
var
  ResultCode: Integer;
begin
  Result := Exec(ExpandConstant('{cmd}'), '/C tasklist /NH /FI "IMAGENAME eq {#MyAppExeName}" | find /I "{#MyAppExeName}" >nul',
                 '', SW_HIDE, ewWaitUntilTerminated, ResultCode) and (ResultCode = 0);
end;

function RemindlyRunning(): Boolean;
begin
  Result := CheckForMutexes(RemindlyMutex) or RemindlyProcessRunning();
end;

procedure WaitForExit(MaxMs: Integer);
var
  Waited: Integer;
begin
  Waited := 0;
  while RemindlyRunning() and (Waited < MaxMs) do
  begin
    Sleep(250);
    Waited := Waited + 250;
  end;
end;

// 1) "Remindly.exe --exit" signals the running copy to save and close (it waits up to 20 s);
// 2) if it is still there, it is ended by force — every change is already saved to disk the moment it is made.
function CloseRemindly(): Boolean;
var
  Exe: String;
  ResultCode: Integer;
begin
  if RemindlyRunning() then
  begin
    Log('Remindly is running — asking it to exit.');
    try
      if IsUninstaller() then
        Exe := ExpandConstant('{app}\{#MyAppExeName}')
      else
      begin
        ExtractTemporaryFile('{#MyAppExeName}');
        Exe := ExpandConstant('{tmp}\{#MyAppExeName}');
      end;
      if FileExists(Exe) then
        Exec(Exe, '--exit', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
    except
      Log('Could not ask Remindly to exit: ' + GetExceptionMessage());
    end;
    WaitForExit(10000);
  end;
  if RemindlyRunning() then
  begin
    Log('Remindly still running — ending it.');
    Exec(ExpandConstant('{cmd}'), '/C taskkill /IM {#MyAppExeName} /F', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
    WaitForExit(8000);
  end;
  // The process can outlive its lock by a moment; give Windows time to release the exe.
  Sleep(500);
  Result := not RemindlyRunning();
end;

function InitializeSetup(): Boolean;
begin
  WasRunning := CheckForMutexes(RemindlyMutex) or RemindlyProcessRunning();
  if WasRunning then Log('A previous Remindly is running; it will be updated in the background and restarted.');
  // Remindly's own update exits right after starting Setup — it must come back either way.
  if IsSelfUpdate() then WasRunning := True;
  Result := True;
end;

// Shown on the "Ready to Install" page of an interactive install.
function UpdateReadyMemo(Space, NewLine, MemoUserInfoInfo, MemoDirInfo, MemoTypeInfo, MemoComponentsInfo, MemoGroupInfo, MemoTasksInfo: String): String;
begin
  Result := '';
  if WasRunning then
    Result := 'Remindly is running now:' + NewLine + Space + 'Setup closes it, installs {#MyAppVersion} and starts it again.' + NewLine + NewLine;
  if MemoDirInfo <> '' then Result := Result + MemoDirInfo + NewLine + NewLine;
  if MemoGroupInfo <> '' then Result := Result + MemoGroupInfo + NewLine + NewLine;
  if MemoTasksInfo <> '' then Result := Result + MemoTasksInfo + NewLine;
end;

// Just before files are copied: the running copy must be gone, or Remindly.exe cannot be replaced.
function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  Result := '';
  if RemindlyRunning() then
  begin
    WasRunning := True;
    if not CloseRemindly() then
      Result := 'Remindly is still running and could not be closed. Exit it from its notification-area icon (right-click → Exit) and run Setup again.';
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  ResultCode: Integer;
  Params: String;
begin
  if (CurStep = ssPostInstall) and WasRunning then
  begin
    // Bring the updated Remindly straight back (as the signed-in user, never elevated).
    Params := '--updated';
    if ExpandConstant('{param:RELAUNCH|}') = 'tray' then Params := Params + ' --tray';
    if not ExecAsOriginalUser(ExpandConstant('{app}\{#MyAppExeName}'), Params, '', SW_SHOWNORMAL, ewNoWait, ResultCode) then
      Log('Could not restart Remindly: ' + SysErrorMessage(ResultCode));
  end;
end;

function InitializeUninstall(): Boolean;
begin
  Result := True;
  if RemindlyRunning() then CloseRemindly();
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usPostUninstall then
  begin
    RegDeleteValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run', 'Remindly');
    // The single-file exe unpacks native libraries to %TEMP%\.net\Remindly (a cache).
    DelTree(AddBackslash(GetEnv('TEMP')) + '.net\Remindly', True, True, True);
    if (not UninstallSilent()) and DirExists(ExpandConstant('{localappdata}\Remindly')) then
      if MsgBox('Also delete your Remindly data (tasks, lists, calls, backups)?' + #13#10 +
                '(Folder: ' + ExpandConstant('{localappdata}\Remindly') + ')' + #13#10#13#10 +
                'Choose No to keep it for a later reinstall or the portable copy.', mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES then
        DelTree(ExpandConstant('{localappdata}\Remindly'), True, True, True);
  end;
end;
