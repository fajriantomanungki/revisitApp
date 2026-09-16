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

Konfigurasi endpoint Apps Script dan token API diatur satu kali pada file
`local.properties` di root project. File ini diabaikan oleh Git:

```properties
APPS_SCRIPT_URL=https://script.google.com/macros/s/DEPLOYMENT_ID/exec
APPS_SCRIPT_TOKEN=ISI_TOKEN_API_APPS_SCRIPT_DI_SINI
MAPS_API_KEY=ISI_GOOGLE_MAPS_KEY_DI_SINI
```

Setelah konfigurasi tersebut tersedia dan aplikasi di-build ulang, layar login
hanya meminta `id_petugas` dan PIN. URL serta token dimasukkan otomatis melalui
`BuildConfig`, kemudian disimpan kembali secara terenkripsi melalui
`SyncConfigStore`. Saat logout, hanya sesi petugas yang dihapus sehingga URL
dan token tidak diminta lagi.

Token yang digunakan pada aplikasi mobile tetap dapat diekstrak dari APK oleh
pihak yang memiliki APK. Untuk MVP internal, batasi token dan pantau aksesnya;
untuk produksi, gunakan token sesi per petugas.

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

Header `master_wilayah` wajib memuat hierarki berikut:

```text
kode_kab, kabupaten, kode_kec, nama_kec, kode_desa, nama_desa,
kode_sls, nama_sls, target_responden, lat_centroid, lon_centroid, versi_master
```

Simpan seluruh kode wilayah sebagai teks agar angka nol di depan tidak hilang.
Setelah menambah atau mengubah master, naikkan nilai `versi_master` agar
perangkat yang sudah memiliki cache mengetahui bahwa master perlu diunduh ulang.
Versi aplikasi yang sudah memiliki database Room lama akan melakukan migration
otomatis untuk menambahkan kolom Kabupaten tanpa menghapus data lokal.

Build Android di GitHub Actions dijalankan pada setiap push ke `main` dan pull
request.
