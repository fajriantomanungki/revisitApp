package com.fajriantomanungki.revisitapp.domain.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject

/**
 * Snapshot lokasi yang siap disimpan ke pendataan lokal.
 *
 * Waktu menggunakan jam perangkat ketika lokasi berhasil diperoleh. Nilainya
 * disimpan sebagai epoch millis UTC di Room, lalu dapat diformat oleh UI
 * sesuai zona waktu yang ditetapkan aplikasi.
 */
data class CapturedLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Double?,
    val capturedAtEpochMillis: Long,
    val isMock: Boolean
) {
    val needsAccuracyWarning: Boolean
        get() = accuracyM?.let { it > ACCURACY_WARNING_THRESHOLD_M } == true

    private companion object {
        const val ACCURACY_WARNING_THRESHOLD_M = 50.0
    }
}

enum class LocationFailureReason {
    PERMISSION_DENIED,
    LOCATION_DISABLED,
    UNAVAILABLE,
    ERROR
}

sealed interface LocationCaptureResult {
    data class Success(val location: CapturedLocation) : LocationCaptureResult

    data class Failure(
        val reason: LocationFailureReason,
        val cause: Throwable? = null
    ) : LocationCaptureResult
}

/**
 * Helper pengambilan satu titik lokasi foreground dengan prioritas akurasi
 * tinggi. Tidak menjalankan tracking lokasi di background.
 *
 * Panggil [captureCurrentLocation] kembali ketika petugas menekan "Ambil
 * Ulang". Permission tetap diminta oleh UI melalui Activity Result API;
 * helper ini hanya memvalidasi permission sebelum memanggil Fused Location.
 */
class LocationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fusedLocationProviderClient: FusedLocationProviderClient
) {

    fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Memeriksa apakah provider lokasi perangkat aktif.
     */
    fun isLocationEnabled(): Boolean {
        val locationManager = context.getSystemService(LocationManager::class.java)
            ?: return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    /**
     * Intent yang dapat dibuka UI ketika GPS/lokasi perangkat sedang mati.
     */
    fun createLocationSettingsIntent(): Intent {
        return Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
    }

    /**
     * Mengambil satu titik lokasi baru, bukan sekadar memakai lokasi lama.
     * Pengambilan dibatasi oleh [timeoutMillis] dan dapat dibatalkan ketika
     * coroutine pemanggil dibatalkan.
     */
    @SuppressLint("MissingPermission")
    suspend fun captureCurrentLocation(
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
    ): LocationCaptureResult {
        if (!hasFineLocationPermission()) {
            return LocationCaptureResult.Failure(
                reason = LocationFailureReason.PERMISSION_DENIED
            )
        }

        if (!isLocationEnabled()) {
            return LocationCaptureResult.Failure(
                reason = LocationFailureReason.LOCATION_DISABLED
            )
        }

        return suspendCancellableCoroutine { continuation ->
            val cancellationTokenSource = CancellationTokenSource()
            continuation.invokeOnCancellation {
                cancellationTokenSource.cancel()
            }

            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMaxUpdateAgeMillis(0L)
                .setDurationMillis(timeoutMillis.coerceAtLeast(MIN_TIMEOUT_MILLIS))
                .build()

            try {
                fusedLocationProviderClient
                    .getCurrentLocation(request, cancellationTokenSource.token)
                    .addOnSuccessListener { location ->
                        if (!continuation.isActive) return@addOnSuccessListener

                        if (location == null) {
                            continuation.resumeWith(
                                Result.success(
                                    LocationCaptureResult.Failure(
                                        reason = LocationFailureReason.UNAVAILABLE
                                    )
                                )
                            )
                        } else {
                            continuation.resumeWith(
                                Result.success(
                                    LocationCaptureResult.Success(location.toCapturedLocation())
                                )
                            )
                        }
                    }
                    .addOnFailureListener { error ->
                        if (continuation.isActive) {
                            continuation.resumeWith(
                                Result.success(
                                    LocationCaptureResult.Failure(
                                        reason = LocationFailureReason.ERROR,
                                        cause = error
                                    )
                                )
                            )
                        }
                    }
            } catch (error: Throwable) {
                if (continuation.isActive) {
                    continuation.resumeWith(
                        Result.success(
                            LocationCaptureResult.Failure(
                                reason = LocationFailureReason.ERROR,
                                cause = error
                            )
                        )
                    )
                }
            }

        }
    }

    private fun Location.toCapturedLocation(): CapturedLocation {
        return CapturedLocation(
            latitude = latitude,
            longitude = longitude,
            accuracyM = if (hasAccuracy()) accuracy.toDouble() else null,
            capturedAtEpochMillis = System.currentTimeMillis(),
            isMock = isMockLocation()
        )
    }

    private fun Location.isMockLocation(): Boolean {
        return LocationCompat.isMock(this)
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 15_000L
        const val MIN_TIMEOUT_MILLIS = 1_000L
    }
}
