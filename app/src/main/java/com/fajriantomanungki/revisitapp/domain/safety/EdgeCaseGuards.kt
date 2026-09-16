package com.fajriantomanungki.revisitapp.domain.safety

import android.location.Location
import androidx.core.location.LocationCompat
import com.fajriantomanungki.revisitapp.data.local.dao.LaporanKegiatanDao
import com.fajriantomanungki.revisitapp.data.local.dao.PendataanDao
import com.fajriantomanungki.revisitapp.data.local.dao.PendataanStatusCount
import com.fajriantomanungki.revisitapp.data.local.model.SyncStatus
import com.fajriantomanungki.revisitapp.domain.location.CapturedLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

/** Kebijakan pemeriksaan kualitas lokasi sebelum record disimpan. */
data class LocationAuditPolicy(
    val accuracyWarningMeters: Double = 50.0
) {
    init {
        require(accuracyWarningMeters > 0.0) {
            "accuracyWarningMeters harus lebih besar dari 0"
        }
    }
}

enum class LocationWarning {
    INVALID_COORDINATE,
    MOCK_LOCATION,
    LOW_ACCURACY
}

data class LocationAuditResult(
    val isMock: Boolean,
    val accuracyM: Double?,
    val hasValidCoordinates: Boolean,
    val warnings: List<LocationWarning>,
    val warningMessage: String?
) {
    /** Koordinat invalid tidak boleh masuk sebagai titik pendataan. */
    val canSaveRecord: Boolean
        get() = hasValidCoordinates

    val hasFakeGpsWarning: Boolean
        get() = LocationWarning.MOCK_LOCATION in warnings
}

/**
 * Pemeriksa integritas lokasi aktual.
 *
 * Fake GPS tidak dihapus diam-diam: flag isMock dan peringatannya disimpan
 * untuk audit. Validitas koordinat tetap menjadi syarat penyimpanan.
 */
class LocationIntegrityChecker(
    private val policy: LocationAuditPolicy = LocationAuditPolicy()
) {

    fun inspect(location: Location): LocationAuditResult = inspectValues(
        latitude = location.latitude,
        longitude = location.longitude,
        accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
        isMock = LocationCompat.isMock(location)
    )

    fun inspect(location: CapturedLocation): LocationAuditResult = inspectValues(
        latitude = location.latitude,
        longitude = location.longitude,
        accuracyM = location.accuracyM,
        isMock = location.isMock
    )

    private fun inspectValues(
        latitude: Double,
        longitude: Double,
        accuracyM: Double?,
        isMock: Boolean
    ): LocationAuditResult {
        val hasValidCoordinates = isValidCoordinate(latitude, longitude)
        val warnings = mutableListOf<LocationWarning>()

        if (!hasValidCoordinates) warnings += LocationWarning.INVALID_COORDINATE
        if (isMock) warnings += LocationWarning.MOCK_LOCATION
        if (accuracyM != null && accuracyM > policy.accuracyWarningMeters) {
            warnings += LocationWarning.LOW_ACCURACY
        }

        return LocationAuditResult(
            isMock = isMock,
            accuracyM = accuracyM,
            hasValidCoordinates = hasValidCoordinates,
            warnings = warnings,
            warningMessage = createWarningMessage(warnings, accuracyM)
        )
    }

    private fun createWarningMessage(
        warnings: List<LocationWarning>,
        accuracyM: Double?
    ): String? {
        if (warnings.isEmpty()) return null

        return warnings.joinToString(separator = " ") { warning ->
            when (warning) {
                LocationWarning.INVALID_COORDINATE ->
                    "Koordinat lokasi tidak valid; ambil titik ulang."

                LocationWarning.MOCK_LOCATION ->
                    "Lokasi terindikasi Fake GPS. Data tetap disimpan untuk audit."

                LocationWarning.LOW_ACCURACY ->
                    "Akurasi lokasi rendah (${formatMeters(accuracyM ?: 0.0)}); " +
                        "ambil titik ulang bila memungkinkan."
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

    private fun isValidCoordinate(latitude: Double, longitude: Double): Boolean {
        return latitude.isFinite() &&
            longitude.isFinite() &&
            latitude in -90.0..90.0 &&
            longitude in -180.0..180.0
    }
}

data class LogoutCheckResult(
    val canLogout: Boolean,
    val draftCount: Long,
    val siapKirimCount: Long,
    val mengirimCount: Long,
    val gagalCount: Long,
    val warningMessage: String?,
    val laporanCount: Long = 0L
) {
    val blockedEntryCount: Long
        get() = draftCount + siapKirimCount + mengirimCount + gagalCount + laporanCount
}

/** Menolak logout jika masih ada pekerjaan lokal yang belum terselesaikan. */
class LogoutGuard @Inject constructor(
    private val pendataanDao: PendataanDao,
    private val laporanKegiatanDao: LaporanKegiatanDao,
    private val sessionCoordinator: LocalSessionCoordinator
) {

    suspend fun check(idPetugas: String): LogoutCheckResult =
        sessionCoordinator.withMutationLock {
            checkUnlocked(idPetugas)
        }

    suspend fun logoutIfAllowed(
        idPetugas: String,
        clearSession: suspend () -> Unit
    ): LogoutCheckResult = sessionCoordinator.withMutationLock {
        val result = checkUnlocked(idPetugas)
        if (result.canLogout) clearSession()
        result
    }

    private suspend fun checkUnlocked(idPetugas: String): LogoutCheckResult {
        if (idPetugas.isBlank()) {
            return LogoutCheckResult(
                canLogout = false,
                draftCount = 0L,
                siapKirimCount = 0L,
                mengirimCount = 0L,
                gagalCount = 0L,
                warningMessage = "Logout ditolak karena identitas petugas tidak valid."
            )
        }

        val summary = withContext(Dispatchers.IO) {
            pendataanDao.observeStatusSummary(idPetugas).first()
        }
        val laporanCount = withContext(Dispatchers.IO) {
            laporanKegiatanDao.countPendingForLogout(idPetugas)
        }
        val draftCount = summary.countFor(SyncStatus.DRAFT)
        val siapKirimCount = summary.countFor(SyncStatus.SIAP_KIRIM)
        val mengirimCount = summary.countFor(SyncStatus.MENGIRIM)
        val gagalCount = summary.countFor(SyncStatus.GAGAL)

        if (draftCount == 0L && siapKirimCount == 0L &&
            mengirimCount == 0L && gagalCount == 0L && laporanCount == 0L
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
        if (draftCount > 0L) pendingParts += "$draftCount DRAFT"
        if (siapKirimCount > 0L) pendingParts += "$siapKirimCount SIAP_KIRIM"
        if (mengirimCount > 0L) pendingParts += "$mengirimCount MENGIRIM"
        if (gagalCount > 0L) pendingParts += "$gagalCount GAGAL"
        if (laporanCount > 0L) pendingParts += "$laporanCount laporan kegiatan"

        return LogoutCheckResult(
            canLogout = false,
            draftCount = draftCount,
            siapKirimCount = siapKirimCount,
            mengirimCount = mengirimCount,
            gagalCount = gagalCount,
            warningMessage = "Logout ditolak. Masih ada " +
                pendingParts.joinToString(" dan ") + ". Lengkapi, hapus, atau " +
                "selesaikan pengiriman sebelum logout.",
            laporanCount = laporanCount
        )
    }

    private fun List<PendataanStatusCount>.countFor(status: String): Long =
        firstOrNull { it.statusKirim == status }?.jumlah ?: 0L
}
