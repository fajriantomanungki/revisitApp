package com.fajriantomanungki.revisitapp.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
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
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun wilayahDao(): WilayahDao

    abstract fun pendataanDao(): PendataanDao

    abstract fun fotoDao(): FotoDao

    abstract fun cakupanCacheDao(): CakupanCacheDao

    companion object {
        const val DATABASE_NAME = "pendataan.db"
    }
}
