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

    Write-Host "Building PelmeniVPN-Service.exe..."
    & $Python -m PyInstaller `
        --noconfirm `
        --clean `
        --onefile `
        --name "PelmeniVPN-Service" `
        --icon "assets\pelmeni_icon.ico" `
        --add-data "runtime;runtime" `
        --exclude-module "tkinter" `
        --exclude-module "PySide6" `
        --exclude-module "matplotlib" `
        --exclude-module "PIL" `
        --exclude-module "numpy" `
        service_entry.py
    if ($LASTEXITCODE -ne 0) { throw "VPN Service build failed" }

    $InnoCandidates = @(
        "C:\tmp\InnoSetup\ISCC.exe",
        "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe",
        "C:\Program Files (x86)\Inno Setup 6\ISCC.exe",
        "C:\Program Files\Inno Setup 6\ISCC.exe"
    )
    $Iscc = $InnoCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if ($Iscc) {
        & $Iscc "installer.iss"
        if ($LASTEXITCODE -ne 0) { throw "Inno Setup build failed" }
    } else {
        Write-Host "Inno Setup not found, building standalone setup via PyInstaller..."
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\build-installer-final.ps1"
        if ($LASTEXITCODE -ne 0) { throw "Installer build failed" }
    }

    $ReleaseDir = Join-Path $ProjectDir "..\release"
    New-Item -ItemType Directory -Force -Path $ReleaseDir | Out-Null
    try {
        Copy-Item "dist\PelmeniVPN-Desktop.exe" (Join-Path $ReleaseDir "PelmeniVPN-Desktop-1.40.exe") -Force
    } catch {
        Write-Warning "Could not overwrite release\PelmeniVPN-Desktop-1.40.exe (file locked)"
    }
    try {
        Copy-Item "dist\PelmeniVPN-TunHelper.exe" (Join-Path $ReleaseDir "PelmeniVPN-TunHelper.exe") -Force
    } catch {
        Write-Warning "Could not overwrite release\PelmeniVPN-TunHelper.exe"
    }
    try {
        Copy-Item "dist\PelmeniVPN-Service.exe" (Join-Path $ReleaseDir "PelmeniVPN-Service.exe") -Force
    } catch {
        Write-Warning "Could not overwrite release\PelmeniVPN-Service.exe"
    }
    if (Test-Path "dist\PelmeniVPN-Windows-Setup-1.40-beta1.exe") {
        try {
            Copy-Item "dist\PelmeniVPN-Windows-Setup-1.40-beta1.exe" (Join-Path $ReleaseDir "PelmeniVPN-Windows-Setup-1.40-beta1.exe") -Force
        } catch {
            Write-Warning "Could not overwrite release\PelmeniVPN-Windows-Setup-1.40-beta1.exe"
        }
    }

    $PortableZipDir = Join-Path $env:TEMP "PelmeniVPN-Windows-v1.40-beta1"
    if (Test-Path $PortableZipDir) { Remove-Item -Recurse -Force $PortableZipDir }
    New-Item -ItemType Directory -Force -Path $PortableZipDir | Out-Null
    Copy-Item "dist\PelmeniVPN-Desktop.exe" $PortableZipDir -Force
    Copy-Item "dist\PelmeniVPN-TunHelper.exe" $PortableZipDir -Force
    Copy-Item "dist\PelmeniVPN-Service.exe" $PortableZipDir -Force
    Copy-Item "runtime" (Join-Path $PortableZipDir "runtime") -Recurse -Force

    $ZipTarget = Join-Path $ReleaseDir "PelmeniVPN-Windows-v1.40-beta1.zip"
    if (Test-Path $ZipTarget) { Remove-Item -Force $ZipTarget }
    Compress-Archive -Path "$PortableZipDir\*" -DestinationPath $ZipTarget -Force
    Write-Host "Release packaging completed: $ZipTarget"
} finally {
    Pop-Location
}
