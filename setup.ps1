# =====================================================================
# Papay TV Launcher - setup lengkap satu perintah untuk TV baru
# =====================================================================
# Hasilnya identik dengan TV-1: launcher glass + suara PS5 + wallpaper +
# screensaver + debloat 51 paket (disable saja, semua bisa dibalik).
#
# Pakai:
#   powershell -ExecutionPolicy Bypass -File setup.ps1 -TvHost 192.168.1.XXX
#
# Sebelum jalan:
#   1. TV harus sudah aktif "Network ADB debugging"/USB debugging
#      (TCL: Setelan > Perangkat > Preferensi > Info & Debugging, atau
#      tekan remote ke posisi developer). TV dan PC harus 1 jaringan Wi-Fi.
#   2. Edit nama sapaan di config.example.json, atau pakai -GreetingName.
#
# Timing penting: launcher DIPASANG dulu, dijadikan Home, baru paket-paket
# dimatikan - Home lama sampai surut dan ADB tidak boleh putus.
# =====================================================================

param(
    [Parameter(Mandatory = $true)][string]$TvHost,          # contoh: 192.168.1.50
    [string]$GreetingName = "",                             # kosong = pakai config.example.json apa adanya
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [switch]$SkipDebloat,
    [switch]$SkipWallpapers
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $MyInvocation.MyCommand.Path
$apk    = Join-Path $repo "mikhayla-glass-home-2.7.apk"
$cfgEx  = Join-Path $repo "config.example.json"
$pkgList = Join-Path $repo "debloat\packages-disabled.txt"

if (-not (Test-Path $Adb))   { Write-Host "adb tidak ketemu di $Adb"; exit 1 }
if (-not (Test-Path $apk))   { Write-Host "APK tidak ketemu"; exit 1 }
if (-not (Test-Path $cfgEx)) { Write-Host "config.example.json tidak ketemu"; exit 1 }
$dev = "${TvHost}:5555"

function Sh($cmd) {
    & $Adb -s $dev shell $cmd 2>&1 | ForEach-Object { $_.ToString() }
}

# ---------- 1. sambung & pasang launcher ----------
Write-Host "== 1/6 sambung $TvHost =="
& $Adb connect $dev | Out-Null
Start-Sleep -Seconds 2
$st = & $Adb -s $dev get-state 2>&1
if ("$st" -ne "device") { Write-Host "TV tidak tersambung ($st). Cek jaringan/AKon debugging."; exit 1 }

Write-Host "== 2/6 pasang APK =="
& $Adb -s $dev install -r $apk

Write-Host "== 3/6 push config =="
$cfgLocal = Join-Path $env:TEMP "mikhayla-config.json"
$cfgText = Get-Content $cfgEx -Raw
if ($GreetingName -ne "") {
    $cfgText = $cfgText -replace '"greeting_name": "NAMA KAMU"', ('"greeting_name": "' + $GreetingName + '"')
}
[System.IO.File]::WriteAllText($cfgLocal, $cfgText, (New-Object System.Text.UTF8Encoding($false)))
& $Adb -s $dev push $cfgLocal /sdcard/Android/data/com.gathin.tvhome/files/config.json

if (-not $SkipWallpapers) {
    Write-Host "== 4/6 push wallpapers =="
    Get-ChildItem (Join-Path $repo "wallpapers") -Directory | ForEach-Object {
        $dest = "/sdcard/Android/data/com.gathin.tvhome/files/wallpapers/" + $_.Name
        & $Adb -s $dev shell "mkdir -p '$dest'" | Out-Null
        & $Adb -s $dev push $_.FullName\* $dest | Out-Null
        Write-Host ("  pushed " + $_.Name)
    }
} else { Write-Host "== 4/6 dilewati (-SkipWallpapers) ==" }

Write-Host "== 5/6 jadikan Home =="
& $Adb -s $dev shell "input keyevent 3" | Out-Null
Start-Sleep -Seconds 3
Sh "cmd package set-home-activity com.gathin.tvhome/.MainActivity"
Sh "settings put secure screensaver_components com.gathin.tvhome/.ClockDream"
Sh "settings put secure screensaver_enabled 1"
# cek benar-benar fokus di launcher baru sebelum menyentuh paket lain
$focus = (Sh "dumpsys activity activities | grep -m1 mFocusedApp")
Write-Host "  fokus sekarang: $focus"
if ($focus -notlike "*com.gathin.tvhome*") {
    Write-Host "PERINGATAN: launcher baru belum jadi Home. Paket TIDAK di-disable. Coba lagi setelah reboot."
    exit 1
}

if (-not $SkipDebloat) {
    Write-Host "== 6/6 debloat (disable saja, bisa di-undo) =="
    $disabled = 0; $failed = @()
    Get-Content $pkgList | ForEach-Object {
        $pkg = ($_ -split '#')[0].Trim()
        if ($pkg -ne "") {
            $out = (Sh "pm disable-user --user 0 $pkg") -join " "
            if ($out -match "(?i)success|already") { $disabled++; Write-Host "  OK   $pkg" }
            else { $failed += $pkg; Write-Host "  SKIP $pkg -> $out" }
        }
    }
    Write-Host "  selesai: $disabled di-disable, $($failed.Count) dilewati"
    if ($failed.Count -gt 0) { Write-Host "  yang dilewati (tidak ada di TV ini, biasanya aman):"; $failed | ForEach-Object { "    $_" } }
} else { Write-Host "== 6/6 dilewati (-SkipDebloat) ==" }

Write-Host ""
Write-Host "Selesai. Reboot TV sekali, lalu cek:"
Write-Host "  - Home tampil glass + sapaan nama"
Write-Host "  - YouTube, Netflix, dan app inti masih jalan"
Write-Host "  - diam 5 menit di Home -> screensaver dengan wallpaper yang sama"
Write-Host "Balikin semuanya: powershell -ExecutionPolicy Bypass -File undo.ps1 -TvHost $TvHost"
