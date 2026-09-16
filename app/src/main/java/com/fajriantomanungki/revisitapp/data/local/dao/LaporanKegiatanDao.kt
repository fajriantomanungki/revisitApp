package com.fajriantomanungki.revisitapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fajriantomanungki.revisitapp.data.local.entity.LaporanKegiatanEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LaporanKegiatanDao {

    @Query(
        """
        SELECT * FROM laporan_kegiatan
        WHERE id_petugas = :idPetugas
        ORDER BY tanggal DESC
        """
    )
    fun observeAll(idPetugas: String): Flow<List<LaporanKegiatanEntity>>

    @Query(
        """
        SELECT * FROM laporan_kegiatan
        WHERE id_petugas = :idPetugas AND tanggal = :tanggal
        LIMIT 1
        """
    )
    suspend fun getByDate(idPetugas: String, tanggal: String): LaporanKegiatanEntity?

    @Query(
        """
        SELECT * FROM laporan_kegiatan
        WHERE id_petugas = :idPetugas
          AND status_kirim IN ('SIAP_KIRIM', 'GAGAL')
        ORDER BY tanggal ASC
        """
    )
    suspend fun getForSync(idPetugas: String): List<LaporanKegiatanEntity>

    @Query(
        """
        SELECT COUNT(*) FROM laporan_kegiatan
        WHERE id_petugas = :idPetugas
          AND status_kirim IN ('DRAFT', 'SIAP_KIRIM', 'MENGIRIM', 'GAGAL')
        """
    )
    suspend fun countPendingForLogout(idPetugas: String): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: LaporanKegiatanEntity)

    @Query(
        """
        UPDATE laporan_kegiatan
        SET status_kirim = 'MENGIRIM',
            pesan_error = NULL,
            percobaan_kirim = :percobaanKirim,
            waktu_diubah = :waktuDiubah
        WHERE id_laporan = :idLaporan
          AND status_kirim IN ('SIAP_KIRIM', 'GAGAL')
        """
    )
    suspend fun markSending(
        idLaporan: String,
        percobaanKirim: Int,
        waktuDiubah: Long
    ): Int

    @Query(
        """
        UPDATE laporan_kegiatan
        SET status_kirim = :statusKirim,
            pesan_error = :pesanError,
            percobaan_kirim = :percobaanKirim,
            waktu_terkirim = :waktuTerkirim,
            waktu_diubah = :waktuDiubah
        WHERE id_laporan = :idLaporan
          AND status_kirim != 'TERKIRIM'
        """
    )
    suspend fun saveSyncResult(
        idLaporan: String,
        statusKirim: String,
        pesanError: String?,
        percobaanKirim: Int,
        waktuTerkirim: Long?,
        waktuDiubah: Long
    ): Int

    @Query(
        """
        UPDATE laporan_kegiatan
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
}
