$ErrorActionPreference = "Stop"

$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Python = (Get-Command python).Source

Push-Location $ProjectDir
try {
    if (-not (Test-Path "dist\PelmeniVPN-Desktop.exe") -or -not (Test-Path "dist\PelmeniVPN-TunHelper.exe")) {
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\build-qt-final.ps1"
        if ($LASTEXITCODE -ne 0) { throw "Application build failed" }
    }
    $ReleaseDir = Join-Path $ProjectDir "..\release"
    $CustomTkinter = & $Python -c "import customtkinter, pathlib; print(pathlib.Path(customtkinter.__file__).parent)"
    & $Python -m PyInstaller `
        --noconfirm `
        --clean `
        --onefile `
        --windowed `
        --distpath "$ReleaseDir" `
        --name "PelmeniVPN-Windows-Setup-1.40-beta1" `
        --icon "assets\pelmeni_icon.ico" `
        --version-file "installer-version-info-qt.txt" `
        --add-data "assets\pelmeni_icon.png;assets" `
        --add-data "dist\PelmeniVPN-Desktop.exe;payload" `
        --add-data "dist\PelmeniVPN-TunHelper.exe;payload" `
        --add-data "dist\PelmeniVPN-Service.exe;payload" `
        --add-data "runtime;payload\runtime" `
        --add-data "$CustomTkinter;customtkinter" `
        installer_entry_qt.pyw
    if ($LASTEXITCODE -ne 0) { throw "Installer build failed" }

    $RootDir = Join-Path $ProjectDir ".."
    Copy-Item (Join-Path $ReleaseDir "PelmeniVPN-Windows-Setup-1.40-beta1.exe") (Join-Path $RootDir "PelmeniVPN-Windows-Setup-1.40-beta1.exe") -Force
} finally {
    Pop-Location
}
