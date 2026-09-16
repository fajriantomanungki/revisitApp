package com.fajriantomanungki.revisitapp.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Penjadwal antrean sync.
 *
 * KEEP mencegah dua worker sinkronisasi aktif bersamaan untuk petugas yang
 * sama. WorkManager hanya menjalankan request ketika jaringan tersedia.
 */
class SyncWorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun enqueue(idPetugas: String): Operation {
        require(idPetugas.isNotBlank()) { "id_petugas tidak boleh kosong" }

        val request: OneTimeWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(
                workDataOf(SyncWorker.KEY_ID_PETUGAS to idPetugas.trim())
            )
            /*
             * WorkManager memiliki batas minimum backoff 10 detik. Dengan
             * nilai ini, jadwal exponential menjadi kurang lebih 10, 20,
             * lalu 40 detik dan tetap memenuhi retry tanpa busy-loop.
             */
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                INITIAL_BACKOFF_SECONDS,
                TimeUnit.SECONDS
            )
            .addTag(TAG_SYNC)
            .addTag(uniqueWorkName(idPetugas))
            .build()

        return WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueWorkName(idPetugas),
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(idPetugas: String) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(idPetugas))
    }

    fun uniqueWorkName(idPetugas: String): String {
        return "sync-pendataan-${idPetugas.trim()}"
    }

    private companion object {
        const val TAG_SYNC = "sync-pendataan"
        const val INITIAL_BACKOFF_SECONDS = 10L
    }
}
