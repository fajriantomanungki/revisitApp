package com.fajriantomanungki.revisitapp.worker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Menjadwalkan pembersihan foto terkirim tanpa mengganggu input offline. */
class PhotoRetentionScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun schedule(retentionDays: Int = PhotoRetentionWorker.DEFAULT_RETENTION_DAYS) {
        val request = PeriodicWorkRequestBuilder<PhotoRetentionWorker>(
            1,
            TimeUnit.DAYS
        )
            .setInputData(
                workDataOf(
                    PhotoRetentionWorker.KEY_RETENTION_DAYS to
                        retentionDays.coerceIn(1, 3650)
                )
            )
            .addTag(TAG_PHOTO_RETENTION)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private companion object {
        const val UNIQUE_WORK_NAME = "retensi-foto-terkirim"
        const val TAG_PHOTO_RETENTION = "photo-retention"
    }
}
