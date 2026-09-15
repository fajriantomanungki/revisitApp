# Integrasi Dashboard F-07

## File yang ditambahkan

- `domain/coverage/CoverageCalculator.kt`
  - Lima kelas warna F-07.
  - Kalkulasi `jumlah_terdata / target_responden`.
  - Penggabungan snapshot server dan data lokal belum terkirim.
- `data/dashboard/DashboardCoverageRepository.kt`
  - Menggabungkan `wilayah`, `cakupan_cache`, dan agregat lokal dari Room
    secara reaktif.
- `feature/dashboard/DashboardScreen.kt`
  - Peta marker centroid, legenda, filter kecamatan/desa, toggle penugasan,
    detail SLS, dan daftar 10 SLS dengan cakupan terendah.

## Dependensi peta

Tambahkan pada `app` module:

````kotlin
implementation("com.google.maps.android:maps-compose:8.4.0")
````

API key Maps SDK perlu didaftarkan pada manifest, misalnya:

````xml
<meta-data
    android:name="com.google.android.geo.API_KEY"
    android:value="${MAPS_API_KEY}" />
````

Simpan nilai key melalui `local.properties`/secret Gradle. Jangan commit key
asli ke repository.

## Contoh penghubungan Room ke layar

````kotlin
val snapshot by dashboardCoverageRepository
    .observe(idPetugas = idPetugas)
    .collectAsState(
        initial = DashboardCoverageSnapshot(
            coverage = emptyList(),
            lastServerSyncMillis = null
        )
    )

DashboardScreen(
    snapshot = snapshot,
    assignedSlsCodes = assignedSlsCodes,
    onNavigate = { sls ->
        // Buka Google Maps menggunakan sls.latCentroid/lonCentroid.
    },
    onOpenSlsData = { kodeSls ->
        // Navigasi ke daftar pendataan pada SLS tersebut.
    },
    onRefreshCoverage = {
        // Ambil GET action=cakupan, lalu cakupanCacheDao.replaceAll(...).
    }
)
````

`observeLocalCoverage()` hanya menghitung status `SIAP_KIRIM`, `MENGIRIM`,
dan `GAGAL`. Status `TERKIRIM` tidak ditambahkan lagi karena sudah tercermin
di snapshot server.
