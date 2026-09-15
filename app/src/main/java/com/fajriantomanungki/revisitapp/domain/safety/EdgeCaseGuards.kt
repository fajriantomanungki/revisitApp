package com.fajriantomanungki.revisitapp.domain.safety

import android.location.Location
import androidx.core.location.LocationCompat
import com.fajriantomanungki.revisitapp.data.local.dao.PendataanDao
import com.fajriantomanungki.revisitapp.data.local.dao.PendataanStatusCount
import com.fajriantomanungki.revisitapp.data.local.model.SyncStatus
import com.fajriantomanungki.revisitapp.domain.location.CapturedLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Guard edge-case yang diminta pada PRD §12:
 *
 * 1. Lokasi mock/fake GPS diberi flag audit dan peringatan, tetapi tidak
 *    diblokir diam-diam. Record tetap dapat disimpan dengan isMock=true.
 * 2. Logout F-01.4 hanya meneruskan proses pembersihan sesi jika tidak ada
 *    data yang belum terkirim milik petugas pada Room. Minimal DRAFT dan
 *    SIAP_KIRIM diblokir; MENGIRIM dan GAGAL juga diblokir karena belum
 *    memperoleh konfirmasi TERKIRIM dari server.
 *
 * File ini dapat ditempatkan langsung pada source set Android app. Ia memakai
 * AndroidX Core, Kotlin Coroutines, Hilt, dan DAO yang sudah ada di proyek.
 */

data class SlsCentroid(
    val latitude: Double,
    val longitude: Double
)

/**
 * maxCentroidDistanceMeters sengaja nullable karena PRD belum menentukan
 * radius universal. Isi berdasarkan karakteristik SLS sebelum mengaktifkan
 * cross-check; nilai terlalu kecil dapat menandai lokasi valid secara keliru.
 */
data class LocationAuditPolicy(
    val accuracyWarningMeters: Double = 50.0,
    val maxCentroidDistanceMeters: Double? = null
) {
    init {
        require(accuracyWarningMeters > 0.0) {
            "accuracyWarningMeters harus lebih besar dari 0"
        }
        require(
            maxCentroidDistanceMeters == null ||
                maxCentroidDistanceMeters > 0.0
        ) {
            "maxCentroidDistanceMeters harus null atau lebih besar dari 0"
        }
    }
}

enum class LocationWarning {
    INVALID_COORDINATE,
    MOCK_LOCATION,
    LOW_ACCURACY,
    OUTSIDE_CENTROID_RADIUS
}

data class LocationAuditResult(
    val isMock: Boolean,
    val accuracyM: Double?,
    val centroidDistanceM: Double?,
    val hasValidCoordinates: Boolean,
    val warnings: List<LocationWarning>,
    val warningMessage: String?
) {
    /**
     * Mock GPS tidak membuat record ditolak. Hanya koordinat yang tidak valid
     * yang membuat hasil lokasi tidak layak dipakai oleh form.
     */
    val canSaveRecord: Boolean
        get() = hasValidCoordinates

    val hasFakeGpsWarning: Boolean
        get() = LocationWarning.MOCK_LOCATION in warnings
}

/**
 * Pemeriksa integritas lokasi.
 *
 * Gunakan hasil [isMock] ketika membentuk PendataanEntity:
 * PendataanEntity(..., isMock = audit.isMock)
 *
 * Jika hasil memiliki peringatan MOCK_LOCATION, tampilkan
 * [LocationAuditResult.warningMessage] lalu tetap simpan record untuk audit.
 */
class LocationIntegrityChecker(
    private val policy: LocationAuditPolicy = LocationAuditPolicy()
) {

    /**
     * Jalur utama ketika objek Location masih tersedia dari Fused Location.
     * LocationCompat.isMock() dipakai agar pemeriksaan konsisten lintas versi
     * Android, termasuk perangkat yang belum menyediakan API langsungnya.
     */
    fun inspect(
        location: Location,
        centroid: SlsCentroid? = null
    ): LocationAuditResult {
        return inspectValues(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyM = if (location.hasAccuracy()) {
                location.accuracy.toDouble()
            } else {
                null
            },
            isMock = LocationCompat.isMock(location),
            centroid = centroid
        )
    }

    /**
     * Jalur ketika lokasi sudah diubah oleh LocationHelper menjadi
     * CapturedLocation.
     */
    fun inspect(
        location: CapturedLocation,
        centroid: SlsCentroid? = null
    ): LocationAuditResult {
        return inspectValues(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyM = location.accuracyM,
            isMock = location.isMock,
            centroid = centroid
        )
    }

    private fun inspectValues(
        latitude: Double,
        longitude: Double,
        accuracyM: Double?,
        isMock: Boolean,
        centroid: SlsCentroid?
    ): LocationAuditResult {
        val hasValidCoordinates = isValidCoordinate(latitude, longitude)
        val warnings = mutableListOf<LocationWarning>()

        if (!hasValidCoordinates) {
            warnings += LocationWarning.INVALID_COORDINATE
        }
        if (isMock) {
            warnings += LocationWarning.MOCK_LOCATION
        }
        if (accuracyM != null &&
            accuracyM > policy.accuracyWarningMeters
        ) {
            warnings += LocationWarning.LOW_ACCURACY
        }

        val centroidDistanceM = if (
            hasValidCoordinates &&
            centroid != null &&
            isValidCoordinate(centroid.latitude, centroid.longitude) &&
            policy.maxCentroidDistanceMeters != null
        ) {
            haversineDistanceMeters(
                latitude1 = latitude,
                longitude1 = longitude,
                latitude2 = centroid.latitude,
                longitude2 = centroid.longitude
            )
        } else {
            null
        }

        if (
            centroidDistanceM != null &&
            policy.maxCentroidDistanceMeters != null &&
            centroidDistanceM > policy.maxCentroidDistanceMeters
        ) {
            warnings += LocationWarning.OUTSIDE_CENTROID_RADIUS
        }

        return LocationAuditResult(
            isMock = isMock,
            accuracyM = accuracyM,
            centroidDistanceM = centroidDistanceM,
            hasValidCoordinates = hasValidCoordinates,
            warnings = warnings,
            warningMessage = createWarningMessage(
                warnings = warnings,
                accuracyM = accuracyM,
                centroidDistanceM = centroidDistanceM
            )
        )
    }

    private fun createWarningMessage(
        warnings: List<LocationWarning>,
        accuracyM: Double?,
        centroidDistanceM: Double?
    ): String? {
        if (warnings.isEmpty()) {
            return null
        }

        return warnings.joinToString(separator = " ") { warning ->
            when (warning) {
                LocationWarning.INVALID_COORDINATE ->
                    "Koordinat lokasi tidak valid; ambil titik ulang."

                LocationWarning.MOCK_LOCATION ->
                    "Lokasi terindikasi Fake GPS. Data tetap disimpan untuk audit."

                LocationWarning.LOW_ACCURACY ->
                    "Akurasi lokasi rendah (" +
                        formatMeters(accuracyM ?: 0.0) +
                        "); ambil titik ulang bila memungkinkan."

                LocationWarning.OUTSIDE_CENTROID_RADIUS ->
                    "Lokasi berjarak " +
                        formatMeters(centroidDistanceM ?: 0.0) +
                        " dari centroid SLS; periksa kembali wilayah."
            }
        }
    }

    private fun formatMeters(distanceM: Double): String {
        return if (distanceM >= 1_000.0) {
            String.format(Locale.US, "%.2f km", distanceM / 1_000.0)
        } else {
            String.format(Locale.US, "%.0f m", distanceM)
        }
    }

    private fun isValidCoordinate(
        latitude: Double,
        longitude: Double
    ): Boolean {
        return latitude.isFinite() &&
            longitude.isFinite() &&
            latitude in -90.0..90.0 &&
            longitude in -180.0..180.0
    }

    private fun haversineDistanceMeters(
        latitude1: Double,
        longitude1: Double,
        latitude2: Double,
        longitude2: Double
    ): Double {
        val earthRadiusM = 6_371_000.0
        val latitudeDelta = Math.toRadians(latitude2 - latitude1)
        val longitudeDelta = Math.toRadians(longitude2 - longitude1)
        val firstLatitude = Math.toRadians(latitude1)
        val secondLatitude = Math.toRadians(latitude2)
        val a = (
            sin(latitudeDelta / 2.0) * sin(latitudeDelta / 2.0)
                + cos(firstLatitude) *
                cos(secondLatitude) *
                sin(longitudeDelta / 2.0) *
                sin(longitudeDelta / 2.0)
            ).coerceIn(0.0, 1.0)

        return earthRadiusM * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    }
}

data class LogoutCheckResult(
    val canLogout: Boolean,
    val draftCount: Long,
    val siapKirimCount: Long,
    val mengirimCount: Long,
    val gagalCount: Long,
    val warningMessage: String?
) {
    val blockedEntryCount: Long
        get() = draftCount + siapKirimCount + mengirimCount + gagalCount
}

/**
 * F-01.4 logout guard.
 *
 * Panggil logoutIfAllowed() dari event tombol Logout. Callback clearSession
 * hanya dieksekusi jika pemeriksaan Room mengizinkan logout.
 */
class LogoutGuard @Inject constructor(
    private val pendataanDao: PendataanDao
) {

    suspend fun check(idPetugas: String): LogoutCheckResult {
        if (idPetugas.isBlank()) {
            return LogoutCheckResult(
                canLogout = false,
                draftCount = 0L,
                siapKirimCount = 0L,
                mengirimCount = 0L,
                gagalCount = 0L,
                warningMessage =
                    "Logout ditolak karena identitas petugas tidak valid."
            )
        }

        val summary = withContext(Dispatchers.IO) {
            pendataanDao.observeStatusSummary(idPetugas).first()
        }
        val draftCount = summary.countFor(SyncStatus.DRAFT)
        val siapKirimCount = summary.countFor(SyncStatus.SIAP_KIRIM)
        val mengirimCount = summary.countFor(SyncStatus.MENGIRIM)
        val gagalCount = summary.countFor(SyncStatus.GAGAL)

        if (
            draftCount == 0L &&
            siapKirimCount == 0L &&
            mengirimCount == 0L &&
            gagalCount == 0L
        ) {
            return LogoutCheckResult(
                canLogout = true,
                draftCount = 0L,
                siapKirimCount = 0L,
                mengirimCount = 0L,
                gagalCount = 0L,
                warningMessage = null
            )
        }

        val pendingParts = mutableListOf<String>()
        if (draftCount > 0L) {
            pendingParts += draftCount.toString() + " DRAFT"
        }
        if (siapKirimCount > 0L) {
            pendingParts += siapKirimCount.toString() + " SIAP_KIRIM"
        }
        if (mengirimCount > 0L) {
            pendingParts += mengirimCount.toString() + " MENGIRIM"
        }
        if (gagalCount > 0L) {
            pendingParts += gagalCount.toString() + " GAGAL"
        }

        return LogoutCheckResult(
            canLogout = false,
            draftCount = draftCount,
            siapKirimCount = siapKirimCount,
            mengirimCount = mengirimCount,
            gagalCount = gagalCount,
            warningMessage =
                "Logout ditolak. Masih ada " +
                    pendingParts.joinToString(" dan ") +
                    ". Lengkapi/hapus DRAFT atau selesaikan pengiriman " +
                    "sebelum logout."
        )
    }

    suspend fun logoutIfAllowed(
        idPetugas: String,
        clearSession: suspend () -> Unit
    ): LogoutCheckResult {
        val result = check(idPetugas)
        if (result.canLogout) {
            clearSession()
        }
        return result
    }

    private fun List<PendataanStatusCount>.countFor(
        status: String
    ): Long {
        return firstOrNull { it.statusKirim == status }?.jumlah ?: 0L
    }
}
