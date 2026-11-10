# PROMPT.md - satu perintah untuk agen AI

Salin ini ke agen (Claude Code, Codex, Gemini CLI, OpenCode, Cursor, dll), ganti IP dan namanya:

> Debloat + pasang launcher di TV Android IP `<IP TV>`, sapaan `<nama>`, pakai repo
> https://github.com/Hanafiwidy/papay-tv-launcher. Ikuti `PROMPT.md` di repo itu sampai selesai,
> lalu laporkan hasil dan hal yang belum dicek.

Kalau repo belum ada di PC, agen men-clone dulu:

```powershell
git clone https://github.com/Hanafiwidy/papay-tv-launcher
cd papay-tv-launcher
```

## Aturan yang tidak boleh dilanggar

- Hanya `pm disable-user --user 0`. Jangan pernah `pm uninstall`, root, atau flash apa pun.
- Jangan disable paket inti Android: `com.android.*`, `com.android.systemui`, `com.android.settings`,
  `com.android.shell`, `com.android.phone`, `com.android.providers.*`,
  `com.google.android.overlay.modules*`, `android`.
- Launcher baru harus sudah jadi Home sebelum list debloat jalan. `setup.ps1` sudah mengatur urutan ini.
- Jangan menambah paket ke `examples/*/debloat.txt`. List itu sudah teruji per model.
- Berhenti dan lapor kalau TV menolak: `pm disable-user` gagal untuk semua paket, sideload gagal,
  atau Android di bawah 8 (SDK < 26).

## Yang dibutuhkan dari pengguna

- IP TV (contoh `192.168.1.50`) dan nama sapaan.
- TV dan PC di jaringan yang sama; **Network ADB debugging** / USB debugging aktif di TV; popup
  izin RSA di TV diterima.
- Windows + PowerShell + `adb` (Android platform-tools). Kalau `adb` tidak di lokasi default,
  tambahkan `-Adb "C:\path\ke\adb.exe"`.

## Langkah

1. Sambung: `adb connect <IP>:5555`, lalu `adb devices` - status harus `device`.
2. Baca TV:
   ```powershell
   adb shell getprop ro.product.model
   adb shell getprop ro.product.brand
   adb shell getprop ro.build.version.sdk
   adb shell getprop ro.build.fingerprint
   ```
3. Pilih list yang cocok: cari folder di `examples/` dengan brand + model yang sama, lalu baca
   `examples/<folder>/tv.json` (`tv`, `os`, `notes`). Contoh: TCL 65C735 -> `-List tcl-65c735`.
   Daftar semua model ada di `examples/README.md`.
   - Ragu atau tidak ada yang cocok: **jangan menebak**. Tanya pengguna, atau jalankan dengan
     `-SkipDebloat` (launcher + wallpaper saja) dan bilang debloat dilewati.
4. Sebelum debloat, tunjukkan aplikasi yang akan dimatikan (baris tanpa `#` di `debloat.txt`).
   Kalau ada yang dipakai keluarga, minta konfirmasi atau pakai `-SkipDebloat`.
5. Jalankan:
   ```powershell
   powershell -ExecutionPolicy Bypass -File setup.ps1 -TvHost <IP> -List <folder> -GreetingName "<nama>"
   ```
6. Cek: `adb shell dumpsys window | findstr mCurrentFocus` harus berisi `com.gathin.tvhome`.
   Buka Netflix/YouTube dari Home, tekan Home (harus balik ke launcher). Di Xiaomi/PatchWall, tekan
   tombol **Mi** di remote: harus balik ke launcher juga (setup mengaktifkan helper tombol Mi untuk
   `-List xiaomi-*`). Reboot TV sekali lalu ulangi cek Home, Wi-Fi, dan jam.
7. Kalau ada yang rusak: `powershell -ExecutionPolicy Bypass -File undo.ps1 -TvHost <IP> -List <folder>`.
8. Lapor: model TV, list yang dipakai, jumlah paket yang dimatikan, dan hasil cek setelah reboot.

## Kalau bukan Windows

Ikuti bagian **Manual install** di README (`adb install`, push config + wallpaper,
`cmd package set-home-activity`), lalu terapkan list dengan `pm disable-user --user 0` satu per satu.
Hasilnya sama; yang tidak ada hanya script pembungkusnya.

## Batas yang wajar

- Mengaktifkan ADB dan menerima izin RSA harus dilakukan di TV dengan remote - tidak bisa otomatis.
- Model yang belum ada di `examples/` tidak didebloat (launcher tetap bisa dipasang).
- Sapaan, wallpaper, dan pilihan dock diatur lewat `config.json`; default-nya milik TV pemilik repo.
