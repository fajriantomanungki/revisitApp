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
    version = 2,
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
    }
}
