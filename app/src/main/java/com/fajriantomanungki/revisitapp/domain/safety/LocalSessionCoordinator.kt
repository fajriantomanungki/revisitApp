package com.fajriantomanungki.revisitapp.domain.safety

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Menserialkan mutasi data lokal yang berhubungan dengan sesi petugas.
 *
 * Logout harus melihat snapshot yang sama dengan operasi simpan/sinkronisasi.
 * Mutex ini tidak menggantikan transaksi Room, tetapi menutup celah antara
 * pemeriksaan antrean dan penghapusan sesi pada proses aplikasi yang sama.
 */
@Singleton
class LocalSessionCoordinator @Inject constructor() {

    private val mutex = Mutex()

    suspend fun <T> withMutationLock(block: suspend () -> T): T =
        mutex.withLock { block() }
}
