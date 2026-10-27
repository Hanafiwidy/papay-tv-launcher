# =====================================================================
# Papay TV Launcher - undo.ps1 : balik TV ke semula (stock)
# =====================================================================
# Meng-enable lagi semua paket yang dimatikan oleh setup.ps1
# (disable-user, jadi tidak ada yang hilang), dan menonaktifkan
# screensaver kustom. Stock launcher yang disable tinggal di-enable lagi
# lalu dipilih sebagai Home dari TV.
#
# Pakai:
#   powershell -ExecutionPolicy Bypass -File undo.ps1 -TvHost 192.168.1.XXX
# =====================================================================

param(
    [Parameter(Mandatory = $true)][string]$TvHost,
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $MyInvocation.MyCommand.Path
$pkgList = Join-Path $repo "debloat\packages-disabled.txt"
$dev = "${TvHost}:5555"

& $Adb connect $dev | Out-Null
Start-Sleep -Seconds 2

Get-Content $pkgList | ForEach-Object {
    $pkg = ($_ -split '#')[0].Trim()
    if ($pkg -ne "") {
        $out = (& $Adb -s $dev shell "pm enable $pkg" 2>&1) -join " "
        if ($out -match "(?i)success|already") { Write-Host "  OK   $pkg" }
        else { Write-Host "  SKIP $pkg -> $out" }
    }
}

& $Adb -s $dev shell "settings put secure screensaver_enabled 0" | Out-Null
& $Adb -s $dev shell "settings put secure screensaver_components com.android.systemui.somnus/.SomnusDream" | Out-Null

Write-Host "Selesai. Semua paket sudah di-enable lagi."
Write-Host "Set stock launcher sebagai Home: Buka TV > pilih Home bawaan, atau:"
Write-Host "  adb shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity"
