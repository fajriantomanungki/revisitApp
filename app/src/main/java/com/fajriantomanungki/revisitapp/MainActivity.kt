package com.fajriantomanungki.revisitapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.fajriantomanungki.revisitapp.data.dashboard.DashboardCoverageRepository
import com.fajriantomanungki.revisitapp.data.local.dao.PendataanDao
import com.fajriantomanungki.revisitapp.data.local.dao.WilayahDao
import com.fajriantomanungki.revisitapp.data.local.dao.CakupanCacheDao
import com.fajriantomanungki.revisitapp.data.master.MasterWilayahRepository
import com.fajriantomanungki.revisitapp.data.dashboard.CakupanSyncRepository
import com.fajriantomanungki.revisitapp.data.pendataan.PendataanRepository
import com.fajriantomanungki.revisitapp.data.sync.AppsScriptApi
import com.fajriantomanungki.revisitapp.data.sync.NetworkStatus
import com.fajriantomanungki.revisitapp.data.sync.SyncConfigStore
import com.fajriantomanungki.revisitapp.data.sync.SyncConfig
import com.fajriantomanungki.revisitapp.domain.location.LocationHelper
import com.fajriantomanungki.revisitapp.domain.media.WatermarkEngine
import com.fajriantomanungki.revisitapp.domain.safety.LogoutGuard
import com.fajriantomanungki.revisitapp.feature.app.RevisitAppShell
import com.fajriantomanungki.revisitapp.feature.auth.LoginScreen
import com.fajriantomanungki.revisitapp.ui.theme.RevisitAppTheme
import com.fajriantomanungki.revisitapp.worker.PhotoRetentionScheduler
import com.fajriantomanungki.revisitapp.worker.SyncWorkScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var wilayahDao: WilayahDao

    @Inject
    lateinit var pendataanDao: PendataanDao

    @Inject
    lateinit var cakupanCacheDao: CakupanCacheDao

    @Inject
    lateinit var syncConfigStore: SyncConfigStore

    @Inject
    lateinit var appsScriptApi: AppsScriptApi

    @Inject
    lateinit var masterWilayahRepository: MasterWilayahRepository

    @Inject
    lateinit var cakupanSyncRepository: CakupanSyncRepository

    @Inject
    lateinit var dashboardCoverageRepository: DashboardCoverageRepository

    @Inject
    lateinit var pendataanRepository: PendataanRepository

    @Inject
    lateinit var locationHelper: LocationHelper

    @Inject
    lateinit var watermarkEngine: WatermarkEngine

    @Inject
    lateinit var logoutGuard: LogoutGuard

    @Inject
    lateinit var syncWorkScheduler: SyncWorkScheduler

    @Inject
    lateinit var photoRetentionScheduler: PhotoRetentionScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        photoRetentionScheduler.schedule()

        setContent {
            RevisitAppTheme {
                var activeConfig by remember {
                    mutableStateOf(syncConfigStore.read())
                }

                val cachedIdentity = activeConfig?.let {
                    syncConfigStore.readCachedIdentity()
                }

                if (activeConfig == null || cachedIdentity?.kodeKabupaten.isNullOrBlank()) {
                    LoginScreen { credentials ->
                        val serverConfig = syncConfigStore.readServerConfig()
                        if (serverConfig == null) {
                            Result.failure<String>(
                                IllegalStateException(
                                    "Konfigurasi server belum tersedia. Tambahkan " +
                                        "APPS_SCRIPT_URL dan APPS_SCRIPT_TOKEN pada " +
                                        "local.properties, lalu build ulang aplikasi."
                                )
                            )
                        } else {
                            val config = SyncConfig(
                                endpointUrl = serverConfig.endpointUrl,
                                token = serverConfig.token,
                                idPetugas = credentials.idPetugas
                            )
                            runCatching {
                                val response = appsScriptApi.login(
                                    config = config,
                                    idPetugas = credentials.idPetugas,
                                    pin = credentials.pin
                                )
                                syncConfigStore.saveAuthenticated(
                                    endpointUrl = serverConfig.endpointUrl,
                                    token = serverConfig.token,
                                    idPetugas = response.idPetugas,
                                    nama = response.nama,
                                    pin = credentials.pin,
                                    kodeKabupaten = response.kodeKabupaten,
                                    kabupaten = response.kabupaten
                                )
                                activeConfig = syncConfigStore.read()
                                    ?: error("Sesi berhasil tetapi gagal disimpan")
                                "Login berhasil${response.nama.takeIf { it.isNotBlank() }?.let { ", $it" }.orEmpty()}."
                            }
                        }
                    }
                } else {
                    val config = requireNotNull(activeConfig)
                    val idPetugas = config.idPetugas
                    val identity = requireNotNull(cachedIdentity)
                    val wilayah by wilayahDao.observeAll()
                        .collectAsState(initial = emptyList())
                    val wilayahPetugas = wilayah.filter {
                        it.kodeKab == identity.kodeKabupaten
                    }
                    val pendataan by pendataanDao.observeAll(idPetugas)
                        .collectAsState(initial = emptyList())
                    val dashboardSnapshot by dashboardCoverageRepository
                        .observe(idPetugas)
                        .collectAsState(
                            initial = com.fajriantomanungki.revisitapp.data.dashboard.DashboardCoverageSnapshot()
                        )
                    val coroutineScope = rememberCoroutineScope()
                    var isMasterRefreshing by rememberSaveable { mutableStateOf(false) }
                    var isCoverageRefreshing by rememberSaveable { mutableStateOf(false) }
                    var message by rememberSaveable { mutableStateOf<String?>(null) }

                    RevisitAppShell(
                        idPetugas = idPetugas,
                        kodeKabupaten = identity.kodeKabupaten,
                        kabupaten = identity.kabupaten,
                        wilayah = wilayahPetugas,
                        pendataan = pendataan,
                        dashboardSnapshot = dashboardSnapshot,
                        locationHelper = locationHelper,
                        watermarkEngine = watermarkEngine,
                        isMasterRefreshing = isMasterRefreshing,
                        isCoverageRefreshing = isCoverageRefreshing,
                        message = message,
                        onDismissMessage = { message = null },
                        onRefreshMaster = {
                            coroutineScope.launch {
                                isMasterRefreshing = true
                                message = runCatching {
                                    masterWilayahRepository.refresh()
                                }.fold(
                                    onSuccess = { result ->
                                        "Master wilayah diperbarui: ${result.rowCount} SLS, versi ${result.version}."
                                    },
                                    onFailure = { error ->
                                        error.message ?: "Gagal memperbarui master wilayah."
                                    }
                                )
                                isMasterRefreshing = false
                            }
                        },
                        onRefreshCoverage = {
                            coroutineScope.launch {
                                isCoverageRefreshing = true
                                message = runCatching {
                                    cakupanSyncRepository.refresh()
                                }.fold(
                                    onSuccess = { result ->
                                        "Cakupan diperbarui: ${result.rowCount} SLS."
                                    },
                                    onFailure = { error ->
                                        error.message ?: "Gagal memperbarui cakupan."
                                    }
                                )
                                isCoverageRefreshing = false
                            }
                        },
                        onSend = {
                            if (!NetworkStatus.isConnected(this@MainActivity)) {
                                syncWorkScheduler.enqueue(idPetugas)
                                message =
                                    "Tidak ada koneksi. Data tetap aman tersimpan; " +
                                        "pengiriman akan dilanjutkan otomatis saat online."
                            } else {
                                syncWorkScheduler.enqueue(idPetugas)
                                message = "Antrean pengiriman dibuat."
                            }
                        },
                        onSavePendataan = { submission ->
                            runCatching {
                                pendataanRepository.saveNew(submission)
                                Unit
                            }
                        },
                        onLogout = {
                            logoutGuard.logoutIfAllowed(idPetugas) {
                                cakupanCacheDao.deleteAll()
                                syncConfigStore.clearSession()
                                activeConfig = null
                            }
                        },
                        onDeletePendataan = { row ->
                            pendataanRepository.deleteIfNotSent(row)
                        }
                    )
                }
            }
        }
    }
}
