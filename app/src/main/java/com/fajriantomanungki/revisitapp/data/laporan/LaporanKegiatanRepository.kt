package com.fajriantomanungki.revisitapp.data.laporan

import com.fajriantomanungki.revisitapp.data.local.AppDatabase
import com.fajriantomanungki.revisitapp.data.local.entity.LaporanKegiatanEntity
import com.fajriantomanungki.revisitapp.data.local.model.LaporanKegiatanStatus
import com.fajriantomanungki.revisitapp.domain.safety.LocalSessionCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Menyimpan satu rangkuman kegiatan untuk satu petugas dan satu tanggal.
 * Upsert berdasarkan pasangan id_petugas + tanggal menjaga agar laporan
 * harian dapat diedit tanpa membuat baris duplikat.
 */
class LaporanKegiatanRepository @Inject constructor(
    private val database: AppDatabase,
    private val sessionCoordinator: LocalSessionCoordinator
) {

    suspend fun save(
        idPetugas: String,
        tanggal: String,
        rangkuman: String,
        siapKirim: Boolean,
        versiApp: String = "0.1.0"
    ): String = sessionCoordinator.withMutationLock {
        withContext(Dispatchers.IO) {
            require(idPetugas.isNotBlank()) { "id_petugas laporan wajib tersedia" }
            require(DATE_PATTERN.matches(tanggal)) {
                "Tanggal laporan harus berformat yyyy-MM-dd"
            }
            require(rangkuman.trim().isNotBlank()) {
                "Rangkuman kegiatan wajib diisi"
            }

            val dao = database.laporanKegiatanDao()
            val existing = dao.getByDate(idPetugas, tanggal)
            if (existing?.statusKirim == LaporanKegiatanStatus.MENGIRIM) {
                error("Laporan sedang dikirim. Tunggu sampai proses selesai sebelum mengedit.")
            }
            val now = System.currentTimeMillis()
            val row = (existing ?: LaporanKegiatanEntity(
                idPetugas = idPetugas,
                tanggal = tanggal,
                waktuDibuat = now,
                waktuDiubah = now,
                versiApp = versiApp
            )).copy(
                rangkuman = rangkuman.trim(),
                statusKirim = if (siapKirim) {
                    LaporanKegiatanStatus.SIAP_KIRIM
                } else {
                    LaporanKegiatanStatus.DRAFT
                },
                pesanError = null,
                percobaanKirim = if (siapKirim) 0 else existing?.percobaanKirim ?: 0,
                waktuTerkirim = if (siapKirim) null else existing?.waktuTerkirim,
                waktuDiubah = now,
                versiApp = versiApp
            )
            dao.upsert(row)
            row.idLaporan
        }
    }

    private companion object {
        val DATE_PATTERN = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    }
}
