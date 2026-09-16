package com.fajriantomanungki.revisitapp.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.fajriantomanungki.revisitapp.data.local.model.LaporanKegiatanStatus
import com.fajriantomanungki.revisitapp.data.local.model.UuidV4

/**
 * Rangkuman kegiatan petugas untuk satu tanggal kalender.
 *
 * Satu petugas hanya memiliki satu laporan per tanggal. Isi laporan tetap
 * berada di Room terlebih dahulu; pengiriman ke Apps Script adalah proses
 * terpisah yang dapat diulang.
 */
@Entity(
    tableName = "laporan_kegiatan",
    indices = [
        Index(value = ["id_petugas"]),
        Index(value = ["tanggal"]),
        Index(value = ["status_kirim"]),
        Index(value = ["id_petugas", "tanggal"], unique = true)
    ]
)
data class LaporanKegiatanEntity(
    @PrimaryKey
    @ColumnInfo(name = "id_laporan")
    val idLaporan: String = UuidV4.generate(),

    @ColumnInfo(name = "id_petugas")
    val idPetugas: String,

    /** Format wajib yyyy-MM-dd agar aman dipakai sebagai kunci harian. */
    @ColumnInfo(name = "tanggal")
    val tanggal: String,

    @ColumnInfo(name = "rangkuman")
    val rangkuman: String = "",

    @ColumnInfo(name = "status_kirim")
    val statusKirim: String = LaporanKegiatanStatus.DRAFT,

    @ColumnInfo(name = "pesan_error")
    val pesanError: String? = null,

    @ColumnInfo(name = "percobaan_kirim")
    val percobaanKirim: Int = 0,

    @ColumnInfo(name = "waktu_dibuat")
    val waktuDibuat: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "waktu_diubah")
    val waktuDiubah: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "waktu_terkirim")
    val waktuTerkirim: Long? = null,

    @ColumnInfo(name = "versi_app")
    val versiApp: String = ""
) {
    init {
        require(UuidV4.isValid(idLaporan)) {
            "id_laporan harus berupa UUID v4 yang valid"
        }
        require(Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(tanggal)) {
            "tanggal laporan harus berformat yyyy-MM-dd"
        }
        require(idPetugas.isNotBlank()) {
            "id_petugas laporan tidak boleh kosong"
        }
    }
}
