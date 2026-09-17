package com.fajriantomanungki.revisitapp.data.local.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fajriantomanungki.revisitapp.data.local.entity.PendataanEntity
import kotlinx.coroutines.flow.Flow

data class PendataanStatusCount(
    @ColumnInfo(name = "status_kirim")
    val statusKirim: String,
    @ColumnInfo(name = "jumlah")
    val jumlah: Long
)

data class LocalCoverageCount(
    @ColumnInfo(name = "kode_sls")
    val kodeSls: String,
    @ColumnInfo(name = "jumlah_terdata")
    val jumlahTerdata: Long
)

@Dao
interface PendataanDao {

    @Query(
        """
        SELECT * FROM pendataan
        WHERE id_petugas = :idPetugas
        ORDER BY waktu_dibuat DESC
        """
    )
    fun observeAll(idPetugas: String): Flow<List<PendataanEntity>>

    @Query(
        """
        SELECT * FROM pendataan
        WHERE id_petugas = :idPetugas
          AND (:statusKirim IS NULL OR status_kirim = :statusKirim)
          AND (:kodeSls IS NULL OR kode_sls = :kodeSls)
          AND (:dariMillis IS NULL OR waktu_dibuat >= :dariMillis)
          AND (:sampaiMillis IS NULL OR waktu_dibuat < :sampaiMillis)
        ORDER BY waktu_dibuat DESC
        """
    )
    fun observeFiltered(
        idPetugas: String,
        statusKirim: String? = null,
        kodeSls: String? = null,
        dariMillis: Long? = null,
        sampaiMillis: Long? = null
    ): Flow<List<PendataanEntity>>

    @Query(
        """
        SELECT * FROM pendataan
        WHERE id_petugas = :idPetugas
          AND status_kirim = :statusKirim
        ORDER BY waktu_dibuat DESC
        """
    )
    fun observeByStatus(
        idPetugas: String,
        statusKirim: String
    ): Flow<List<PendataanEntity>>

    @Query(
        """
        SELECT * FROM pendataan
        WHERE id_petugas = :idPetugas
          AND kode_sls = :kodeSls
        ORDER BY waktu_dibuat DESC
        """
    )
    fun observeByKodeSls(
        idPetugas: String,
        kodeSls: String
    ): Flow<List<PendataanEntity>>

    @Query(
        """
        SELECT * FROM pendataan
        WHERE id_record = :idRecord
          AND id_petugas = :idPetugas
        LIMIT 1
        """
    )
    suspend fun getById(
        idRecord: String,
        idPetugas: String
    ): PendataanEntity?

    @Query(
        """
        SELECT * FROM pendataan
        WHERE id_record = :idRecord
        LIMIT 1
        """
    )
    suspend fun getByRecordId(idRecord: String): PendataanEntity?

    /**
     * Pengambilan antrean dibatasi 20 record sesuai batas API Apps Script.
     */
    @Query(
        """
        SELECT * FROM pendataan
        WHERE id_petugas = :idPetugas
          AND status_kirim IN ('SIAP_KIRIM', 'GAGAL')
        ORDER BY waktu_dibuat ASC
        LIMIT :limit
        """
    )
    suspend fun getForSync(
        idPetugas: String,
        limit: Int = 20
    ): List<PendataanEntity>

    /**
     * Mengambil snapshot seluruh antrean agar satu sesi worker dapat memproses
     * data dalam batch tanpa mengambil ulang record GAGAL yang baru saja
     * diproses pada loop yang sama.
     */
    @Query(
        """
        SELECT * FROM pendataan
        WHERE id_petugas = :idPetugas
          AND status_kirim IN ('SIAP_KIRIM', 'GAGAL')
        ORDER BY waktu_dibuat ASC
        """
    )
    suspend fun getAllForSync(idPetugas: String): List<PendataanEntity>

    @Query(
        """
        SELECT status_kirim, COUNT(*) AS jumlah
        FROM pendataan
        WHERE id_petugas = :idPetugas
        GROUP BY status_kirim
        """
    )
    fun observeStatusSummary(
        idPetugas: String
    ): Flow<List<PendataanStatusCount>>

    /**
     * DRAFT tidak dihitung sebagai cakupan karena belum tentu memiliki data
     * lengkap. Record siap/sedang dikirim dihitung selama belum menjadi
     * pengganti record server. Record GAGAL tetap dihitung karena hasil
     * request belum cukup untuk membuktikan apakah server sudah menyimpannya.
     */
    @Query(
        """
        SELECT kode_sls, COUNT(*) AS jumlah_terdata
        FROM pendataan
        WHERE id_petugas = :idPetugas
          AND kode_sls != ''
          AND (
              replace_existing = 0
              OR status_kirim = 'GAGAL'
          )
          AND status_kirim IN ('SIAP_KIRIM', 'MENGIRIM', 'GAGAL')
        GROUP BY kode_sls
        """
    )
    fun observeLocalCoverage(
        idPetugas: String
    ): Flow<List<LocalCoverageCount>>

    @Query(
        """
        SELECT COUNT(*) FROM pendataan
        WHERE id_petugas = :idPetugas
          AND status_kirim IN ('SIAP_KIRIM', 'GAGAL')
        """
    )
    suspend fun countWaitingForSync(idPetugas: String): Long

    @Query(
        """
        SELECT COUNT(*) FROM pendataan
        WHERE id_petugas = :idPetugas
          AND status_kirim IN ('SIAP_KIRIM', 'GAGAL')
          AND waktu_dibuat < :thresholdMillis
        """
    )
    suspend fun countNotSentBefore(
        idPetugas: String,
        thresholdMillis: Long
    ): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: PendataanEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<PendataanEntity>)

    @Query(
        """
        UPDATE pendataan
        SET status_kirim = 'MENGIRIM',
            pesan_error = NULL,
            percobaan_kirim = :percobaanKirim,
            waktu_diubah = :waktuDiubah
        WHERE id_record = :idRecord
          AND status_kirim IN ('SIAP_KIRIM', 'GAGAL')
        """
    )
    suspend fun markSending(
        idRecord: String,
        percobaanKirim: Int,
        waktuDiubah: Long
    ): Int

    @Query(
        """
        UPDATE pendataan
        SET status_kirim = :statusKirim,
            pesan_error = :pesanError,
            percobaan_kirim = :percobaanKirim,
            waktu_terkirim = :waktuTerkirim,
            replace_existing = CASE
                WHEN :statusKirim = 'TERKIRIM' THEN 0
                ELSE replace_existing
            END,
            waktu_diubah = :waktuDiubah
        WHERE id_record = :idRecord
          AND status_kirim != 'TERKIRIM'
        """
    )
    suspend fun saveSyncResult(
        idRecord: String,
        statusKirim: String,
        pesanError: String?,
        percobaanKirim: Int,
        waktuTerkirim: Long?,
        waktuDiubah: Long
    ): Int

    @Query(
        """
        UPDATE pendataan
        SET status_kirim = 'GAGAL',
            pesan_error = :pesanError,
            waktu_diubah = :waktuDiubah
        WHERE id_petugas = :idPetugas
          AND status_kirim = 'MENGIRIM'
        """
    )
    suspend fun recoverInterruptedSync(
        idPetugas: String,
        pesanError: String,
        waktuDiubah: Long
    ): Int

    /**
     * Record berstatus TERKIRIM tidak dapat dihapus lewat DAO. Semua caller
     * wajib menggunakan operasi yang menyertakan id_petugas agar satu sesi
     * tidak dapat menghapus data milik petugas lain.
     */
    @Query(
        """
        DELETE FROM pendataan
        WHERE id_record = :idRecord
          AND id_petugas = :idPetugas
          AND status_kirim != 'TERKIRIM'
        """
    )
    suspend fun deleteIfNotSent(
        idRecord: String,
        idPetugas: String
    ): Int
}
