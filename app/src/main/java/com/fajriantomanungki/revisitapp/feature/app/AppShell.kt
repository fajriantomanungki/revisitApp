package com.fajriantomanungki.revisitapp.feature.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.fajriantomanungki.revisitapp.data.dashboard.DashboardCoverageSnapshot
import com.fajriantomanungki.revisitapp.data.local.entity.LaporanKegiatanEntity
import com.fajriantomanungki.revisitapp.data.local.entity.PendataanEntity
import com.fajriantomanungki.revisitapp.data.local.entity.FotoEntity
import com.fajriantomanungki.revisitapp.data.local.entity.WilayahEntity
import com.fajriantomanungki.revisitapp.data.local.model.SyncStatus
import com.fajriantomanungki.revisitapp.data.pendataan.PendataanFormSubmission
import com.fajriantomanungki.revisitapp.domain.location.LocationHelper
import com.fajriantomanungki.revisitapp.domain.media.WatermarkEngine
import com.fajriantomanungki.revisitapp.domain.safety.LogoutCheckResult
import com.fajriantomanungki.revisitapp.feature.dashboard.DashboardScreen
import com.fajriantomanungki.revisitapp.feature.pendataan.PendataanFormScreen
import com.fajriantomanungki.revisitapp.feature.laporan.LaporanKegiatanScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun RevisitAppShell(
    idPetugas: String,
    kodeKabupaten: String,
    kabupaten: String,
    wilayah: List<WilayahEntity>,
    pendataan: List<PendataanEntity>,
    laporanKegiatan: List<LaporanKegiatanEntity>,
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
    onLoadPhotos: suspend (String) -> List<FotoEntity>,
    onSaveLaporanKegiatan: suspend (tanggal: String, rangkuman: String, siapKirim: Boolean) -> Result<Unit>,
    onLogout: suspend () -> LogoutCheckResult,
    onDeletePendataan: suspend (PendataanEntity) -> Boolean
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var isFormOpen by rememberSaveable { mutableStateOf(false) }
    var editingRow by remember { mutableStateOf<PendataanEntity?>(null) }
    var editingPhotos by remember { mutableStateOf<List<FotoEntity>>(emptyList()) }
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

    fun openEdit(row: PendataanEntity) {
        editingRow = row
        editingPhotos = emptyList()
        isFormOpen = false
        coroutineScope.launch {
            val result: Result<List<FotoEntity>> = try {
                Result.success(onLoadPhotos(row.idRecord))
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }
            if (editingRow?.idRecord != row.idRecord) return@launch
            result.fold(
                onSuccess = {
                    editingPhotos = it
                    isFormOpen = true
                },
                onFailure = {
                    editingRow = null
                    snackbarHostState.showSnackbar(
                        it.message ?: "Foto lama tidak dapat dimuat."
                    )
                }
            )
        }
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
                            val deleted = try {
                                onDeletePendataan(candidate)
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (_: Throwable) {
                                false
                            }
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
            kodeKabupaten = kodeKabupaten,
            wilayah = wilayah,
            locationHelper = locationHelper,
            watermarkEngine = watermarkEngine,
            existing = editingRow,
            existingPhotos = editingPhotos,
            onSave = onSavePendataan,
            onSaved = {
                isFormOpen = false
                editingRow = null
                editingPhotos = emptyList()
                selectedTab = 0
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Pendataan tersimpan di perangkat.")
                }
            },
            onBack = {
                isFormOpen = false
                editingRow = null
                editingPhotos = emptyList()
            }
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                listOf(
                    "Pendataan" to "P",
                    "Laporan" to "L",
                    "Dashboard" to "D"
                ).forEachIndexed { index, (label, glyph) ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = {
                            NavigationGlyph(
                                glyph = glyph,
                                selected = selectedTab == index
                            )
                        },
                        label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    ) { paddingValues ->
        when (selectedTab) {
            0 -> PendataanListScreen(
                modifier = Modifier.padding(paddingValues),
                idPetugas = idPetugas,
                kabupaten = kabupaten,
                rows = pendataan,
                onSend = onSend,
                canAdd = wilayah.isNotEmpty(),
                onDelete = { rowToDelete = it },
                onEdit = ::openEdit,
                isLoggingOut = isLoggingOut,
                onLogout = {
                    coroutineScope.launch {
                        isLoggingOut = true
                        val result = try {
                            onLogout()
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (error: Throwable) {
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
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                "Master wilayah belum tersedia. Perbarui data wilayah terlebih dahulu."
                            )
                        }
                    } else {
                        editingRow = null
                        isFormOpen = true
                    }
                }
            )

            1 -> LaporanKegiatanScreen(
                modifier = Modifier.padding(paddingValues),
                idPetugas = idPetugas,
                kabupaten = kabupaten,
                pendataan = pendataan,
                laporan = laporanKegiatan,
                isMasterRefreshing = isMasterRefreshing,
                onRefreshMaster = onRefreshMaster,
                onSave = onSaveLaporanKegiatan,
                onSend = onSend
            )

            else -> DashboardScreen(
                modifier = Modifier.padding(paddingValues),
                snapshot = dashboardSnapshot,
                isRefreshing = isCoverageRefreshing,
                onRefreshCoverage = onRefreshCoverage
            )
        }
    }
}

@Composable
private fun PendataanListScreen(
    modifier: Modifier,
    idPetugas: String,
    kabupaten: String,
    rows: List<PendataanEntity>,
    onSend: () -> Unit,
    canAdd: Boolean,
    onDelete: (PendataanEntity) -> Unit,
    onEdit: (PendataanEntity) -> Unit,
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

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
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
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (kabupaten.isNotBlank()) {
                        Text(
                            text = "Kabupaten kerja: $kabupaten",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                OutlinedButton(
                    onClick = onLogout,
                    enabled = !isLoggingOut,
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(if (isLoggingOut) "Memeriksa..." else "Logout")
                }
            }
        }
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.secondary
                            )
                        )
                    )
                    .padding(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "revisit SE2026",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Simpan dulu di perangkat. Kirim batch saat jaringan tersedia.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.86f)
                    )
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color.White.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "OFFLINE-FIRST  •  ROOM AKTIF",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
        item {
            OutlinedActionButton(
                text = "+  Tambah Pendataan",
                onClick = onAdd,
                enabled = canAdd,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SummaryCard(
                    label = "Terkirim",
                    count = sent,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )
                SummaryCard(
                    label = "Menunggu",
                    count = waiting,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                SummaryCard(
                    label = "Gagal",
                    count = failed,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            Button(
                onClick = onSend,
                enabled = waiting > 0,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (waiting > 0) "Kirim $waiting entri" else "Tidak ada antrean")
            }
        }
        if (staleRows > 0) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.error.copy(alpha = 0.25f)
                    ),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = "Peringatan: $staleRows entri belum terkirim lebih dari 24 jam.",
                        modifier = Modifier.padding(14.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        if (rows.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Belum ada pendataan",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Tekan tombol tambah untuk memulai pendataan pertama Anda.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(rows, key = { it.idRecord }) { row ->
                PendataanRow(
                    row,
                    onDelete = { onDelete(row) },
                    onEdit = { onEdit(row) }
                )
            }
        }
    }
}

@Composable
private fun NavigationGlyph(
    glyph: String,
    selected: Boolean
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
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
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(vertical = 14.dp)
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
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, color.copy(alpha = 0.22f)),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.14f)
        )
    ) {
        Column(
            modifier = Modifier
                .heightIn(min = 88.dp)
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                count.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

@Composable
private fun PendataanRow(
    row: PendataanEntity,
    onDelete: () -> Unit,
    onEdit: () -> Unit
) {
    val (label, color, symbol) = when (row.statusKirim) {
        SyncStatus.DRAFT -> Triple("DRAFT", Color.Gray, "○")
        SyncStatus.SIAP_KIRIM -> Triple("SIAP KIRIM", Color(0xFF1565C0), "●")
        SyncStatus.MENGIRIM -> Triple("MENGIRIM", Color(0xFF1565C0), "↻")
        SyncStatus.TERKIRIM -> Triple("TERKIRIM", Color(0xFF1B5E20), "✓")
        SyncStatus.GAGAL -> Triple("GAGAL", Color(0xFFB71C1C), "!")
        else -> Triple(row.statusKirim, Color.Gray, "?")
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, color.copy(alpha = 0.18f)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.namaObjek.ifBlank { "Tanpa nama objek" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = listOf(row.namaSls, row.jenisObjek)
                            .filter { it.isNotBlank() }
                            .joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = color.copy(alpha = 0.13f)
                ) {
                    Text(
                        text = "$symbol  $label",
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        color = color,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            row.pesanError?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = onEdit,
                    enabled = row.statusKirim != SyncStatus.MENGIRIM,
                    contentPadding = PaddingValues(horizontal = 0.dp)
                ) {
                    Text(
                        if (row.statusKirim == SyncStatus.TERKIRIM) {
                            "Edit & Kirim Ulang"
                        } else {
                            "Edit"
                        }
                    )
                }
                if (row.statusKirim != SyncStatus.TERKIRIM) {
                    TextButton(
                        onClick = onDelete,
                        contentPadding = PaddingValues(horizontal = 0.dp)
                    ) {
                        Text("Hapus")
                    }
                }
            }
        }
    }
}
