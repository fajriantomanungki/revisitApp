package com.fajriantomanungki.revisitapp.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Snapshot cakupan terakhir dari server untuk Dashboard offline.
 */
@Entity(tableName = "cakupan_cache")
data class CakupanCacheEntity(
    @PrimaryKey
    @ColumnInfo(name = "kode_sls")
    val kodeSls: String,

    @ColumnInfo(name = "jumlah_terdata")
    val jumlahTerdata: Int = 0,

    @ColumnInfo(name = "target")
    val target: Int = 0,

    @ColumnInfo(name = "waktu_sinkron")
    val waktuSinkron: Long
) {
    init {
        require(kodeSls.isNotBlank()) {
            "kode_sls cache tidak boleh kosong"
        }
        require(jumlahTerdata >= 0) {
            "jumlah_terdata tidak boleh negatif"
        }
        require(target >= 0) {
            "target tidak boleh negatif"
        }
    }
}
