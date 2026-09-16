package com.fajriantomanungki.revisitapp.data.pendataan

import androidx.room.withTransaction
import com.fajriantomanungki.revisitapp.data.local.AppDatabase
import com.fajriantomanungki.revisitapp.data.local.entity.FotoEntity
import com.fajriantomanungki.revisitapp.data.local.entity.PendataanEntity
import com.fajriantomanungki.revisitapp.data.local.entity.WilayahEntity
import com.fajriantomanungki.revisitapp.data.local.model.StatusPendataan
import com.fajriantomanungki.revisitapp.data.local.model.SyncStatus
import com.fajriantomanungki.revisitapp.data.local.model.UuidV4
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class PendataanFormSubmission(
    val idPetugas: String,
    val wilayah: WilayahEntity?,
    val jenisObjek: String,
    val namaObjek: String,
    val alamat: String,
    val statusPendataan: String,
    val catatan: String,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyM: Double?,
    val isMock: Boolean,
    val waktuPendataan: Long?,
    val photoFiles: List<File>,
    val simpanSebagaiDraf: Boolean,
    val versiApp: String
)

/**
 * Menyimpan form dan foto dalam satu transaksi Room.
 *
 * Record selalu ditulis lebih dulu ke database lokal. Jaringan sama sekali
 * tidak terlibat dalam jalur simpan ini.
 */
class PendataanRepository @Inject constructor(
    private val database: AppDatabase
) {

    suspend fun saveNew(submission: PendataanFormSubmission): String {
        require(submission.idPetugas.isNotBlank()) {
            "id_petugas wajib tersedia sebelum menyimpan pendataan"
        }

        val now = System.currentTimeMillis()
        val statusKirim = if (submission.simpanSebagaiDraf) {
            SyncStatus.DRAFT
        } else {
            SyncStatus.SIAP_KIRIM
        }
        val idRecord = UuidV4.generate()
        val record = PendataanEntity(
            idRecord = idRecord,
            idPetugas = submission.idPetugas,
            kodeKab = submission.wilayah?.kodeKab.orEmpty(),
            kabupaten = submission.wilayah?.kabupaten.orEmpty(),
            kodeKec = submission.wilayah?.kodeKec.orEmpty(),
            namaKec = submission.wilayah?.namaKec.orEmpty(),
            kodeDesa = submission.wilayah?.kodeDesa.orEmpty(),
            namaDesa = submission.wilayah?.namaDesa.orEmpty(),
            kodeSls = submission.wilayah?.kodeSls.orEmpty(),
            namaSls = submission.wilayah?.namaSls.orEmpty(),
            jenisObjek = submission.jenisObjek,
            namaObjek = submission.namaObjek,
            alamat = submission.alamat,
            statusPendataan = submission.statusPendataan,
            catatan = submission.catatan,
            latitude = submission.latitude,
            longitude = submission.longitude,
            akurasiM = submission.accuracyM,
            isMock = submission.isMock,
            waktuPendataan = submission.waktuPendataan,
            statusKirim = statusKirim,
            waktuDibuat = now,
            waktuDiubah = now,
            versiApp = submission.versiApp
        )
        val photos = submission.photoFiles.mapIndexed { index, file ->
            FotoEntity(
                idRecord = idRecord,
                pathLokal = file.absolutePath,
                urutan = index + 1
            )
        }

        database.withTransaction {
            database.pendataanDao().upsert(record)
            if (photos.isNotEmpty()) {
                database.fotoDao().upsertAll(photos)
            }
        }
        return idRecord
    }

    fun isReadyToSend(submission: PendataanFormSubmission): Boolean {
        return submission.wilayah?.kodeSls?.isNotBlank() == true &&
            submission.jenisObjek.isNotBlank() &&
            submission.namaObjek.isNotBlank() &&
            submission.statusPendataan in setOf(
                StatusPendataan.LENGKAP,
                StatusPendataan.TIDAK_LENGKAP
            ) &&
            (submission.statusPendataan != StatusPendataan.TIDAK_LENGKAP ||
                submission.catatan.isNotBlank()) &&
            submission.latitude != null &&
            submission.longitude != null &&
            submission.accuracyM != null &&
            submission.waktuPendataan != null &&
            submission.photoFiles.isNotEmpty()
    }

    /** Menghapus hanya record milik petugas aktif yang belum TERKIRIM. */
    suspend fun deleteIfNotSent(row: PendataanEntity): Boolean = withContext(Dispatchers.IO) {
        val photos = database.fotoDao().getByRecord(row.idRecord)
        val deletedRows = database.pendataanDao().deleteIfNotSent(
            idRecord = row.idRecord,
            idPetugas = row.idPetugas
        )
        if (deletedRows > 0) {
            photos.forEach { File(it.pathLokal).delete() }
            true
        } else {
            false
        }
    }
}
