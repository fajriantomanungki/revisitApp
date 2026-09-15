package com.fajriantomanungki.revisitapp

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point sekaligus konfigurasi WorkManager dengan
 * HiltWorkerFactory. Tanpa konfigurasi ini, SyncWorker ber-@HiltWorker tidak
 * dapat dibuat ketika aplikasi berjalan di background.
 */
@HiltAndroidApp
class RevisitApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(Log.INFO)
            .build()
}
