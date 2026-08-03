$ErrorActionPreference = "Stop"

$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Python = (Get-Command python).Source

Push-Location $ProjectDir
try {
    & $Python -m pip install --disable-pip-version-check `
        "PySide6==6.8.3" "paramiko>=3.4,<4" "PyInstaller>=6.6,<7"
    if ($LASTEXITCODE -ne 0) { throw "Dependency installation failed" }

    & $Python -m PyInstaller `
        --noconfirm `
        --clean `
        --onefile `
        --windowed `
        --name "PelmeniVPN-Desktop" `
        --icon "assets\pelmeni_icon.ico" `
        --version-file "app-version-info.txt" `
        --add-data "assets\pelmeni_icon.png;assets" `
        --add-data "qml;qml" `
        --hidden-import "PySide6.QtQuick" `
        --hidden-import "PySide6.QtQuickControls2" `
        pelmeni_vpn_qt_final.pyw
    if ($LASTEXITCODE -ne 0) { throw "Qt application build failed" }

    & "dist\PelmeniVPN-Desktop.exe" --self-test
    if ($LASTEXITCODE -ne 0) { throw "Packaged self-test failed" }
} finally {
    Pop-Location
}
