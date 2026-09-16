# Integrasi Dashboard Progress

Dashboard admin menggunakan Spreadsheet yang sama dengan API Android.
Perubahan ini tidak membuat sumber data baru dan tidak mengubah alur
offline-first pada aplikasi.

## Isi dashboard

- Progress total per kabupaten.
- Progress per kecamatan.
- Progress per petugas.
- Input dan pembaruan petugas.
- Import master wilayah Kabupaten–Kecamatan–Desa–SLS.
- Pembuatan laporan PDF per petugas.
- Login dashboard berbasis username/password dan pendaftaran admin baru.
- Pembuatan laporan kegiatan harian yang menggabungkan rangkuman, hasil
  pendataan, tanggal, dan foto.

Target seorang petugas aktif adalah 126 responden (`14 SLS × 9 responden`).
Target kabupaten dihitung dari jumlah petugas aktif dikalikan 126, sedangkan
realisasi adalah jumlah record pendataan yang sudah diterima Spreadsheet.

## Scope petugas

Sheet `petugas` wajib memiliki kolom:

```text
id_petugas,nama,hash_pin,kode_kabupaten,kabupaten,aktif
```

Petugas boleh memilih wilayah secara mandiri, tetapi hanya pada kabupaten
yang sama dengan `kode_kabupaten` miliknya. Validasi dilakukan di UI Android
dan di server Apps Script.

## Data master

Header `master_wilayah` yang digunakan:

```text
kode_kab,kabupaten,kode_kec,nama_kec,kode_desa,nama_desa,
kode_sls,nama_sls,target_responden,versi_master
```

Kolom centroid dan sheet penugasan tidak lagi digunakan. Setelah backup
Spreadsheet lama, jalankan `migrateBackendSchema()` sekali untuk menghapus
kolom legacy serta sheet `penugasan`, lalu isi kabupaten setiap petugas pada
menu Manajemen Petugas.

## Sinkronisasi cache Android

`CakupanSyncRepository` mengambil rekap server melalui `GET action=cakupan`
dan menyimpannya pada `cakupan_cache`. Dashboard Android menambahkan record
lokal berstatus `SIAP_KIRIM`, `MENGIRIM`, atau `GAGAL`, sehingga angka progres
tetap informatif ketika perangkat offline.

Contoh penghubungan:

```kotlin
val snapshot by dashboardCoverageRepository
    .observe(idPetugas)
    .collectAsState(initial = DashboardCoverageSnapshot())

DashboardScreen(
    snapshot = snapshot,
    onRefreshCoverage = { /* refresh cache server */ }
)
```

Tidak ada dependensi Google Maps pada modul dashboard Android.

## Laporan kegiatan harian

Android menyimpan satu rangkuman per petugas dan tanggal pada Room table
`laporan_kegiatan`. Tombol **Simpan & Kirim** memasukkannya ke antrean
WorkManager. Apps Script meng-upsert data tersebut ke sheet `laporan_kegiatan`
melalui action POST `sync_laporan_kegiatan`.

Pada dashboard, buka **Laporan PDF → Laporan Kegiatan Harian**, pilih petugas
dan periode, lalu buat PDF. File final dibuat melalui Google Docs sementara,
diekspor sebagai PDF, dan disimpan pada `REPORT_FOLDER_ID` (atau folder foto
sebagai fallback). Foto hanya diambil dari file Drive yang berada pada folder
foto terkonfigurasi.

## Login dan admin dashboard

Jalankan `setupBackend()` atau `migrateBackendSchema()` satu kali setelah
menyalin kode terbaru. Jika sheet `admin` masih kosong, sistem membuat akun:

```text
username: manungki.fajri
password: 1234
```

Setelah masuk, gunakan menu **Admin** untuk mendaftarkan akun tambahan. Password
disimpan sebagai SHA-256 pada Spreadsheet; password mentah tidak dikembalikan
ke browser.
