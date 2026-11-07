# =====================================================================
# Papay TV Launcher - undo.ps1 : balik TV ke semula (stock)
# =====================================================================
# Meng-enable lagi semua paket yang dimatikan oleh setup.ps1
# (disable-user, jadi tidak ada yang hilang), dan menonaktifkan
# screensaver kustom. Stock launcher yang disable tinggal di-enable lagi
# lalu dipilih sebagai Home dari TV.
#
# Pakai:
#   powershell -ExecutionPolicy Bypass -File undo.ps1 -TvHost 192.168.1.XXX -List tcl-65c735
#   (-List sama dengan yang dipakai saat setup.ps1; tanpa -List pakai debloat\packages-disabled.txt)
# =====================================================================

param(
    [Parameter(Mandatory = $true)][string]$TvHost,
    [string]$List = "",
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $MyInvocation.MyCommand.Path
$pkgList = Join-Path $repo "debloat\packages-disabled.txt"
if ($List -ne "") {
    if (Test-Path $List) { $pkgList = (Resolve-Path $List).Path }
    else { $pkgList = Join-Path $repo ("examples\" + $List + "\debloat.txt") }
}
$dev = "${TvHost}:5555"

& $Adb connect $dev | Out-Null
Start-Sleep -Seconds 2

& $Adb -s $dev shell "pm enable com.google.android.tvlauncher" | Out-Null

if (Test-Path $pkgList) {
    Get-Content $pkgList | ForEach-Object {
        $pkg = ($_ -split '#')[0].Trim()
        if ($pkg -ne "") {
            $out = (& $Adb -s $dev shell "pm enable $pkg" 2>&1) -join " "
            if ($out -match "(?i)success|already") { Write-Host "  OK   $pkg" }
            else { Write-Host "  SKIP $pkg -> $out" }
        }
    }
} else {
    Write-Host "  Daftar tidak ketemu: $pkgList"
    Write-Host "  Paket dilewati. Pakai -List yang sama seperti saat setup.ps1."
}

& $Adb -s $dev shell "settings put secure screensaver_enabled 0" | Out-Null
& $Adb -s $dev shell "settings put secure screensaver_components com.android.systemui.somnus/.SomnusDream" | Out-Null

Write-Host "Selesai. Semua paket sudah di-enable lagi."
Write-Host "Set stock launcher sebagai Home: Buka TV > pilih Home bawaan, atau:"
Write-Host "  adb shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity"
