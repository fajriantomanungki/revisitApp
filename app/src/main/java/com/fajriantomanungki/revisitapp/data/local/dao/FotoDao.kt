package com.fajriantomanungki.revisitapp.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fajriantomanungki.revisitapp.data.local.entity.FotoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FotoDao {

    @Query(
        """
        SELECT * FROM foto
        WHERE id_record = :idRecord
        ORDER BY urutan ASC
        """
    )
    fun observeByRecord(idRecord: String): Flow<List<FotoEntity>>

    @Query(
        """
        SELECT * FROM foto
        WHERE id_record = :idRecord
        ORDER BY urutan ASC
        """
    )
    suspend fun getByRecord(idRecord: String): List<FotoEntity>

    @Query("SELECT * FROM foto WHERE id_foto = :idFoto LIMIT 1")
    suspend fun getById(idFoto: String): FotoEntity?

    @Query(
        """
        SELECT * FROM foto
        WHERE id_record = :idRecord
          AND sudah_diunggah = 0
        ORDER BY urutan ASC
        """
    )
    suspend fun getPendingUpload(idRecord: String): List<FotoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: FotoEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<FotoEntity>)

    @Query(
        """
        UPDATE foto
        SET drive_file_id = :driveFileId,
            sudah_diunggah = 1
        WHERE id_foto = :idFoto
        """
    )
    suspend fun markUploaded(
        idFoto: String,
        driveFileId: String
    ): Int

    /**
     * Repository mengambil hasil query ini, menghapus file fisik, lalu
     * memanggil deleteAll() agar database dan storage tetap konsisten.
     */
    @Query(
        """
        SELECT f.* FROM foto AS f
        INNER JOIN pendataan AS p ON p.id_record = f.id_record
        WHERE p.status_kirim = 'TERKIRIM'
          AND p.waktu_terkirim IS NOT NULL
          AND p.waktu_terkirim <= :cutoffMillis
          AND f.path_lokal <> ''
        ORDER BY p.waktu_terkirim ASC
        """
    )
    suspend fun getUploadedPhotosBefore(
        cutoffMillis: Long
    ): List<FotoEntity>

    @Delete
    suspend fun deleteAll(rows: List<FotoEntity>): Int

    /** Menghapus salinan lokal tetapi mempertahankan drive_file_id untuk edit ulang. */
    @Query("UPDATE foto SET path_lokal = '' WHERE id_foto = :idFoto")
    suspend fun clearLocalPath(idFoto: String): Int

    @Query("DELETE FROM foto WHERE id_record = :idRecord")
    suspend fun deleteByRecord(idRecord: String): Int

    @Query("SELECT COUNT(*) FROM foto WHERE id_record = :idRecord")
    suspend fun countByRecord(idRecord: String): Long
}
