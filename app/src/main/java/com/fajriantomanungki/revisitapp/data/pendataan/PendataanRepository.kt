package com.fajriantomanungki.revisitapp.data.pendataan

import androidx.room.withTransaction
import com.fajriantomanungki.revisitapp.data.local.AppDatabase
import com.fajriantomanungki.revisitapp.data.local.entity.FotoEntity
import com.fajriantomanungki.revisitapp.data.local.entity.PendataanEntity
import com.fajriantomanungki.revisitapp.data.local.entity.WilayahEntity
import com.fajriantomanungki.revisitapp.data.local.model.StatusPendataan
import com.fajriantomanungki.revisitapp.data.local.model.SyncStatus
import com.fajriantomanungki.revisitapp.data.local.model.UuidV4
import com.fajriantomanungki.revisitapp.domain.safety.LocalSessionCoordinator
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
    val versiApp: String,
    /** ID foto lama yang tetap dipertahankan ketika set foto diedit. */
    val retainedPhotoIds: List<String> = emptyList(),
    /** True jika pengguna memang mengubah set foto (termasuk menghapus foto). */
    val replacePhotos: Boolean = false,
    /** Null untuk pendataan baru; berisi UUID saat pengguna mengedit. */
    val existingRecordId: String? = null
)

/**
 * Menyimpan form dan foto dalam satu transaksi Room.
 *
 * Record selalu ditulis lebih dulu ke database lokal. Jaringan sama sekali
 * tidak terlibat dalam jalur simpan ini.
 */
class PendataanRepository @Inject constructor(
    private val database: AppDatabase,
    private val sessionCoordinator: LocalSessionCoordinator
) {

    suspend fun save(submission: PendataanFormSubmission): String =
        sessionCoordinator.withMutationLock {
            submission.existingRecordId
                ?.let { updateExistingUnlocked(it, submission) }
                ?: saveNewUnlocked(submission)
        }

    suspend fun saveNew(submission: PendataanFormSubmission): String =
        sessionCoordinator.withMutationLock {
            saveNewUnlocked(submission)
        }

    private suspend fun saveNewUnlocked(submission: PendataanFormSubmission): String {
        require(submission.idPetugas.isNotBlank()) {
            "id_petugas wajib tersedia sebelum menyimpan pendataan"
        }
        require(submission.photoFiles.size <= MAX_PHOTOS) {
            "Maksimal $MAX_PHOTOS foto per pendataan"
        }
        if (!submission.simpanSebagaiDraf) {
            require(isReadyToSend(submission)) {
                "Pendataan belum lengkap untuk dikirim. Lengkapi lokasi, data objek, dan foto."
            }
        }
        require(submission.photoFiles.all { it.isFile }) {
            "Ada file foto yang tidak tersedia di penyimpanan aplikasi"
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

    /**
     * Memperbarui record yang sudah ada tanpa membuat UUID baru.
     *
     * Memakai UUID yang sama membuat edit record TERKIRIM menjadi upsert
     * idempoten di Apps Script, sehingga tidak menambah hitungan ganda pada
     * dashboard. Foto lama dipertahankan bila pengguna tidak mengambil foto
     * baru; bila ada foto baru, seluruh set foto diganti secara atomik.
     */
    suspend fun updateExisting(
        idRecord: String,
        submission: PendataanFormSubmission
    ): String = sessionCoordinator.withMutationLock {
        updateExistingUnlocked(idRecord, submission)
    }

    private suspend fun updateExistingUnlocked(
        idRecord: String,
        submission: PendataanFormSubmission
    ): String {
        require(submission.idPetugas.isNotBlank()) {
            "id_petugas wajib tersedia sebelum memperbarui pendataan"
        }

        val existing = database.pendataanDao().getById(
            idRecord = idRecord,
            idPetugas = submission.idPetugas
        ) ?: error("Pendataan yang akan diedit tidak ditemukan di perangkat")
        if (existing.statusKirim == SyncStatus.MENGIRIM) {
            error("Pendataan sedang dikirim. Tunggu sampai proses selesai sebelum mengedit.")
        }
        val editingSentRecord = existing.statusKirim == SyncStatus.TERKIRIM
        if (editingSentRecord && submission.simpanSebagaiDraf) {
            error("Record yang sudah terkirim harus disimpan melalui tombol kirim ulang.")
        }
        val oldPhotos = database.fotoDao().getByRecord(idRecord)
        val retainedPhotos = oldPhotos.filter { old ->
            old.idFoto in submission.retainedPhotoIds
        }
        val photoSetChanged = submission.replacePhotos ||
            submission.photoFiles.isNotEmpty() ||
            submission.retainedPhotoIds.isNotEmpty()
        val effectivePhotoFiles = if (photoSetChanged) {
            retainedPhotos.map { File(it.pathLokal) } + submission.photoFiles
        } else {
            oldPhotos.map { File(it.pathLokal) }
        }

        require(effectivePhotoFiles.size <= MAX_PHOTOS) {
            "Maksimal $MAX_PHOTOS foto per pendataan"
        }

        if (!submission.simpanSebagaiDraf &&
            !isReadyToSend(submission.copy(photoFiles = effectivePhotoFiles))
        ) {
            error("Pendataan belum lengkap untuk dikirim. Lengkapi lokasi, data objek, dan foto.")
        }
        require(submission.photoFiles.all { it.isFile }) {
            "Ada file foto baru yang tidak tersedia di penyimpanan aplikasi"
        }
        val photosToKeep = if (photoSetChanged) retainedPhotos else oldPhotos
        if (!submission.simpanSebagaiDraf &&
            photosToKeep.any { it.driveFileId.isNullOrBlank() &&
                (it.pathLokal.isBlank() || !File(it.pathLokal).isFile) }
        ) {
            error("Foto lama tidak tersedia. Ambil foto pengganti sebelum mengirim.")
        }

        val now = System.currentTimeMillis()
        val nextStatus = if (submission.simpanSebagaiDraf) {
            SyncStatus.DRAFT
        } else {
            SyncStatus.SIAP_KIRIM
        }
        val record = existing.copy(
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
            statusKirim = nextStatus,
            replaceExisting = existing.replaceExisting ||
                editingSentRecord,
            pesanError = null,
            percobaanKirim = if (nextStatus == SyncStatus.DRAFT) {
                existing.percobaanKirim
            } else {
                0
            },
            waktuTerkirim = if (nextStatus == SyncStatus.DRAFT) {
                existing.waktuTerkirim
            } else {
                null
            },
            waktuDiubah = now,
            versiApp = submission.versiApp
        )

        val newPhotos = if (photoSetChanged) {
            val retainedRows = retainedPhotos.mapIndexed { index, photo ->
                photo.copy(urutan = index + 1)
            }
            val newRows = submission.photoFiles.mapIndexed { index, file ->
                FotoEntity(
                    idRecord = idRecord,
                    pathLokal = file.absolutePath,
                    urutan = retainedRows.size + index + 1
                )
            }
            retainedRows + newRows
        } else {
            emptyList()
        }

        database.withTransaction {
            database.pendataanDao().upsert(record)
            if (photoSetChanged) {
                database.fotoDao().deleteByRecord(idRecord)
                if (newPhotos.isNotEmpty()) {
                    database.fotoDao().upsertAll(newPhotos)
                }
            }
        }

        if (photoSetChanged) {
            oldPhotos.forEach { old ->
                if (newPhotos.none { it.pathLokal == old.pathLokal }) {
                    old.pathLokal.takeIf { it.isNotBlank() }?.let { File(it).delete() }
                }
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
    suspend fun deleteIfNotSent(row: PendataanEntity): Boolean =
        sessionCoordinator.withMutationLock {
            withContext(Dispatchers.IO) {
                val photos = database.fotoDao().getByRecord(row.idRecord)
                val deletedRows = database.pendataanDao().deleteIfNotSent(
                    idRecord = row.idRecord,
                    idPetugas = row.idPetugas
                )
                if (deletedRows > 0) {
                    photos.forEach { photo ->
                        photo.pathLokal.takeIf { it.isNotBlank() }
                            ?.let { File(it).delete() }
                    }
                    true
                } else {
                    false
                }
            }
        }

    private companion object {
        const val MAX_PHOTOS = 3
    }
}
