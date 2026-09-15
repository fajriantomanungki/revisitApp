package com.fajriantomanungki.revisitapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.fajriantomanungki.revisitapp.data.local.entity.CakupanCacheEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CakupanCacheDao {

    @Query(
        """
        SELECT * FROM cakupan_cache
        ORDER BY kode_sls ASC
        """
    )
    abstract fun observeAll(): Flow<List<CakupanCacheEntity>>

    @Query(
        """
        SELECT * FROM cakupan_cache
        ORDER BY
            CASE
                WHEN target <= 0 THEN 0.0
                ELSE CAST(jumlah_terdata AS REAL) / target
            END ASC,
            kode_sls ASC
        LIMIT 10
        """
    )
    abstract fun observeLowestTen(): Flow<List<CakupanCacheEntity>>

    @Query(
        """
        SELECT * FROM cakupan_cache
        ORDER BY kode_sls ASC
        """
    )
    abstract suspend fun getAll(): List<CakupanCacheEntity>

    @Query(
        "SELECT * FROM cakupan_cache WHERE kode_sls = :kodeSls LIMIT 1"
    )
    abstract suspend fun getByKodeSls(kodeSls: String): CakupanCacheEntity?

    @Query("SELECT MAX(waktu_sinkron) FROM cakupan_cache")
    abstract suspend fun getLastSyncTime(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(rows: List<CakupanCacheEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(row: CakupanCacheEntity)

    @Query("DELETE FROM cakupan_cache")
    abstract suspend fun deleteAll()

    @Transaction
    open suspend fun replaceAll(rows: List<CakupanCacheEntity>) {
        deleteAll()
        upsertAll(rows)
    }
}
