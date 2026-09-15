package com.fajriantomanungki.revisitapp.feature.app

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.fajriantomanungki.revisitapp.data.dashboard.DashboardCoverageSnapshot
import com.fajriantomanungki.revisitapp.data.local.entity.PendataanEntity
import com.fajriantomanungki.revisitapp.data.local.entity.WilayahEntity
import com.fajriantomanungki.revisitapp.data.local.model.SyncStatus
import com.fajriantomanungki.revisitapp.data.pendataan.PendataanFormSubmission
import com.fajriantomanungki.revisitapp.domain.location.LocationHelper
import com.fajriantomanungki.revisitapp.domain.media.WatermarkEngine
import com.fajriantomanungki.revisitapp.domain.safety.LogoutCheckResult
import com.fajriantomanungki.revisitapp.feature.dashboard.DashboardScreen
import com.fajriantomanungki.revisitapp.feature.pendataan.PendataanFormScreen
import com.fajriantomanungki.revisitapp.feature.wilayah.MasterWilayahScreen

@Composable
fun RevisitAppShell(
    idPetugas: String,
    wilayah: List<WilayahEntity>,
    pendataan: List<PendataanEntity>,
    dashboardSnapshot: DashboardCoverageSnapshot,
    locationHelper: LocationHelper,
    watermarkEngine: WatermarkEngine,
    isMasterRefreshing: Boolean,
    isCoverageRefreshing: Boolean,
    message: String?,
    onDismissMessage: () -> Unit,
    onRefreshMaster: () -> Unit,
    onRefreshCoverage: () -> Unit,
    onSend: () -> Unit,
    onSavePendataan: suspend (PendataanFormSubmission) -> Result<Unit>,
    onLogout: suspend () -> LogoutCheckResult,
    onDeletePendataan: suspend (PendataanEntity) -> Boolean
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var isFormOpen by rememberSaveable { mutableStateOf(false) }
    var isLoggingOut by remember { mutableStateOf(false) }
    var rowToDelete by remember { mutableStateOf<PendataanEntity?>(null) }
    var isDeleting by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    var showNotificationRationale by rememberSaveable {
        mutableStateOf(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
        )
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        showNotificationRationale = false
    }

    LaunchedEffect(message) {
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            onDismissMessage()
        }
    }

    if (showNotificationRationale) {
        AlertDialog(
            onDismissRequest = { showNotificationRationale = false },
            title = { Text("Izin notifikasi") },
            text = {
                Text(
                    "Notifikasi digunakan untuk memantau progres pengiriman " +
                        "data saat aplikasi berjalan di latar belakang."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        notificationPermissionLauncher.launch(
                            Manifest.permission.POST_NOTIFICATIONS
                        )
                    }
                ) {
                    Text("Izinkan")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showNotificationRationale = false }
                ) {
                    Text("Nanti")
                }
            }
        )
    }

    rowToDelete?.let { candidate ->
        AlertDialog(
            onDismissRequest = { if (!isDeleting) rowToDelete = null },
            title = { Text("Hapus pendataan?") },
            text = {
                Text(
                    "Entri ${candidate.namaObjek.ifBlank { candidate.idRecord }} " +
                        "dan foto lokalnya akan dihapus. Entri TERKIRIM tidak dapat dihapus."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            isDeleting = true
                            val deleted = runCatching {
                                onDeletePendataan(candidate)
                            }.getOrDefault(false)
                            isDeleting = false
                            rowToDelete = null
                            snackbarHostState.showSnackbar(
                                if (deleted) "Pendataan dihapus dari perangkat."
                                else "Pendataan tidak dapat dihapus."
                            )
                        }
                    },
                    enabled = !isDeleting
                ) {
                    Text(if (isDeleting) "Menghapus..." else "Hapus")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { rowToDelete = null },
                    enabled = !isDeleting
                ) {
                    Text("Batal")
                }
            }
        )
    }

    if (isFormOpen) {
        PendataanFormScreen(
            idPetugas = idPetugas,
            wilayah = wilayah,
            locationHelper = locationHelper,
            watermarkEngine = watermarkEngine,
            onSave = onSavePendataan,
            onSaved = {
                isFormOpen = false
                selectedTab = 0
                message = "Pendataan tersimpan di perangkat."
            },
            onBack = { isFormOpen = false }
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            NavigationBar {
                listOf("Pendataan", "Wilayah", "Dashboard").forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Text(label.first().toString()) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { paddingValues ->
        when (selectedTab) {
            0 -> PendataanListScreen(
                modifier = Modifier.padding(paddingValues),
                idPetugas = idPetugas,
                rows = pendataan,
                onSend = onSend,
                canAdd = wilayah.isNotEmpty(),
                onDelete = { rowToDelete = it },
                isLoggingOut = isLoggingOut,
                onLogout = {
                    coroutineScope.launch {
                        isLoggingOut = true
                        val result = runCatching { onLogout() }
                            .getOrElse { error ->
                                LogoutCheckResult(
                                    canLogout = false,
                                    draftCount = 0,
                                    siapKirimCount = 0,
                                    mengirimCount = 0,
                                    gagalCount = 0,
                                    warningMessage = error.message ?: "Logout gagal."
                                )
                            }
                        isLoggingOut = false
                        if (!result.canLogout) {
                            snackbarHostState.showSnackbar(
                                result.warningMessage ?: "Logout ditolak."
                            )
                        }
                    }
                },
                onAdd = {
                    if (wilayah.isEmpty()) {
                        message =
                            "Master wilayah belum tersedia. Perbarui data wilayah terlebih dahulu."
                    } else {
                        isFormOpen = true
                    }
                }
            )

            1 -> MasterWilayahScreen(
                modifier = Modifier.padding(paddingValues),
                wilayah = wilayah,
                isRefreshing = isMasterRefreshing,
                onRefreshMaster = onRefreshMaster
            )

            else -> DashboardScreen(
                modifier = Modifier.padding(paddingValues),
                snapshot = dashboardSnapshot,
                isRefreshing = isCoverageRefreshing,
                onRefreshCoverage = onRefreshCoverage,
                onNavigate = { sls ->
                    val latitude = sls.latCentroid
                    val longitude = sls.lonCentroid
                    if (latitude == null || longitude == null) {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                "Centroid SLS belum tersedia untuk navigasi."
                            )
                        }
                    } else {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(
                                        "geo:$latitude,$longitude?q=$latitude,$longitude(" +
                                            Uri.encode(sls.namaSls) + ")"
                                    )
                                )
                            )
                        }.onFailure {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    "Tidak ada aplikasi peta yang dapat dibuka."
                                )
                            }
                        }
                    }
                },
                onOpenSlsData = { kodeSls ->
                    selectedTab = 0
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(
                            "Daftar pendataan untuk SLS $kodeSls dipilih."
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun PendataanListScreen(
    modifier: Modifier,
    idPetugas: String,
    rows: List<PendataanEntity>,
    onSend: () -> Unit,
    canAdd: Boolean,
    onDelete: (PendataanEntity) -> Unit,
    isLoggingOut: Boolean,
    onLogout: () -> Unit,
    onAdd: () -> Unit
) {
    val waiting = rows.count {
        it.statusKirim == SyncStatus.SIAP_KIRIM ||
            it.statusKirim == SyncStatus.GAGAL
    }
    val sent = rows.count { it.statusKirim == SyncStatus.TERKIRIM }
    val failed = rows.count { it.statusKirim == SyncStatus.GAGAL }
    val staleThreshold = System.currentTimeMillis() - 24L * 60L * 60L * 1_000L
    val staleRows = rows.count {
        it.statusKirim != SyncStatus.TERKIRIM &&
            it.waktuDibuat <= staleThreshold
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Daftar Pendataan",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = if (idPetugas.isBlank()) {
                "Sesi petugas belum dikonfigurasi"
            } else {
                "Petugas: $idPetugas"
            }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            OutlinedButton(
                onClick = onLogout,
                enabled = !isLoggingOut
            ) {
                Text(if (isLoggingOut) "Memeriksa..." else "Logout")
            }
        }
        OutlinedActionButton(
            text = "+ Tambah Pendataan",
            onClick = onAdd,
            enabled = canAdd,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SummaryCard("Terkirim", sent, Color(0xFF1B5E20), Modifier.weight(1f))
            SummaryCard("Menunggu", waiting, Color(0xFF1565C0), Modifier.weight(1f))
            SummaryCard("Gagal", failed, MaterialTheme.colorScheme.error, Modifier.weight(1f))
        }
        Button(
            onClick = onSend,
            enabled = waiting > 0,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Kirim ($waiting)")
        }

        if (staleRows > 0) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = "Peringatan: $staleRows entri belum terkirim " +
                        "lebih dari 24 jam.",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        if (rows.isEmpty()) {
            Text(
                text = "Belum ada entri. Form pendataan akan menggunakan sumber data Room ini.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(rows, key = { it.idRecord }) { row ->
                    PendataanRow(row, onDelete = { onDelete(row) })
                }
            }
        }
    }
}

@Composable
private fun OutlinedActionButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier
) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
    ) {
        Text(text)
    }
}

@Composable
private fun SummaryCard(
    label: String,
    count: Int,
    color: Color,
    modifier: Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.14f)
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(count.toString(), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PendataanRow(
    row: PendataanEntity,
    onDelete: () -> Unit
) {
    val (label, color, symbol) = when (row.statusKirim) {
        SyncStatus.DRAFT -> Triple("DRAFT", Color.Gray, "○")
        SyncStatus.SIAP_KIRIM -> Triple("SIAP KIRIM", Color(0xFF1565C0), "●")
        SyncStatus.MENGIRIM -> Triple("MENGIRIM", Color(0xFF1565C0), "↻")
        SyncStatus.TERKIRIM -> Triple("TERKIRIM", Color(0xFF1B5E20), "✓")
        SyncStatus.GAGAL -> Triple("GAGAL", Color(0xFFB71C1C), "!")
        else -> Triple(row.statusKirim, Color.Gray, "?")
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = row.namaObjek.ifBlank { "Tanpa nama objek" },
                fontWeight = FontWeight.Bold
            )
            Text(
                text = listOf(row.namaSls, row.jenisObjek)
                    .filter { it.isNotBlank() }
                    .joinToString(" • ")
            )
            Text(
                text = "$symbol $label",
                color = color,
                fontWeight = FontWeight.Medium
            )
            row.pesanError?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            if (row.statusKirim == SyncStatus.TERKIRIM) {
                Text(
                    text = "Read-only: sudah tersimpan di server",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                TextButton(onClick = onDelete) {
                    Text("Hapus")
                }
            }
        }
    }
}
