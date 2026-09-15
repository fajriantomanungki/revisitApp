package com.fajriantomanungki.revisitapp.data.dashboard

import com.fajriantomanungki.revisitapp.data.local.dao.CakupanCacheDao
import com.fajriantomanungki.revisitapp.data.local.dao.PendataanDao
import com.fajriantomanungki.revisitapp.data.local.dao.WilayahDao
import com.fajriantomanungki.revisitapp.domain.coverage.LocalCoverageSource
import com.fajriantomanungki.revisitapp.domain.coverage.ServerCoverageSource
import com.fajriantomanungki.revisitapp.domain.coverage.SlsCoverage
import com.fajriantomanungki.revisitapp.domain.coverage.WilayahCoverageSource
import com.fajriantomanungki.revisitapp.domain.coverage.mergeCoverage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

data class DashboardCoverageSnapshot(
    val coverage: List<SlsCoverage>,
    val lastServerSyncMillis: Long?
)

/**
 * Satu sumber data reaktif untuk F-07.
 *
 * Ketiga flow berasal dari Room sehingga hasil terakhir tetap tersedia tanpa
 * jaringan. observeLocalCoverage() hanya menghitung SIAP_KIRIM, MENGIRIM,
 * dan GAGAL; record TERKIRIM tidak ditambahkan lagi ke snapshot server.
 */
class DashboardCoverageRepository @Inject constructor(
    private val wilayahDao: WilayahDao,
    private val cakupanCacheDao: CakupanCacheDao,
    private val pendataanDao: PendataanDao
) {

    fun observe(idPetugas: String): Flow<DashboardCoverageSnapshot> =
        combine(
            wilayahDao.observeAll(),
            cakupanCacheDao.observeAll(),
            pendataanDao.observeLocalCoverage(idPetugas)
        ) { wilayah, serverCache, localCoverage ->
            DashboardCoverageSnapshot(
                coverage = mergeCoverage(
                    wilayah = wilayah.map {
                        WilayahCoverageSource(
                            kodeKec = it.kodeKec,
                            namaKec = it.namaKec,
                            kodeDesa = it.kodeDesa,
                            namaDesa = it.namaDesa,
                            kodeSls = it.kodeSls,
                            namaSls = it.namaSls,
                            targetResponden = it.targetResponden.toLong(),
                            latCentroid = it.latCentroid,
                            lonCentroid = it.lonCentroid
                        )
                    },
                    serverCache = serverCache.map {
                        ServerCoverageSource(
                            kodeSls = it.kodeSls,
                            jumlahTerdata = it.jumlahTerdata.toLong(),
                            target = it.target.toLong()
                        )
                    },
                    localPending = localCoverage.map {
                        LocalCoverageSource(
                            kodeSls = it.kodeSls,
                            jumlahTerdata = it.jumlahTerdata
                        )
                    }
                ),
                lastServerSyncMillis = serverCache
                    .map { it.waktuSinkron }
                    .filter { it > 0L }
                    .maxOrNull()
            )
        }
}
