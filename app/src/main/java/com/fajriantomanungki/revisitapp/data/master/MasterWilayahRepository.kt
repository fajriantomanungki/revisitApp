package com.fajriantomanungki.revisitapp.data.master

import com.fajriantomanungki.revisitapp.data.local.dao.WilayahDao
import com.fajriantomanungki.revisitapp.data.local.entity.WilayahEntity
import com.fajriantomanungki.revisitapp.data.sync.AppsScriptApi
import com.fajriantomanungki.revisitapp.data.sync.SyncConfigStore
import javax.inject.Inject

data class MasterRefreshResult(
    val updated: Boolean,
    val version: Int,
    val rowCount: Int
)

/**
 * Menghubungkan F-02 dengan GET action=master. Room tetap menjadi sumber
 * kebenaran; jaringan hanya mengganti snapshot secara atomik.
 */
class MasterWilayahRepository @Inject constructor(
    private val wilayahDao: WilayahDao,
    private val appsScriptApi: AppsScriptApi,
    private val syncConfigStore: SyncConfigStore
) {

    suspend fun refresh(): MasterRefreshResult {
        val config = syncConfigStore.read()
            ?: error("Konfigurasi Apps Script belum tersedia")
        val localVersion = wilayahDao.getLatestMasterVersion() ?: 0
        val response = appsScriptApi.getMaster(
            config = config,
            localVersion = localVersion
        )

        if (!response.hasChanges) {
            return MasterRefreshResult(
                updated = false,
                version = response.version,
                rowCount = wilayahDao.getAll().size
            )
        }

        val rows = response.rows.map { row ->
            WilayahEntity(
                kodeKec = row.kodeKec,
                namaKec = row.namaKec,
                kodeDesa = row.kodeDesa,
                namaDesa = row.namaDesa,
                kodeSls = row.kodeSls,
                namaSls = row.namaSls,
                targetResponden = row.targetResponden,
                latCentroid = row.latCentroid,
                lonCentroid = row.lonCentroid,
                versiMaster = maxOf(row.version, response.version)
            )
        }
        if (response.version > localVersion && rows.isEmpty()) {
            error(
                "Respons master wilayah tidak valid: versi server " +
                    "lebih baru tetapi tidak berisi data. Cache lokal " +
                    "dipertahankan."
            )
        }
        wilayahDao.replaceAll(rows)
        return MasterRefreshResult(
            updated = true,
            version = response.version,
            rowCount = rows.size
        )
    }
}
