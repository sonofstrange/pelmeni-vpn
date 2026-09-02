$ErrorActionPreference = "Stop"

$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Python = (Get-Command python).Source

Push-Location $ProjectDir
try {
    & $Python -m pip install --disable-pip-version-check -r requirements.txt
    & $Python -m PyInstaller `
        --noconfirm `
        --clean `
        --onefile `
        --windowed `
        --name "PelmeniVPN-Desktop" `
        --icon "assets\pelmeni_icon.ico" `
        --add-data "assets\pelmeni_icon.png;assets" `
        pelmeni_vpn.pyw
} finally {
    Pop-Location
}
