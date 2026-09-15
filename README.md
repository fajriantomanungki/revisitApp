# RevisitApp — Aplikasi Pendataan Lapangan

Project Android Kotlin/Jetpack Compose dengan arsitektur offline-first:

- Room/SQLite sebagai sumber kebenaran lokal.
- WorkManager untuk antrean sinkronisasi.
- Google Apps Script sebagai API HTTPS.
- Google Spreadsheet untuk metadata dan Google Drive untuk foto.

## Menjalankan Android

Buka folder repository ini sebagai project Android Studio. Atau gunakan Gradle
8.9 dengan JDK 17:

```bash
gradle --no-daemon assembleDebug
```

Jika memakai Google Maps, isi `MAPS_API_KEY` melalui `local.properties` atau
property Gradle. Jangan commit key asli.

Konfigurasi endpoint, token API, dan `id_petugas` disimpan melalui
`SyncConfigStore` menggunakan `EncryptedSharedPreferences`.

Jalur form F-04 menyimpan record dan foto watermark ke Room/internal storage
lebih dahulu. CameraX hanya menulis file sementara di `cacheDir`; file itu
langsung diproses Canvas + EXIF lalu file aslinya dihapus. `SyncWorker` baru
mengunggah foto watermark setelah record berstatus `SIAP_KIRIM`.

Untuk login online, panggil action `login` dengan `id_petugas` dan PIN; setelah
berhasil gunakan `SyncConfigStore.saveAuthenticated(...)`. PIN tidak disimpan,
hanya digest SHA-256 terenkripsi untuk validasi login offline.

## Menyiapkan Apps Script

1. Buka `Code.gs` pada project Apps Script.
2. Isi Script Properties: `SPREADSHEET_ID`, `DRIVE_FOLDER_ID`, dan `API_TOKEN`.
3. Jalankan `setupBackend()` satu kali.
4. Isi sheet `petugas` dan `master_wilayah` sesuai header yang dibuat.
5. Deploy sebagai Web App dengan akses sesuai kebutuhan operasional.

Build Android di GitHub Actions dijalankan pada setiap push ke `main` dan pull
request.
