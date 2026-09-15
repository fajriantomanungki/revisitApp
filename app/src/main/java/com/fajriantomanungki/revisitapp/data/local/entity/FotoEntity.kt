package com.fajriantomanungki.revisitapp.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.fajriantomanungki.revisitapp.data.local.model.UuidV4

/**
 * Foto ber-watermark yang disimpan pada internal storage aplikasi.
 */
@Entity(
    tableName = "foto",
    foreignKeys = [
        ForeignKey(
            entity = PendataanEntity::class,
            parentColumns = ["id_record"],
            childColumns = ["id_record"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["id_record"]),
        Index(value = ["id_record", "urutan"], unique = true)
    ]
)
data class FotoEntity(
    @PrimaryKey
    @ColumnInfo(name = "id_foto")
    val idFoto: String = UuidV4.generate(),

    @ColumnInfo(name = "id_record")
    val idRecord: String,

    @ColumnInfo(name = "path_lokal")
    val pathLokal: String,

    @ColumnInfo(name = "drive_file_id")
    val driveFileId: String? = null,

    @ColumnInfo(name = "urutan")
    val urutan: Int,

    @ColumnInfo(name = "sudah_diunggah")
    val sudahDiunggah: Boolean = false
) {
    init {
        require(UuidV4.isValid(idFoto)) {
            "id_foto harus berupa UUID v4 yang valid"
        }
        require(urutan in 1..3) {
            "urutan foto harus berada pada rentang 1 sampai 3"
        }
    }
}
