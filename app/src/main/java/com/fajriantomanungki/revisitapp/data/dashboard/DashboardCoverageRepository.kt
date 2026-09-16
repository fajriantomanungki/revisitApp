package com.fajriantomanungki.revisitapp.data.dashboard

import com.fajriantomanungki.revisitapp.data.local.dao.CakupanCacheDao
import com.fajriantomanungki.revisitapp.data.local.dao.PendataanDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

const val SLS_PER_PETUGAS = 14
const val RESPONDEN_PER_SLS = 9
const val TARGET_PER_PETUGAS = SLS_PER_PETUGAS * RESPONDEN_PER_SLS

/** Ringkasan progres satu petugas yang dapat ditampilkan tanpa jaringan. */
data class DashboardCoverageSnapshot(
    val target: Long = TARGET_PER_PETUGAS.toLong(),
    val serverTerdata: Long = 0L,
    val localPending: Long = 0L,
    val lastServerSyncMillis: Long? = null
) {
    /** Realisasi adalah data yang sudah ada di server ditambah antrean lokal. */
    val terdata: Long
        get() = (serverTerdata + localPending).coerceAtLeast(0L)

    /** Persentase boleh lebih dari 100 untuk menampakkan data berlebih. */
    val persenPenyelesaian: Double
        get() = if (target > 0L) {
            terdata.toDouble() * 100.0 / target.toDouble()
        } else {
            0.0
        }

    /** Nilai yang aman untuk LinearProgressIndicator. */
    val progressFraction: Float
        get() = (persenPenyelesaian / 100.0)
            .coerceIn(0.0, 1.0)
            .toFloat()
}

/**
 * Sumber data reaktif F-07.
 *
 * Cache server berisi record yang sudah diterima Apps Script. Data lokal
 * berstatus SIAP_KIRIM, MENGIRIM, atau GAGAL ditambahkan agar progres di
 * perangkat tidak mundur ketika perangkat sedang offline.
 */
class DashboardCoverageRepository @Inject constructor(
    private val cakupanCacheDao: CakupanCacheDao,
    private val pendataanDao: PendataanDao
) {

    fun observe(idPetugas: String): Flow<DashboardCoverageSnapshot> =
        combine(
            cakupanCacheDao.observeAll(),
            pendataanDao.observeLocalCoverage(idPetugas)
        ) { serverCache, localCoverage ->
            DashboardCoverageSnapshot(
                serverTerdata = serverCache.sumOf { row ->
                    row.jumlahTerdata.toLong().coerceAtLeast(0L)
                },
                localPending = localCoverage.sumOf { row ->
                    row.jumlahTerdata.coerceAtLeast(0L)
                },
                lastServerSyncMillis = serverCache
                    .map { it.waktuSinkron }
                    .filter { it > 0L }
                    .maxOrNull()
            )
        }
}
