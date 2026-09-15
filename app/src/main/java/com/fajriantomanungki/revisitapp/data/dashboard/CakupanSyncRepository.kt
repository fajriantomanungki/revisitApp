package com.fajriantomanungki.revisitapp.data.dashboard

import com.fajriantomanungki.revisitapp.data.local.dao.CakupanCacheDao
import com.fajriantomanungki.revisitapp.data.local.entity.CakupanCacheEntity
import com.fajriantomanungki.revisitapp.data.sync.AppsScriptApi
import com.fajriantomanungki.revisitapp.data.sync.SyncConfigStore
import javax.inject.Inject

data class CoverageRefreshResult(
    val rowCount: Int,
    val syncedAtMillis: Long
)

/** Mengambil snapshot cakupan server dan menyimpannya untuk mode offline. */
class CakupanSyncRepository @Inject constructor(
    private val cakupanCacheDao: CakupanCacheDao,
    private val appsScriptApi: AppsScriptApi,
    private val syncConfigStore: SyncConfigStore
) {

    suspend fun refresh(): CoverageRefreshResult {
        val config = syncConfigStore.read()
            ?: error("Konfigurasi Apps Script belum tersedia")
        val response = appsScriptApi.getCoverage(
            config = config,
            idPetugas = config.idPetugas
        )
        val syncedAt = System.currentTimeMillis()
        val rows = response.rows.map { row ->
            CakupanCacheEntity(
                kodeSls = row.kodeSls,
                jumlahTerdata = row.jumlahTerdata.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                target = row.target.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                waktuSinkron = syncedAt
            )
        }
        cakupanCacheDao.replaceAll(rows)
        return CoverageRefreshResult(
            rowCount = rows.size,
            syncedAtMillis = syncedAt
        )
    }
}
