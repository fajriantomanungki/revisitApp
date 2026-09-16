package com.fajriantomanungki.revisitapp.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Master kecamatan, desa, dan SLS yang di-cache untuk penggunaan offline.
 * Kode wilayah disimpan sebagai String agar leading zero tetap terjaga.
 */
@Entity(
    tableName = "wilayah",
    indices = [
        Index(value = ["kode_kab"]),
        Index(value = ["kode_kec"]),
        Index(value = ["kode_desa"]),
        Index(value = ["versi_master"])
    ]
)
data class WilayahEntity(
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

    @PrimaryKey
    @ColumnInfo(name = "kode_sls")
    val kodeSls: String = "",

    @ColumnInfo(name = "nama_sls")
    val namaSls: String = "",

    @ColumnInfo(name = "target_responden")
    val targetResponden: Int = 0,

    @ColumnInfo(name = "lat_centroid")
    val latCentroid: Double? = null,

    @ColumnInfo(name = "lon_centroid")
    val lonCentroid: Double? = null,

    @ColumnInfo(name = "versi_master")
    val versiMaster: Int = 0
)
