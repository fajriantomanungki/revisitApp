package com.fajriantomanungki.revisitapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.fajriantomanungki.revisitapp.data.local.entity.WilayahEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class WilayahDao {

    @Query(
        """
        SELECT * FROM wilayah
        ORDER BY nama_kec COLLATE NOCASE,
                 nama_desa COLLATE NOCASE,
                 nama_sls COLLATE NOCASE
        """
    )
    abstract fun observeAll(): Flow<List<WilayahEntity>>

    @Query(
        """
        SELECT * FROM wilayah
        ORDER BY nama_kec COLLATE NOCASE,
                 nama_desa COLLATE NOCASE,
                 nama_sls COLLATE NOCASE
        """
    )
    abstract suspend fun getAll(): List<WilayahEntity>

    @Query("SELECT * FROM wilayah WHERE kode_sls = :kodeSls LIMIT 1")
    abstract suspend fun getByKodeSls(kodeSls: String): WilayahEntity?

    @Query(
        """
        SELECT * FROM wilayah
        WHERE kode_kec = :kodeKec
        ORDER BY nama_desa COLLATE NOCASE, nama_sls COLLATE NOCASE
        """
    )
    abstract fun observeByKodeKec(kodeKec: String): Flow<List<WilayahEntity>>

    @Query(
        """
        SELECT * FROM wilayah
        WHERE kode_desa = :kodeDesa
        ORDER BY nama_sls COLLATE NOCASE
        """
    )
    abstract fun observeByKodeDesa(kodeDesa: String): Flow<List<WilayahEntity>>

    @Query(
        """
        SELECT * FROM wilayah
        WHERE kode_kec LIKE '%' || :query || '%'
           OR nama_kec LIKE '%' || :query || '%'
           OR kode_desa LIKE '%' || :query || '%'
           OR nama_desa LIKE '%' || :query || '%'
           OR kode_sls LIKE '%' || :query || '%'
           OR nama_sls LIKE '%' || :query || '%'
        ORDER BY nama_kec COLLATE NOCASE,
                 nama_desa COLLATE NOCASE,
                 nama_sls COLLATE NOCASE
        """
    )
    abstract fun observeSearch(query: String): Flow<List<WilayahEntity>>

    @Query("SELECT MAX(versi_master) FROM wilayah")
    abstract suspend fun getLatestMasterVersion(): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(rows: List<WilayahEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(row: WilayahEntity)

    @Query("DELETE FROM wilayah")
    abstract suspend fun deleteAll()

    /**
     * Penggantian master dilakukan secara atomik agar UI tidak membaca master
     * dalam keadaan setengah terhapus.
     */
    @Transaction
    open suspend fun replaceAll(rows: List<WilayahEntity>) {
        deleteAll()
        upsertAll(rows)
    }
}
