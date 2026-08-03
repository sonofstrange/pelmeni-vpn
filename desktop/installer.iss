#define MyAppName "Пельмени VPN Desktop"
#define MyAppVersion "1.32.0"
#define MyAppExeName "PelmeniVPN-Desktop.exe"
#define MySetupName "PelmeniVPN-Desktop-Setup-1.32"

[Setup]
AppId={{A3E26D86-3C0A-41EA-95D6-CE7D1BE54192}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher=Pelmeni VPN
DefaultDirName={autopf}\PelmeniVPN Desktop
DefaultGroupName=Пельмени VPN
DisableProgramGroupPage=yes
AllowNoIcons=yes
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
OutputDir=dist
OutputBaseFilename={#MySetupName}
SetupIconFile=assets\pelmeni_icon.ico
UninstallDisplayIcon={app}\{#MyAppExeName}
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes
RestartApplications=no
UsePreviousAppDir=yes
UsePreviousTasks=yes
VersionInfoVersion=1.32.0.0
VersionInfoCompany=Pelmeni VPN
VersionInfoDescription=Pelmeni VPN Desktop Setup
VersionInfoProductName=Pelmeni VPN Desktop
VersionInfoProductVersion=1.32.0.0

[Languages]
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"

[Tasks]
Name: "desktopicon"; Description: "Создать ярлык на рабочем столе"; GroupDescription: "Дополнительные ярлыки:"; Flags: unchecked

[InstallDelete]
Type: files; Name: "{app}\Uninstall.exe"
Type: files; Name: "{app}\install.json"
Type: files; Name: "{commonprograms}\Пельмени VPN\Удалить Пельмени VPN.lnk"

[Files]
Source: "dist\{#MyAppExeName}"; DestDir: "{app}"; Flags: ignoreversion
Source: "dist\PelmeniVPN-TunHelper.exe"; DestDir: "{app}"; Flags: ignoreversion


[Icons]
Name: "{group}\Пельмени VPN"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"
Name: "{autodesktop}\Пельмени VPN"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"; Tasks: desktopicon

[Registry]
Root: HKLM; Subkey: "Software\Microsoft\Windows\CurrentVersion\Uninstall\PelmeniVPNDesktop"; Flags: deletekey

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "Запустить Пельмени VPN"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{app}\{#MyAppExeName}"; Parameters: "--restore-proxy"; Flags: runhidden; RunOnceId: "RestorePelmeniProxy"
