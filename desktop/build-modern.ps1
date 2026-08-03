$ErrorActionPreference = "Stop"

$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Python = (Get-Command python).Source

Push-Location $ProjectDir
try {
    & $Python -m pip install --disable-pip-version-check -r requirements.txt
    if ($LASTEXITCODE -ne 0) { throw "Dependency installation failed" }
    $CustomTkinter = & $Python -c "import customtkinter, pathlib; print(pathlib.Path(customtkinter.__file__).parent)"
    & $Python -m PyInstaller `
        --noconfirm `
        --clean `
        --onefile `
        --windowed `
        --name "PelmeniVPN-Desktop" `
        --icon "assets\pelmeni_icon.ico" `
        --version-file "app-version-info.txt" `
        --add-data "assets\pelmeni_icon.png;assets" `
        --add-data "$CustomTkinter;customtkinter" `
        pelmeni_vpn_modern.pyw
    if ($LASTEXITCODE -ne 0) { throw "Application build failed" }
} finally {
    Pop-Location
}
