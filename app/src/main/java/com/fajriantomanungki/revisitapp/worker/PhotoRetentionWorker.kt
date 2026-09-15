package com.fajriantomanungki.revisitapp.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fajriantomanungki.revisitapp.data.local.dao.FotoDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Membersihkan file foto lokal yang sudah terkonfirmasi TERKIRIM.
 * Metadata pendataan tetap dipertahankan di Room; hanya row foto dan file
 * fisiknya yang dihapus setelah masa retensi berlalu.
 */
@HiltWorker
class PhotoRetentionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val fotoDao: FotoDao
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val retentionDays = inputData
            .getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)
            .coerceIn(1, 3650)
        val cutoffMillis = System.currentTimeMillis() -
            retentionDays.toLong() * MILLIS_PER_DAY
        val candidates = fotoDao.getUploadedPhotosBefore(cutoffMillis)
        if (candidates.isEmpty()) return@withContext Result.success()

        /* Jika delete gagal, row dipertahankan agar percobaan berikutnya aman. */
        val removable = candidates.filter { photo ->
            val file = File(photo.pathLokal)
            !file.exists() || file.delete()
        }
        if (removable.isNotEmpty()) {
            fotoDao.deleteAll(removable)
        }
        Result.success()
    }

    companion object {
        const val KEY_RETENTION_DAYS = "retention_days"
        const val DEFAULT_RETENTION_DAYS = 7
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L
    }
}
