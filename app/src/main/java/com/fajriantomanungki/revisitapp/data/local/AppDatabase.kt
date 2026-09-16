package com.fajriantomanungki.revisitapp.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.fajriantomanungki.revisitapp.data.local.dao.CakupanCacheDao
import com.fajriantomanungki.revisitapp.data.local.dao.FotoDao
import com.fajriantomanungki.revisitapp.data.local.dao.PendataanDao
import com.fajriantomanungki.revisitapp.data.local.dao.WilayahDao
import com.fajriantomanungki.revisitapp.data.local.entity.CakupanCacheEntity
import com.fajriantomanungki.revisitapp.data.local.entity.FotoEntity
import com.fajriantomanungki.revisitapp.data.local.entity.PendataanEntity
import com.fajriantomanungki.revisitapp.data.local.entity.WilayahEntity

@Database(
    entities = [
        WilayahEntity::class,
        PendataanEntity::class,
        FotoEntity::class,
        CakupanCacheEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun wilayahDao(): WilayahDao

    abstract fun pendataanDao(): PendataanDao

    abstract fun fotoDao(): FotoDao

    abstract fun cakupanCacheDao(): CakupanCacheDao

    companion object {
        const val DATABASE_NAME = "pendataan.db"

        /** Menambahkan hierarki Kabupaten tanpa menghapus cache/data lokal. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE wilayah ADD COLUMN kode_kab TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "ALTER TABLE wilayah ADD COLUMN kabupaten TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_wilayah_kode_kab " +
                        "ON wilayah(kode_kab)"
                )
            }
        }

        /**
         * Menambahkan kabupaten pada pendataan dan menyelaraskan struktur
         * tabel wilayah ke skema master terbaru.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE pendataan ADD COLUMN kode_kab TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "ALTER TABLE pendataan ADD COLUMN kabupaten TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_pendataan_kode_kab " +
                        "ON pendataan(kode_kab)"
                )

                database.execSQL(
                    """
                    CREATE TABLE wilayah_new (
                        kode_kab TEXT NOT NULL DEFAULT '',
                        kabupaten TEXT NOT NULL DEFAULT '',
                        kode_kec TEXT NOT NULL,
                        nama_kec TEXT NOT NULL,
                        kode_desa TEXT NOT NULL,
                        nama_desa TEXT NOT NULL,
                        kode_sls TEXT NOT NULL,
                        nama_sls TEXT NOT NULL,
                        target_responden INTEGER NOT NULL,
                        versi_master INTEGER NOT NULL,
                        PRIMARY KEY(kode_sls)
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT INTO wilayah_new (
                        kode_kab, kabupaten, kode_kec, nama_kec,
                        kode_desa, nama_desa, kode_sls, nama_sls,
                        target_responden, versi_master
                    )
                    SELECT kode_kab, kabupaten, kode_kec, nama_kec,
                           kode_desa, nama_desa, kode_sls, nama_sls,
                           target_responden, versi_master
                    FROM wilayah
                    """.trimIndent()
                )
                database.execSQL("DROP TABLE wilayah")
                database.execSQL("ALTER TABLE wilayah_new RENAME TO wilayah")
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_wilayah_kode_kab " +
                        "ON wilayah(kode_kab)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_wilayah_kode_kec " +
                        "ON wilayah(kode_kec)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_wilayah_kode_desa " +
                        "ON wilayah(kode_desa)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_wilayah_versi_master " +
                        "ON wilayah(versi_master)"
                )
            }
        }
    }
}
