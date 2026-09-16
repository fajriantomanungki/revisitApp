package com.fajriantomanungki.revisitapp.data.local

import android.content.Context
import androidx.room.Room
import com.fajriantomanungki.revisitapp.data.local.dao.CakupanCacheDao
import com.fajriantomanungki.revisitapp.data.local.dao.FotoDao
import com.fajriantomanungki.revisitapp.data.local.dao.LaporanKegiatanDao
import com.fajriantomanungki.revisitapp.data.local.dao.PendataanDao
import com.fajriantomanungki.revisitapp.data.local.dao.WilayahDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module untuk menyediakan database dan seluruh DAO sebagai singleton.
 *
 * fallbackToDestructiveMigration() sengaja tidak digunakan karena database
 * lokal berisi data lapangan yang mungkin belum tersinkronisasi.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        ).addMigrations(
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4
        ).build()
    }

    @Provides
    fun provideWilayahDao(database: AppDatabase): WilayahDao {
        return database.wilayahDao()
    }

    @Provides
    fun providePendataanDao(database: AppDatabase): PendataanDao {
        return database.pendataanDao()
    }

    @Provides
    fun provideFotoDao(database: AppDatabase): FotoDao {
        return database.fotoDao()
    }

    @Provides
    fun provideCakupanCacheDao(database: AppDatabase): CakupanCacheDao {
        return database.cakupanCacheDao()
    }

    @Provides
    fun provideLaporanKegiatanDao(database: AppDatabase): LaporanKegiatanDao {
        return database.laporanKegiatanDao()
    }
}
