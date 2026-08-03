$ErrorActionPreference = "Stop"

$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Python = (Get-Command python).Source
$App = Join-Path $ProjectDir "dist\PelmeniVPN-Desktop.exe"
if (-not (Test-Path -LiteralPath $App)) {
    throw "Build the desktop application first"
}

Push-Location $ProjectDir
try {
    $CustomTkinter = & $Python -c "import customtkinter, pathlib; print(pathlib.Path(customtkinter.__file__).parent)"
    & $Python -m PyInstaller `
        --noconfirm `
        --clean `
        --onefile `
        --windowed `
        --uac-admin `
        --name "PelmeniVPN-Desktop-Setup-1.32" `
        --icon "assets\pelmeni_icon.ico" `
        --version-file "installer-version-info.txt" `
        --add-data "assets\pelmeni_icon.png;assets" `
        --add-data "dist\PelmeniVPN-Desktop.exe;payload" `
        --add-data "$CustomTkinter;customtkinter" `
        installer_entry_release.pyw
    if ($LASTEXITCODE -ne 0) { throw "Installer build failed" }
} finally {
    Pop-Location
}
