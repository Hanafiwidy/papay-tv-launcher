# =====================================================================
# Papay TV Launcher - setup lengkap satu perintah untuk TV baru
# =====================================================================
# Launcher glass + suara PS5 + 25 wallpaper 4K + screensaver, plus BitChord TV
# (pemutar untuk kartu now-playing) dan debloat per model TV (disable saja,
# semua bisa dibalik).
#
# Pakai:
#   powershell -ExecutionPolicy Bypass -File setup.ps1 -TvHost 192.168.1.XXX -List tcl-65c735
#
# -List = nama folder di examples\ (lihat examples\README.md untuk daftar TV
# yang sudah teruji). Tanpa -List, dipakai debloat\packages-disabled.txt (TV-1).
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
    [string]$List = "",                                     # nama folder di examples\ atau path file list
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [switch]$SkipDebloat,
    [switch]$SkipWallpapers
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $MyInvocation.MyCommand.Path
$apk    = Join-Path $repo "mikhayla-glass-home-2.7.apk"
# BitChord TV (GPL-3.0, github.com/kushagrasinghx/BitChord): the player the now-playing card reads.
$bitchord = Join-Path $repo "BitChordTV-v1.9.1_armeabi-v7a.apk"
$cfgEx  = Join-Path $repo "config.example.json"
# Daftar debloat: -List bisa nama folder examples\ (mis. tcl-65c735) atau path file.
$pkgList = Join-Path $repo "debloat\packages-disabled.txt"
if ($List -ne "") {
    if (Test-Path $List) { $pkgList = (Resolve-Path $List).Path }
    else { $pkgList = Join-Path $repo ("examples\" + $List + "\debloat.txt") }
}

if (-not (Test-Path $Adb))   { Write-Host "adb tidak ketemu di $Adb"; exit 1 }
if (-not (Test-Path $apk))   { Write-Host "APK tidak ketemu"; exit 1 }
if (-not (Test-Path $cfgEx)) { Write-Host "config.example.json tidak ketemu"; exit 1 }
if (-not $SkipDebloat -and -not (Test-Path $pkgList)) {
    Write-Host "Daftar debloat tidak ketemu: $pkgList"
    Write-Host "Pilih folder dari examples\ (mis. -List tcl-65c735); daftar TV ada di examples\README.md"
    exit 1
}
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
# Player for the now-playing card. Skipped if the file is gone; the card then says "BitChord not installed".
if (Test-Path $bitchord) { & $Adb -s $dev install -r $bitchord }

Write-Host "== 3/6 push config =="
$cfgLocal = Join-Path $env:TEMP "mikhayla-config.json"
$cfgText = Get-Content $cfgEx -Raw
if ($GreetingName -ne "") {
    $cfgText = $cfgText -replace '"greeting_name":\s*"[^"]*"', ('"greeting_name": "' + $GreetingName + '"')
}
# Buang dock yang tidak terpasang di TV ini; tile aplikasi yang tidak ada tidak bisa dibuka.
$installed = (Sh "pm list packages") | ForEach-Object { $_ -replace '^package:', '' }
$cfg = $cfgText | ConvertFrom-Json
$cfg.dock_apps = @($cfg.dock_apps | Where-Object { $installed -contains $_ })
$cfgText = $cfg | ConvertTo-Json -Depth 6
[System.IO.File]::WriteAllText($cfgLocal, $cfgText, (New-Object System.Text.UTF8Encoding($false)))
& $Adb -s $dev push $cfgLocal /sdcard/Android/data/com.gathin.tvhome/files/config.json

# Nama Wi-Fi di pill (Android 10+ butuh izin lokasi; tanpa ini pill cuma bilang "Wi-Fi").
& $Adb -s $dev shell "pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION" | Out-Null
& $Adb -s $dev shell "settings put secure location_mode 3" | Out-Null

if (-not $SkipWallpapers) {
    Write-Host "== 4/6 push wallpapers =="
    Get-ChildItem (Join-Path $repo "wallpapers") -Directory | ForEach-Object {
        $dest = "/sdcard/Android/data/com.gathin.tvhome/files/wallpapers"
        & $Adb -s $dev shell "mkdir -p '$dest'" | Out-Null
        Get-ChildItem $_.FullName -File | ForEach-Object {
            & $Adb -s $dev push $_.FullName $dest | Out-Null
        }
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
$focus = (Sh "dumpsys activity activities | grep mFocusedApp") -join " "
Write-Host "  fokus sekarang: $focus"
if ($focus -notlike "*com.gathin.tvhome*") {
    # Android 11 Google TV: tvlauncher sering punya priority lebih tinggi, jadi set-home-activity
    # kalah. Matikan dulu (disable-user, bisa dibalik), lalu cek lagi.
    Write-Host "  stock launcher masih menang; menonaktifkan com.google.android.tvlauncher (bisa dibalik)"
    & $Adb -s $dev shell "pm disable-user --user 0 com.google.android.tvlauncher" | Out-Null
    & $Adb -s $dev shell "input keyevent 3" | Out-Null
    Start-Sleep -Seconds 3
    $focus = (Sh "dumpsys activity activities | grep mFocusedApp") -join " "
    Write-Host "  fokus sekarang: $focus"
}
if ($focus -notlike "*com.gathin.tvhome*") {
    Write-Host "PERINGATAN: launcher baru belum jadi Home. Paket TIDAK di-disable. Coba lagi setelah reboot."
    exit 1
}

# Xiaomi/PatchWall: firmware mengirim tombol Mi (192) ke PatchWall sebelum filter Android.
# Hidupkan helper bawaan launcher: 192 -> Home, dan PatchWall yang muncul tetap dilempar balik ke sini.
if ($List -like "*xiaomi*") {
    Write-Host "  Xiaomi: aktifkan helper tombol Mi/PatchWall"
    $svc = @()
    $cur = (((Sh "settings get secure enabled_accessibility_services") -join "")).Trim()
    if ($cur -ne "" -and $cur -ne "null") { $svc = $cur -split ":" }
    foreach ($s in @("com.gathin.tvhome/.HomeKeyService", "com.gathin.tvhome/.InputKeyService")) {
        if ($svc -notcontains $s) { $svc += $s }
    }
    Sh ("settings put secure enabled_accessibility_services " + ($svc -join ":")) | Out-Null
    Sh "settings put secure accessibility_enabled 1" | Out-Null
}

if (-not $SkipDebloat) {
    Write-Host "== 6/6 debloat dari $pkgList (disable saja, bisa di-undo) =="
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
$undoHint = "Balikin semuanya: powershell -ExecutionPolicy Bypass -File undo.ps1 -TvHost $TvHost"
if ($List -ne "") { $undoHint += " -List $List" }
Write-Host $undoHint
