package com.fajriantomanungki.revisitapp.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.fajriantomanungki.revisitapp.data.local.dao.FotoDao
import com.fajriantomanungki.revisitapp.data.local.dao.PendataanDao
import com.fajriantomanungki.revisitapp.data.local.entity.PendataanEntity
import com.fajriantomanungki.revisitapp.data.local.model.SyncStatus
import com.fajriantomanungki.revisitapp.data.sync.AppsScriptApi
import com.fajriantomanungki.revisitapp.data.sync.AppsScriptApiException
import com.fajriantomanungki.revisitapp.data.sync.RemoteSyncItemResult
import com.fajriantomanungki.revisitapp.data.sync.SyncConfigStore
import com.fajriantomanungki.revisitapp.data.sync.SyncPayloadRecord
import com.fajriantomanungki.revisitapp.data.sync.UploadedPhotoReference
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class RecordSyncException(
    message: String,
    val retryable: Boolean = false,
    cause: Throwable? = null
) : IOException(message, cause)

private data class BatchOutcome(
    val retryableError: Throwable? = null
)

/**
 * Worker antrean offline-first.
 *
 * Alur setiap record:
 *   1. tandai MENGIRIM;
 *   2. upload foto yang belum memiliki drive_file_id;
 *   3. simpan drive_file_id ke Room;
 *   4. kirim metadata satu batch maksimal 20 record;
 *   5. tandai TERKIRIM hanya setelah server mengonfirmasi id_record.
 *
 * Foto yang sudah memperoleh drive_file_id tidak diunggah ulang pada retry.
 * Idempotensi tambahan tetap dijamin oleh id_record/id_foto di Apps Script.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val pendataanDao: PendataanDao,
    private val fotoDao: FotoDao,
    private val appsScriptApi: AppsScriptApi,
    private val syncConfigStore: SyncConfigStore
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val config = syncConfigStore.read()
            ?: return failureResult("Konfigurasi Apps Script belum tersedia")

        val requestedIdPetugas = inputData.getString(KEY_ID_PETUGAS)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val idPetugas = requestedIdPetugas ?: config.idPetugas

        if (requestedIdPetugas != null && requestedIdPetugas != config.idPetugas) {
            return failureResult("id_petugas pada antrean tidak sesuai sesi aktif")
        }

        ensureNotificationChannel()
        setForeground(createForegroundInfo(processed = 0, total = 0))

        /*
         * Jika proses sebelumnya mati setelah markSending(), record MENGIRIM
         * dikembalikan ke GAGAL agar dapat masuk ke snapshot antrean ini.
         */
        pendataanDao.recoverInterruptedSync(
            idPetugas = idPetugas,
            pesanError = "Sinkronisasi sebelumnya terhenti dan akan dicoba ulang",
            waktuDiubah = System.currentTimeMillis()
        )

        val pendingRecords = pendataanDao.getAllForSync(idPetugas)
        if (pendingRecords.isEmpty()) {
            publishProgress(processed = 0, total = 0)
            return Result.success()
        }

        publishProgress(processed = 0, total = pendingRecords.size)
        val currentAttempt = runAttemptCount + 1
        var processed = 0

        pendingRecords.chunked(MAX_BATCH_SIZE).forEach { batch ->
            val outcome = processBatch(
                config = config,
                idPetugas = idPetugas,
                batch = batch,
                currentAttempt = currentAttempt
            )
            processed += batch.size
            publishProgress(processed = processed, total = pendingRecords.size)

            val retryableError = outcome.retryableError ?: return@forEach
            val errorMessage = readableError(retryableError)

            if (runAttemptCount < MAX_ATTEMPTS_PER_SESSION - 1) {
                return Result.retry()
            }

            /*
             * Ini adalah percobaan terakhir WorkManager. Record yang belum
             * selesai ditandai GAGAL; record TERKIRIM tidak pernah ditimpa.
             */
            markRemainingAsFailed(
                records = pendingRecords,
                message = errorMessage,
                attempt = currentAttempt
            )
            return failureResult(errorMessage)
        }

        return Result.success(
            workDataOf(
                KEY_PROCESSED to processed,
                KEY_TOTAL to pendingRecords.size,
                KEY_MESSAGE to "Sinkronisasi selesai"
            )
        )
    }

    private suspend fun processBatch(
        config: SyncConfig,
        idPetugas: String,
        batch: List<PendataanEntity>,
        currentAttempt: Int
    ): BatchOutcome {
        val readyForMetadata = ArrayList<SyncPayloadRecord>(batch.size)
        var retryableError: Throwable? = null

        batch.forEach { record ->
            val attempt = maxOf(currentAttempt, record.percobaanKirim + 1)
            val marked = pendataanDao.markSending(
                idRecord = record.idRecord,
                percobaanKirim = attempt,
                waktuDiubah = System.currentTimeMillis()
            )
            if (marked == 0) {
                return@forEach
            }

            try {
                val photos = uploadPhotosFirst(
                    config = config,
                    record = record
                )
                readyForMetadata += SyncPayloadRecord(
                    record = record,
                    photos = photos
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                markFailed(
                    record = record,
                    attempt = attempt,
                    message = readableError(error)
                )
                if (isRetryable(error) && retryableError == null) {
                    retryableError = error
                }
            }
        }

        if (readyForMetadata.isEmpty()) {
            return BatchOutcome(retryableError)
        }

        try {
            val response = appsScriptApi.sync(
                config = config,
                idPetugas = idPetugas,
                records = readyForMetadata
            )
            val resultsByRecord = response.results.associateBy { it.idRecord }

            readyForMetadata.forEach { payload ->
                val record = payload.record
                val attempt = maxOf(currentAttempt, record.percobaanKirim + 1)
                val remoteResult = resultsByRecord[record.idRecord]

                if (remoteResult == null) {
                    val error = AppsScriptApiException(
                        message = "Server tidak mengembalikan hasil untuk ${record.idRecord}",
                        errorCode = "HASIL_TIDAK_LENGKAP",
                        retryable = true
                    )
                    markFailed(
                        record = record,
                        attempt = attempt,
                        message = readableError(error)
                    )
                    if (retryableError == null) retryableError = error
                } else {
                    saveRemoteResult(
                        record = record,
                        attempt = attempt,
                        remoteResult = remoteResult
                    )
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            readyForMetadata.forEach { payload ->
                val attempt = maxOf(currentAttempt, payload.record.percobaanKirim + 1)
                markFailed(
                    record = payload.record,
                    attempt = attempt,
                    message = readableError(error)
                )
            }
            if (isRetryable(error) && retryableError == null) {
                retryableError = error
            }
        }

        return BatchOutcome(retryableError)
    }

    private suspend fun uploadPhotosFirst(
        config: SyncConfig,
        record: PendataanEntity
    ): List<UploadedPhotoReference> {
        val photos = fotoDao.getByRecord(record.idRecord)
            .sortedBy { it.urutan }
        if (photos.isEmpty()) {
            throw RecordSyncException(
                message = "Record ${record.idRecord} tidak memiliki foto",
                retryable = false
            )
        }

        return photos.map { photo ->
            val existingDriveId = photo.driveFileId
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

            if (existingDriveId != null) {
                if (!photo.sudahDiunggah) {
                    fotoDao.markUploaded(
                        idFoto = photo.idFoto,
                        driveFileId = existingDriveId
                    )
                }
                UploadedPhotoReference(
                    idFoto = photo.idFoto,
                    driveFileId = existingDriveId,
                    url = null,
                    urutan = photo.urutan
                )
            } else {
                val localFile = File(photo.pathLokal)
                if (!localFile.isFile) {
                    throw RecordSyncException(
                        message = "File foto tidak ditemukan: ${photo.pathLokal}",
                        retryable = false
                    )
                }

                val bytes = try {
                    withContext(Dispatchers.IO) { localFile.readBytes() }
                } catch (error: IOException) {
                    throw RecordSyncException(
                        message = "Gagal membaca foto ${photo.urutan}: " +
                            (error.message ?: "I/O error"),
                        retryable = false,
                        cause = error
                    )
                }
                val uploaded = appsScriptApi.uploadPhoto(
                    config = config,
                    idRecord = record.idRecord,
                    photo = photo,
                    bytes = bytes
                )
                fotoDao.markUploaded(
                    idFoto = photo.idFoto,
                    driveFileId = uploaded.driveFileId
                )
                uploaded
            }
        }
    }

    private suspend fun saveRemoteResult(
        record: PendataanEntity,
        attempt: Int,
        remoteResult: RemoteSyncItemResult
    ) {
        if (remoteResult.status == SyncStatus.TERKIRIM) {
            pendataanDao.saveSyncResult(
                idRecord = record.idRecord,
                statusKirim = SyncStatus.TERKIRIM,
                pesanError = null,
                percobaanKirim = attempt,
                waktuTerkirim = System.currentTimeMillis(),
                waktuDiubah = System.currentTimeMillis()
            )
        } else {
            markFailed(
                record = record,
                attempt = attempt,
                message = remoteResult.message ?: "Server menandai record sebagai GAGAL"
            )
        }
    }

    private suspend fun markFailed(
        record: PendataanEntity,
        attempt: Int,
        message: String
    ) {
        pendataanDao.saveSyncResult(
            idRecord = record.idRecord,
            statusKirim = SyncStatus.GAGAL,
            pesanError = message.take(MAX_ERROR_LENGTH),
            percobaanKirim = attempt,
            waktuTerkirim = null,
            waktuDiubah = System.currentTimeMillis()
        )
    }

    private suspend fun markRemainingAsFailed(
        records: List<PendataanEntity>,
        message: String,
        attempt: Int
    ) {
        records.forEach { snapshot ->
            val current = pendataanDao.getByRecordId(snapshot.idRecord) ?: return@forEach
            if (current.statusKirim == SyncStatus.TERKIRIM ||
                current.statusKirim == SyncStatus.GAGAL
            ) {
                return@forEach
            }
            markFailed(
                record = current,
                attempt = maxOf(attempt, current.percobaanKirim + 1),
                message = message
            )
        }
    }

    private suspend fun publishProgress(processed: Int, total: Int) {
        val message = if (total == 0) {
            "Tidak ada data yang menunggu dikirim"
        } else {
            "Mengirim $processed dari $total"
        }
        setProgress(
            workDataOf(
                KEY_PROCESSED to processed,
                KEY_TOTAL to total,
                KEY_MESSAGE to message
            )
        )
        setForeground(createForegroundInfo(processed, total))
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
            ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Sinkronisasi pendataan",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Kemajuan pengiriman data pendataan ke server"
            }
        )
    }

    private fun createForegroundInfo(
        processed: Int,
        total: Int
    ): ForegroundInfo {
        val text = if (total <= 0) {
            "Menyiapkan sinkronisasi"
        } else {
            "Mengirim $processed dari $total"
        }
        val notification = NotificationCompat.Builder(
            applicationContext,
            NOTIFICATION_CHANNEL_ID
        )
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Sinkronisasi data")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(0), processed.coerceIn(0, total.coerceAtLeast(0)), total <= 0)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun isRetryable(error: Throwable): Boolean {
        return when (error) {
            is AppsScriptApiException -> error.retryable
            is RecordSyncException -> error.retryable
            else -> false
        }
    }

    private fun readableError(error: Throwable): String {
        return when (error) {
            is AppsScriptApiException -> error.message
            else -> error.message
        }?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(MAX_ERROR_LENGTH)
            ?: "Sinkronisasi gagal karena kesalahan yang tidak diketahui"
    }

    private fun failureResult(message: String): Result {
        return Result.failure(
            workDataOf(KEY_ERROR_MESSAGE to message.take(MAX_ERROR_LENGTH))
        )
    }

    companion object {
        const val KEY_ID_PETUGAS = "id_petugas"
        const val KEY_PROCESSED = "processed"
        const val KEY_TOTAL = "total"
        const val KEY_MESSAGE = "message"
        const val KEY_ERROR_MESSAGE = "error_message"

        const val MAX_BATCH_SIZE = 20
        const val MAX_ATTEMPTS_PER_SESSION = 3
        const val MAX_ERROR_LENGTH = 500
        const val NOTIFICATION_CHANNEL_ID = "sync_pendataan"
        const val NOTIFICATION_ID = 1001
    }
}
