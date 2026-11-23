; Builds Pocketpad-Setup.exe: installs the app, the virtual-controller driver (ViGEmBus) and a firewall rule.
[Setup]
AppName=Pocketpad
AppVersion=1.1
AppPublisher=Pocketpad
DefaultDirName={autopf}\Pocketpad
DisableProgramGroupPage=yes
OutputDir=output
OutputBaseFilename=Pocketpad-Setup
Compression=lzma2
SolidCompression=yes
PrivilegesRequired=admin
ArchitecturesInstallIn64BitMode=x64compatible
SetupIconFile=..\windows\pocketpad.ico
UninstallDisplayIcon={app}\Pocketpad.exe
WizardStyle=modern

[Files]
Source: "..\out\Pocketpad.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "ViGEmBus_Setup.exe"; DestDir: "{tmp}"; Flags: deleteafterinstall; Check: NeedsDriver

[Icons]
Name: "{autoprograms}\Pocketpad"; Filename: "{app}\Pocketpad.exe"
Name: "{autodesktop}\Pocketpad"; Filename: "{app}\Pocketpad.exe"

[Run]
Filename: "{tmp}\ViGEmBus_Setup.exe"; Parameters: "/passive /norestart"; StatusMsg: "Installing the controller driver..."; Check: NeedsDriver; Flags: waituntilterminated
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Pocketpad"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Pocketpad"" dir=in action=allow program=""{app}\Pocketpad.exe"" enable=yes profile=any"; StatusMsg: "Allowing Pocketpad through the firewall..."; Flags: runhidden
Filename: "{app}\Pocketpad.exe"; Description: "Open Pocketpad"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/F /IM Pocketpad.exe"; Flags: runhidden; RunOnceId: "StopApp"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Pocketpad"""; Flags: runhidden; RunOnceId: "RemoveFirewallRule"

[UninstallDelete]
Type: filesandordirs; Name: "{userappdata}\Pocketpad"
Type: filesandordirs; Name: "{app}"

[Code]
function NeedsDriver: Boolean;
begin
  // a cleaner can leave the registry entry behind and delete the driver file, so check both
  Result := not (RegKeyExists(HKLM, 'SYSTEM\CurrentControlSet\Services\ViGEmBus') and
                 FileExists(ExpandConstant('{sys}\drivers\ViGEmBus.sys')));
end;

// Close a running copy so the new exe can be written.
function InitializeSetup: Boolean;
var
  code: Integer;
begin
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM Pocketpad.exe', '', SW_HIDE, ewWaitUntilTerminated, code);
  Result := True;
end;

// Uninstall: offer to remove the controller driver too, so nothing is left behind.
function DriverUninstallCommand: String;
var
  names: TArrayOfString;
  i: Integer;
  dn, us: String;
begin
  Result := '';
  if not RegGetSubkeyNames(HKLM, 'SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall', names) then Exit;
  for i := 0 to GetArrayLength(names) - 1 do
  begin
    if RegQueryStringValue(HKLM, 'SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\' + names[i], 'DisplayName', dn) and
       ((Pos('ViGEm', dn) > 0) or (Pos('Virtual Gamepad Emulation', dn) > 0)) and
       RegQueryStringValue(HKLM, 'SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\' + names[i], 'UninstallString', us) then
    begin
      // shortcut: only the MSI style is handled; anything else is left alone
      if Pos('msiexec', LowerCase(us)) > 0 then
      begin
        StringChangeEx(us, '/I{', '/X{', True);
        Result := us + ' /qn /norestart';
      end;
      Exit;
    end;
  end;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  cmd: String;
  code: Integer;
begin
  if (CurUninstallStep = usUninstall) and (not UninstallSilent) then
  begin
    cmd := DriverUninstallCommand;
    if (cmd <> '') and (MsgBox('Also remove the virtual controller driver (ViGEmBus)?' + #13#10 +
        'Other apps that use virtual controllers need it, so only say Yes if nothing else does.',
        mbConfirmation, MB_YESNO) = IDYES) then
      Exec(ExpandConstant('{cmd}'), '/C ' + cmd, '', SW_HIDE, ewWaitUntilTerminated, code);
  end;
end;
