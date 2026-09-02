$ErrorActionPreference = "Stop"

$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Python = (Get-Command python).Source

Push-Location $ProjectDir
try {
    & $Python -m PyInstaller `
        --noconfirm `
        --clean `
        --onefile `
        --windowed `
        --name "PelmeniVPN-TunHelper" `
        --icon "assets\pelmeni_icon.ico" `
        --version-file "tun-helper-version-info.txt" `
        --add-data "runtime;runtime" `
        tun_helper_entry.py
    if ($LASTEXITCODE -ne 0) { throw "VPN helper build failed" }
} finally {
    Pop-Location
}
