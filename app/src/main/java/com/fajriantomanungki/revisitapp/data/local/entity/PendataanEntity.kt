package com.fajriantomanungki.revisitapp.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.fajriantomanungki.revisitapp.data.local.model.SyncStatus
import com.fajriantomanungki.revisitapp.data.local.model.UuidV4

/**
 * Satu entri pendataan lokal.
 *
 * Nilai kosong/null diperbolehkan pada field form tertentu agar draf dapat
 * disimpan sebelum seluruh data lengkap.
 */
@Entity(
    tableName = "pendataan",
    indices = [
        Index(value = ["id_petugas"]),
        Index(value = ["kode_kab"]),
        Index(value = ["kode_sls"]),
        Index(value = ["status_kirim"]),
        Index(value = ["waktu_dibuat"]),
        Index(value = ["id_petugas", "status_kirim"])
    ]
)
data class PendataanEntity(
    @PrimaryKey
    @ColumnInfo(name = "id_record")
    val idRecord: String = UuidV4.generate(),

    @ColumnInfo(name = "id_petugas")
    val idPetugas: String,

    @ColumnInfo(name = "kode_kab", defaultValue = "''")
    val kodeKab: String = "",

    @ColumnInfo(name = "kabupaten", defaultValue = "''")
    val kabupaten: String = "",

    @ColumnInfo(name = "kode_kec")
    val kodeKec: String = "",

    @ColumnInfo(name = "nama_kec")
    val namaKec: String = "",

    @ColumnInfo(name = "kode_desa")
    val kodeDesa: String = "",

    @ColumnInfo(name = "nama_desa")
    val namaDesa: String = "",

    @ColumnInfo(name = "kode_sls")
    val kodeSls: String = "",

    @ColumnInfo(name = "nama_sls")
    val namaSls: String = "",

    @ColumnInfo(name = "jenis_objek")
    val jenisObjek: String = "",

    @ColumnInfo(name = "nama_objek")
    val namaObjek: String = "",

    @ColumnInfo(name = "alamat")
    val alamat: String = "",

    @ColumnInfo(name = "status_pendataan")
    val statusPendataan: String = "",

    @ColumnInfo(name = "catatan")
    val catatan: String = "",

    @ColumnInfo(name = "latitude")
    val latitude: Double? = null,

    @ColumnInfo(name = "longitude")
    val longitude: Double? = null,

    @ColumnInfo(name = "akurasi_m")
    val akurasiM: Double? = null,

    @ColumnInfo(name = "is_mock")
    val isMock: Boolean = false,

    /**
     * Epoch millis UTC. Nullable karena draf dapat disimpan sebelum lokasi
     * diambil.
     */
    @ColumnInfo(name = "waktu_pendataan")
    val waktuPendataan: Long? = null,

    @ColumnInfo(name = "status_kirim")
    val statusKirim: String = SyncStatus.DRAFT,

    /** True bila record TERKIRIM diedit dan harus diperbarui di server. */
    @ColumnInfo(name = "replace_existing")
    val replaceExisting: Boolean = false,

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
        require(UuidV4.isValid(idRecord)) {
            "id_record harus berupa UUID v4 yang valid"
        }
    }
}
