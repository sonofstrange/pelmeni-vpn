$ErrorActionPreference = "Stop"

$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Python = (Get-Command python).Source

Push-Location $ProjectDir
try {
    & $Python -m unittest discover -s tests -v
    if ($LASTEXITCODE -ne 0) { throw "Unit tests failed" }



    & $Python -m PyInstaller `
        --noconfirm `
        --clean `
        --onefile `
        --windowed `
        --name "PelmeniVPN-Desktop" `
        --icon "assets\pelmeni_icon.ico" `
        --version-file "app-version-info-qt.txt" `
        --add-data "assets\pelmeni_icon.png;assets" `
        --add-data "qml;qml" `
        --add-data "pelmeni_desktop\android_scripts.json;pelmeni_desktop" `
        --hidden-import "socks" `
        --hidden-import "PySide6.QtQuick" `
        --hidden-import "PySide6.QtQuickControls2" `
        pelmeni_vpn_qt_api.pyw
    if ($LASTEXITCODE -ne 0) { throw "Qt application build failed" }


    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\build-tun-helper.ps1"
    if ($LASTEXITCODE -ne 0) { throw "VPN helper build failed" }

    $InnoCandidates = @(
        "C:\tmp\InnoSetup\ISCC.exe",
        "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe",
        "C:\Program Files (x86)\Inno Setup 6\ISCC.exe",
        "C:\Program Files\Inno Setup 6\ISCC.exe"
    )
    $Iscc = $InnoCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if (-not $Iscc) {
        throw "Inno Setup compiler not found. Install Inno Setup 6 or place it in C:\tmp\InnoSetup."
    }
    & $Iscc "installer.iss"
    if ($LASTEXITCODE -ne 0) { throw "Inno Setup build failed" }
} finally {
    Pop-Location
}
