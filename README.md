# RevisitApp — Aplikasi Pendataan Lapangan

Project Android Kotlin/Jetpack Compose dengan arsitektur offline-first:

- Room/SQLite menjadi sumber kebenaran lokal.
- WorkManager menangani antrean sinkronisasi batch.
- Google Apps Script menjadi API HTTPS.
- Google Spreadsheet menyimpan metadata dan Google Drive menyimpan foto.

## Menjalankan Android

Buka folder repository sebagai project Android Studio. Gunakan Gradle JDK 17
dan Android SDK 35. Konfigurasi endpoint serta token API satu kali pada
`local.properties` di root project; file ini diabaikan Git:

```properties
APPS_SCRIPT_URL=https://script.google.com/macros/s/DEPLOYMENT_ID/exec
APPS_SCRIPT_TOKEN=ISI_TOKEN_API_APPS_SCRIPT_DI_SINI
```

Setelah build ulang, layar login hanya meminta `id_petugas` dan PIN. URL serta
token diambil dari `BuildConfig`, kemudian disimpan terenkripsi oleh
`SyncConfigStore`. Logout hanya menghapus sesi petugas.

Alur form menyimpan record dan foto watermark ke Room/internal storage lebih
dahulu. `SyncWorker` mengunggah foto lalu metadata secara batch maksimal 20
record ketika jaringan tersedia. PIN mentah tidak disimpan; login offline
menggunakan digest SHA-256 terenkripsi.

## Menyiapkan Apps Script

1. Salin `Code.gs` dan `Dashboard.html` ke project Apps Script.
2. Isi Script Properties: `SPREADSHEET_ID`, `DRIVE_FOLDER_ID`, dan `API_TOKEN`.
3. Jalankan `setupBackend()` sekali.
4. Isi `master_wilayah` dan `petugas` sesuai header di bawah.
5. Deploy sebagai Web App dengan akses sesuai kebutuhan operasional.

### Struktur petugas

```text
id_petugas,nama,hash_pin,kode_kabupaten,kabupaten,aktif
```

`hash_pin` adalah SHA-256 hex lowercase; gunakan fungsi `hashPin("PIN")` dari
editor Apps Script. Setiap petugas aktif memiliki target 126 responden
(`14 SLS × 9 responden`) dan boleh memilih wilayah mana pun di kabupatennya.
Server menolak record yang berada di kabupaten lain.

### Struktur master wilayah

```text
kode_kab,kabupaten,kode_kec,nama_kec,kode_desa,nama_desa,
kode_sls,nama_sls,target_responden,versi_master
```

Simpan seluruh kode wilayah sebagai teks agar angka nol di depan tetap ada.
`target_responden` masih dipakai untuk rekap kecamatan/SLS, sedangkan target
operasional petugas tetap 126.

### Migrasi dari versi lama

Backup Spreadsheet terlebih dahulu, lalu jalankan `migrateBackendSchema()`
satu kali. Fungsi ini menghapus kolom centroid, kolom assignment lama, dan
sheet assignment legacy. Nilai `kode_kabupaten` serta `kabupaten` setiap
petugas harus diisi manual melalui dashboard karena tidak dapat disimpulkan
secara aman dari assignment lama.

Database Room Android naik ke versi 3 melalui migration yang mempertahankan
data lokal, menambahkan county pada `pendataan`, dan menghapus kolom centroid
dari tabel `wilayah`.

## Dashboard web dan laporan PDF

Dashboard memakai Spreadsheet yang sama:

```text
https://script.google.com/macros/s/DEPLOYMENT_ID/exec?page=dashboard&admin_token=DASHBOARD_TOKEN
```

Tambahkan Script Properties berikut:

```text
DASHBOARD_TOKEN = token dashboard, berbeda dari API_TOKEN
ADMIN_EMAILS = email admin dipisahkan koma
REPORT_FOLDER_ID = folder Drive laporan PDF (opsional)
```

Dashboard menampilkan target/realisasi per kabupaten, kecamatan, dan petugas;
menyediakan manajemen petugas, import master, serta laporan PDF per petugas.
Target kabupaten dihitung sebagai jumlah petugas aktif × 126. Laporan PDF
hanya memuat record yang sudah masuk ke Spreadsheet.

Setelah memperbarui Apps Script, deploy versi Web App terbaru. Build Android
di GitHub Actions dijalankan pada setiap push ke `main` dan pull request.
