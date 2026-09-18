package com.fajriantomanungki.revisitapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.fajriantomanungki.revisitapp.data.dashboard.DashboardCoverageRepository
import com.fajriantomanungki.revisitapp.data.local.dao.PendataanDao
import com.fajriantomanungki.revisitapp.data.local.dao.WilayahDao
import com.fajriantomanungki.revisitapp.data.local.dao.CakupanCacheDao
import com.fajriantomanungki.revisitapp.data.local.dao.FotoDao
import com.fajriantomanungki.revisitapp.data.local.dao.LaporanKegiatanDao
import com.fajriantomanungki.revisitapp.data.master.MasterWilayahRepository
import com.fajriantomanungki.revisitapp.data.dashboard.CakupanSyncRepository
import com.fajriantomanungki.revisitapp.data.pendataan.PendataanRepository
import com.fajriantomanungki.revisitapp.data.laporan.LaporanKegiatanRepository
import com.fajriantomanungki.revisitapp.data.sync.AppsScriptApi
import com.fajriantomanungki.revisitapp.data.sync.AppsScriptApiException
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
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var wilayahDao: WilayahDao

    @Inject
    lateinit var pendataanDao: PendataanDao

    @Inject
    lateinit var fotoDao: FotoDao

    @Inject
    lateinit var laporanKegiatanDao: LaporanKegiatanDao

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
    lateinit var laporanKegiatanRepository: LaporanKegiatanRepository

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
                        val cached = syncConfigStore.readCachedIdentity()
                        val canUseOfflineSession =
                            syncConfigStore.canLoginOffline(
                                idPetugas = credentials.idPetugas,
                                pin = credentials.pin
                            ) &&
                                cached?.kodeKabupaten?.isNotBlank() == true &&
                                cached?.kabupaten?.isNotBlank() == true &&
                                syncConfigStore.read() != null

                        if (!NetworkStatus.isConnected(this@MainActivity)) {
                            if (!canUseOfflineSession) {
                                Result.failure<String>(
                                    IllegalStateException(
                                        "Login offline belum tersedia untuk petugas ini. " +
                                            "Login online satu kali terlebih dahulu."
                                    )
                                )
                            } else {
                                activeConfig = syncConfigStore.read()
                                    ?: error("Sesi offline tidak dapat dipulihkan")
                                Result.success(
                                    "Login offline berhasil${cached?.nama?.takeIf { it.isNotBlank() }?.let { ", $it" }.orEmpty()}."
                                )
                            }
                        } else if (serverConfig == null) {
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
                            try {
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
                                Result.success(
                                    "Login berhasil${response.nama.takeIf { it.isNotBlank() }?.let { ", $it" }.orEmpty()}."
                                )
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (error: Throwable) {
                                if (canUseOfflineSession && isOfflineFallback(error)) {
                                    activeConfig = syncConfigStore.read()
                                        ?: throw IllegalStateException(
                                            "Sesi offline tidak dapat dipulihkan"
                                        )
                                    Result.success(
                                        "Server tidak dapat dihubungi. Login offline berhasil${cached?.nama?.takeIf { it.isNotBlank() }?.let { ", $it" }.orEmpty()}."
                                    )
                                } else {
                                    Result.failure(error)
                                }
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
                    val laporanKegiatan by laporanKegiatanDao.observeAll(idPetugas)
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

                    LaunchedEffect(idPetugas) {
                        if (!NetworkStatus.isConnected(this@MainActivity)) return@LaunchedEffect

                        isMasterRefreshing = true
                        val masterFailure = try {
                            masterWilayahRepository.refresh()
                            null
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (error: Throwable) {
                            error
                        }
                        isMasterRefreshing = false

                        isCoverageRefreshing = true
                        val coverageFailure = try {
                            cakupanSyncRepository.refresh()
                            null
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (error: Throwable) {
                            error
                        }
                        isCoverageRefreshing = false

                        val failures = listOfNotNull(
                            masterFailure?.message,
                            coverageFailure?.message
                        )
                        if (failures.isNotEmpty()) {
                            message = "Sinkronisasi awal belum lengkap: " +
                                failures.joinToString("; ")
                        }
                    }

                    RevisitAppShell(
                        idPetugas = idPetugas,
                        kodeKabupaten = identity.kodeKabupaten,
                        kabupaten = identity.kabupaten,
                        wilayah = wilayahPetugas,
                        pendataan = pendataan,
                        laporanKegiatan = laporanKegiatan,
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
                                try {
                                    message = try {
                                        val result = masterWilayahRepository.refresh()
                                        "Master wilayah diperbarui: ${result.rowCount} SLS, " +
                                            "versi ${result.version}."
                                    } catch (cancellation: CancellationException) {
                                        throw cancellation
                                    } catch (error: Throwable) {
                                        error.message ?: "Gagal memperbarui master wilayah."
                                    }
                                } finally {
                                    isMasterRefreshing = false
                                }
                            }
                        },
                        onRefreshCoverage = {
                            coroutineScope.launch {
                                isCoverageRefreshing = true
                                try {
                                    message = try {
                                        val result = cakupanSyncRepository.refresh()
                                        "Cakupan diperbarui: ${result.rowCount} SLS."
                                    } catch (cancellation: CancellationException) {
                                        throw cancellation
                                    } catch (error: Throwable) {
                                        error.message ?: "Gagal memperbarui cakupan."
                                    }
                                } finally {
                                    isCoverageRefreshing = false
                                }
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
                            try {
                                pendataanRepository.save(submission)
                                Result.success(Unit)
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (error: Throwable) {
                                Result.failure(error)
                            }
                        },
                        onSaveLaporanKegiatan = { tanggal, rangkuman, siapKirim ->
                            try {
                                laporanKegiatanRepository.save(
                                    idPetugas = idPetugas,
                                    tanggal = tanggal,
                                    rangkuman = rangkuman,
                                    siapKirim = siapKirim
                                )
                                Result.success(Unit)
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (error: Throwable) {
                                Result.failure(error)
                            }
                        },
                        onLoadPhotos = { idRecord ->
                            fotoDao.getByRecord(idRecord).filter { photo ->
                                photo.driveFileId?.isNotBlank() == true ||
                                    photo.pathLokal
                                        .takeIf { it.isNotBlank() }
                                        ?.let(::File)
                                        ?.isFile == true
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

private fun isOfflineFallback(error: Throwable): Boolean {
    val apiError = error as? AppsScriptApiException ?: return false
    return apiError.errorCode == "NETWORK_ERROR" ||
        apiError.httpCode == 408 ||
        apiError.httpCode == 429 ||
        apiError.httpCode?.let { it >= 500 } == true
}
