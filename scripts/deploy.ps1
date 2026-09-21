# deploy.ps1 - Unified build/install entry for CAM-P (Android-Agent).
#
# Usage: .\scripts\deploy.ps1 <action>
#   start     - build debug APK (old dist APK backed up to dist\clean-<ts>\), copy to dist\
#   install   - adb install -r dist\app-debug.apk
#   uninstall - adb uninstall the debug package
#   clean     - remove dist\
#   verify    - gradle dry-run of the debug build (no compile)
#
# Notes:
#   - ASCII-only content (PowerShell 5.1 reads non-BOM UTF-8 as ANSI; keep it simple).
#   - No credentials ever belong in this file.
param(
    [Parameter(Position = 0)]
    [ValidateSet("start", "install", "uninstall", "clean", "verify")]
    [string]$Action = "start"
)

$ErrorActionPreference = "Stop"
$Root     = Split-Path -Parent $PSScriptRoot
$Dist     = Join-Path $Root "dist"
$ApkName  = "app-debug.apk"
$Apk      = Join-Path $Dist $ApkName
$PkgDebug = "com.rickeal.agent.debug"

function Get-Timestamp { Get-Date -Format "yyyyMMdd-HHmmss" }

switch ($Action) {
    "start" {
        New-Item -ItemType Directory -Force -Path $Dist | Out-Null
        if (Test-Path $Apk) {
            $bak = Join-Path $Dist ("clean-" + (Get-Timestamp))
            New-Item -ItemType Directory -Force -Path $bak | Out-Null
            Move-Item $Apk $bak
            Write-Host "Previous APK moved to $bak"
        }
        Push-Location $Root
        try {
            & .\gradlew.bat ":app:assembleDebug" --no-daemon
            if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        } finally {
            Pop-Location
        }
        $apkOut = Get-ChildItem -Path (Join-Path $Root "app\build\outputs\apk\debug") -Filter *.apk |
            Select-Object -First 1
        Copy-Item $apkOut.FullName $Apk -Force
        Write-Host "APK copied to dist\$ApkName"
    }
    "install" {
        if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
            Write-Error "adb not found in PATH"
            exit 1
        }
        if (-not (Test-Path $Apk)) {
            Write-Error "dist\$ApkName not found. Run 'start' first."
            exit 1
        }
        adb install -r $Apk
    }
    "uninstall" {
        adb uninstall $PkgDebug
        if ($LASTEXITCODE -ne 0) { Write-Host "(not installed, skipped)" }
    }
    "clean" {
        if (Test-Path $Dist) { Remove-Item -Recurse -Force $Dist }
        Write-Host "dist\ removed"
    }
    "verify" {
        Push-Location $Root
        try {
            & .\gradlew.bat ":app:assembleDebug" --dry-run --no-daemon
        } finally {
            Pop-Location
        }
    }
}
