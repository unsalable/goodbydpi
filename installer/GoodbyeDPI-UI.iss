; GoodbyeDPI UI - Inno Setup kurulum betigi
;
; Derleme:  ISCC.exe installer\GoodbyeDPI-UI.iss
; Ciktisi:  build\GoodbyeDPI-UI-Setup.exe
;
; Uygulama yayini once yapilmis olmali (build\app icinde tek dosya exe + Runtime).
; Otomatik guncelleme bu setup'i /VERYSILENT ile calistirir; bu yuzden [Run]
; adiminda skipifsilent YOK - sessiz kurulumdan sonra uygulama yeniden acilir.
;
; Uygulama (2.3.0'dan itibaren) kurucunun bitmesini bekleyip kendini AYRICA aciyor.
; Iki yol da calissa sorun olmaz: tek ornek kilidi yuzunden ikinci kopya sessizce cikar.
; Asagidaki [Run] adimi eski surumlerden guncelleyenler icin gerekli - kaldirmayin.

#define AppName "GoodbyeDPI UI"
#define AppVersion "2.3.0"
#define AppPublisher "GoodbyeDPI UI"
#define AppExe "GoodbyeDPI-UI.exe"
#define AppMutexName "GoodbyeDPI-UI.SingleInstance.v1"
#define SourceDir "..\build\app"
#define IconFile "..\src\GoodbyeDpiUI\Assets\app.ico"

[Setup]
AppId={{9DCDD6ED-2D8D-4EEE-A4A4-13F6B37614AE}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher={#AppPublisher}
AppSupportURL=https://github.com/unsalable/goodbydpi
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
DisableDirPage=auto
UninstallDisplayIcon={app}\{#AppExe}
UninstallDisplayName={#AppName}
OutputDir=..\build
OutputBaseFilename=GoodbyeDPI-UI-Setup
SetupIconFile={#IconFile}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
; Uygulama WinDivert surucusu icin yonetici ister; kurulum da yonetici olarak yapilir.
PrivilegesRequired=admin
PrivilegesRequiredOverridesAllowed=commandline
; win-x64 tek surum: 64-bit modda Program Files (x64) altina kurulur.
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
; Kurulum/kaldirma sirasinda calisan uygulamanin kapanmasini bekler.
CloseApplications=yes
AppMutex={#AppMutexName}

[Languages]
Name: "tr"; MessagesFile: "compiler:Languages\Turkish.isl"
Name: "en"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
; Tum yayin ciktisi (tek dosya exe + Runtime klasoru).
Source: "{#SourceDir}\{#AppExe}"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#SourceDir}\Runtime\*"; DestDir: "{app}\Runtime"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{group}\{cm:UninstallProgram,{#AppName}}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Run]
; Kurulumdan sonra uygulamayi baslat. skipifsilent YOK: otomatik guncellemede de acilir.
Filename: "{app}\{#AppExe}"; Description: "{cm:LaunchProgram,{#AppName}}"; Flags: nowait postinstall

[UninstallRun]
; Kaldirirken otomatik baslatma gorevini de temizle (yoksa sessizce gecer).
Filename: "{sys}\schtasks.exe"; Parameters: "/Delete /TN ""GoodbyeDPI UI Autostart"" /F"; Flags: runhidden; RunOnceId: "DelAutostartTask"

[UninstallDelete]
; Kullanici ayarlari uygulama tarafindan %AppData% altinda tutulur; kaldirmada birakiyoruz.
Type: filesandordirs; Name: "{app}\Runtime"
