; Builds Pocketpad-Setup.exe: installs the app, the virtual-controller driver (ViGEmBus) and a firewall rule.
[Setup]
AppName=Pocketpad
AppVersion=0.4
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
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Pocketpad"""; Flags: runhidden; RunOnceId: "RemoveFirewallRule"

[Code]
function NeedsDriver: Boolean;
begin
  Result := not RegKeyExists(HKLM, 'SYSTEM\CurrentControlSet\Services\ViGEmBus');
end;

// Close a running copy so the new exe can be written.
function InitializeSetup: Boolean;
var
  code: Integer;
begin
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM Pocketpad.exe', '', SW_HIDE, ewWaitUntilTerminated, code);
  Result := True;
end;
