package com.fajriantomanungki.revisitapp.feature.dashboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.annotation.ColorInt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fajriantomanungki.revisitapp.data.dashboard.DashboardCoverageSnapshot
import com.fajriantomanungki.revisitapp.data.local.dao.LocalCoverageCount
import com.fajriantomanungki.revisitapp.data.local.entity.CakupanCacheEntity
import com.fajriantomanungki.revisitapp.data.local.entity.WilayahEntity
import com.fajriantomanungki.revisitapp.domain.coverage.CoverageClass
import com.fajriantomanungki.revisitapp.domain.coverage.SlsCoverage
import com.fajriantomanungki.revisitapp.domain.coverage.WilayahCoverageSource
import com.fajriantomanungki.revisitapp.domain.coverage.ServerCoverageSource
import com.fajriantomanungki.revisitapp.domain.coverage.LocalCoverageSource
import com.fajriantomanungki.revisitapp.domain.coverage.mergeCoverage
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

/**
 * F-07 dengan input mentah dari Room. Pemanggil dapat mengumpulkan tiga flow
 * Room lalu menyerahkannya ke layar ini, atau menggunakan overload snapshot di
 * bawah melalui DashboardCoverageRepository.
 */
@Composable
fun DashboardScreen(
    wilayah: List<WilayahEntity>,
    cakupanCache: List<CakupanCacheEntity>,
    localCoverage: List<LocalCoverageCount>,
    modifier: Modifier = Modifier,
    assignedSlsCodes: Set<String>? = null,
    lastServerSyncMillis: Long? = cakupanCache
        .map { it.waktuSinkron }
        .filter { it > 0L }
        .maxOrNull(),
    onRefreshCoverage: (() -> Unit)? = null,
    onNavigate: (SlsCoverage) -> Unit = {},
    onOpenSlsData: (String) -> Unit = {},
    onSlsSelected: (SlsCoverage) -> Unit = {}
) {
    val coverage = remember(wilayah, cakupanCache, localCoverage) {
        mergeCoverage(
            wilayah = wilayah.map {
                WilayahCoverageSource(
                    kodeKec = it.kodeKec,
                    namaKec = it.namaKec,
                    kodeDesa = it.kodeDesa,
                    namaDesa = it.namaDesa,
                    kodeSls = it.kodeSls,
                    namaSls = it.namaSls,
                    targetResponden = it.targetResponden.toLong(),
                    latCentroid = it.latCentroid,
                    lonCentroid = it.lonCentroid
                )
            },
            serverCache = cakupanCache.map {
                ServerCoverageSource(
                    kodeSls = it.kodeSls,
                    jumlahTerdata = it.jumlahTerdata.toLong(),
                    target = it.target.toLong()
                )
            },
            localPending = localCoverage.map {
                LocalCoverageSource(
                    kodeSls = it.kodeSls,
                    jumlahTerdata = it.jumlahTerdata
                )
            }
        )
    }

    DashboardContent(
        coverage = coverage,
        lastServerSyncMillis = lastServerSyncMillis,
        modifier = modifier,
        assignedSlsCodes = assignedSlsCodes,
        onRefreshCoverage = onRefreshCoverage,
        onNavigate = onNavigate,
        onOpenSlsData = onOpenSlsData,
        onSlsSelected = onSlsSelected
    )
}

/**
 * Overload untuk pemakaian langsung bersama DashboardCoverageRepository.
 */
@Composable
fun DashboardScreen(
    snapshot: DashboardCoverageSnapshot,
    modifier: Modifier = Modifier,
    assignedSlsCodes: Set<String>? = null,
    onRefreshCoverage: (() -> Unit)? = null,
    onNavigate: (SlsCoverage) -> Unit = {},
    onOpenSlsData: (String) -> Unit = {},
    onSlsSelected: (SlsCoverage) -> Unit = {}
) {
    DashboardContent(
        coverage = snapshot.coverage,
        lastServerSyncMillis = snapshot.lastServerSyncMillis,
        modifier = modifier,
        assignedSlsCodes = assignedSlsCodes,
        onRefreshCoverage = onRefreshCoverage,
        onNavigate = onNavigate,
        onOpenSlsData = onOpenSlsData,
        onSlsSelected = onSlsSelected
    )
}

@Composable
private fun DashboardContent(
    coverage: List<SlsCoverage>,
    lastServerSyncMillis: Long?,
    modifier: Modifier,
    assignedSlsCodes: Set<String>?,
    onRefreshCoverage: (() -> Unit)?,
    onNavigate: (SlsCoverage) -> Unit,
    onOpenSlsData: (String) -> Unit,
    onSlsSelected: (SlsCoverage) -> Unit
) {
    var selectedKecCode by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedDesaCode by rememberSaveable { mutableStateOf<String?>(null) }
    var showOnlyAssigned by rememberSaveable { mutableStateOf(false) }
    var selectedSlsCode by rememberSaveable { mutableStateOf<String?>(null) }
    val cameraPositionState = rememberCameraPositionState()
    val coroutineScope = rememberCoroutineScope()

    val assignmentCoverage = remember(coverage, assignedSlsCodes, showOnlyAssigned) {
        if (showOnlyAssigned && assignedSlsCodes != null) {
            coverage.filter { it.kodeSls in assignedSlsCodes }
        } else {
            coverage
        }
    }

    val kecOptions = remember(assignmentCoverage) {
        assignmentCoverage
            .distinctBy { it.kodeKec }
            .sortedBy { it.namaKec }
            .map { DashboardFilterOption(it.kodeKec, it.namaKec) }
    }
    val desaOptions = remember(assignmentCoverage, selectedKecCode) {
        assignmentCoverage
            .filter { selectedKecCode == null || it.kodeKec == selectedKecCode }
            .distinctBy { it.kodeDesa }
            .sortedBy { it.namaDesa }
            .map { DashboardFilterOption(it.kodeDesa, it.namaDesa) }
    }
    val filteredCoverage = remember(
        assignmentCoverage,
        selectedKecCode,
        selectedDesaCode
    ) {
        assignmentCoverage.filter {
            (selectedKecCode == null || it.kodeKec == selectedKecCode) &&
                (selectedDesaCode == null || it.kodeDesa == selectedDesaCode)
        }
    }
    val lowestTen = remember(filteredCoverage) {
        filteredCoverage
            .sortedWith(
                compareBy<SlsCoverage> { it.coverageRatio }
                    .thenBy { it.namaKec }
                    .thenBy { it.namaDesa }
                    .thenBy { it.namaSls }
            )
            .take(10)
    }
    val selectedSls = filteredCoverage.firstOrNull { it.kodeSls == selectedSlsCode }
    val localPendingTotal = filteredCoverage.sumOf { it.jumlahLokalBelumTerkirim }
    val filterKey = (selectedKecCode ?: "") + "|" +
        (selectedDesaCode ?: "") + "|" +
        showOnlyAssigned

    LaunchedEffect(selectedKecCode) {
        if (selectedDesaCode != null &&
            desaOptions.none { it.code == selectedDesaCode }
        ) {
            selectedDesaCode = null
        }
    }

    LaunchedEffect(filteredCoverage.map { it.kodeSls }) {
        if (selectedSlsCode != null &&
            filteredCoverage.none { it.kodeSls == selectedSlsCode }
        ) {
            selectedSlsCode = null
        }
    }

    LaunchedEffect(filterKey) {
        val firstMarker = filteredCoverage.firstOrNull { it.hasValidCentroid }
        if (firstMarker != null) {
            cameraPositionState.animate(
                update = CameraUpdateFactory.newLatLngZoom(
                    LatLng(
                        firstMarker.latCentroid ?: 0.0,
                        firstMarker.lonCentroid ?: 0.0
                    ),
                    12f
                )
            )
        }
    }

    LaunchedEffect(selectedSlsCode) {
        val selected = filteredCoverage.firstOrNull { it.kodeSls == selectedSlsCode }
        if (selected?.hasValidCentroid == true) {
            cameraPositionState.animate(
                update = CameraUpdateFactory.newLatLngZoom(
                    LatLng(
                        selected.latCentroid ?: 0.0,
                        selected.lonCentroid ?: 0.0
                    ),
                    15f
                )
            )
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Dashboard Cakupan SLS") },
                actions = {
                    if (onRefreshCoverage != null) {
                        OutlinedButton(
                            onClick = onRefreshCoverage,
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("Perbarui")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DashboardSummary(
                coverage = filteredCoverage,
                localPendingTotal = localPendingTotal,
                lastServerSyncMillis = lastServerSyncMillis
            )

            DashboardFilters(
                kecOptions = kecOptions,
                desaOptions = desaOptions,
                selectedKecCode = selectedKecCode,
                selectedDesaCode = selectedDesaCode,
                onKecSelected = {
                    selectedKecCode = it
                    selectedDesaCode = null
                    selectedSlsCode = null
                },
                onDesaSelected = {
                    selectedDesaCode = it
                    selectedSlsCode = null
                },
                assignedSlsCodes = assignedSlsCodes,
                showOnlyAssigned = showOnlyAssigned,
                onOnlyAssignedChanged = {
                    showOnlyAssigned = it
                    selectedSlsCode = null
                }
            )

            CoverageLegend()

            DashboardMap(
                coverage = filteredCoverage,
                selectedSlsCode = selectedSlsCode,
                cameraPositionState = cameraPositionState,
                onSlsSelected = {
                    selectedSlsCode = it.kodeSls
                    onSlsSelected(it)
                }
            )

            if (selectedSls != null) {
                SlsDetailCard(
                    item = selectedSls,
                    onNavigate = { onNavigate(selectedSls) },
                    onOpenSlsData = { onOpenSlsData(selectedSls.kodeSls) }
                )
            }

            LowestCoverageList(
                items = lowestTen,
                onItemClicked = { item ->
                    selectedSlsCode = item.kodeSls
                    onSlsSelected(item)
                    if (item.hasValidCentroid) {
                        coroutineScope.launch {
                            cameraPositionState.animate(
                                update = CameraUpdateFactory.newLatLngZoom(
                                    LatLng(
                                        item.latCentroid ?: 0.0,
                                        item.lonCentroid ?: 0.0
                                    ),
                                    15f
                                )
                            )
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun DashboardSummary(
    coverage: List<SlsCoverage>,
    localPendingTotal: Long,
    lastServerSyncMillis: Long?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Ringkasan cakupan",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = coverage.size.toString() + " SLS ditampilkan • " +
                    coverage.count { it.targetValid && it.coverageRatio >= 1.0 }.toString() +
                    " sudah mencapai target"
            )
            Text(
                text = "Entri lokal belum terkirim: " +
                    formatInteger(localPendingTotal)
            )
            Text(
                text = lastServerSyncMillis
                    ?.takeIf { it > 0L }
                    ?.let { "Data per: " + formatDateTime(it) }
                    ?: "Data server belum pernah disinkronkan"
            )
        }
    }
}

@Composable
private fun DashboardFilters(
    kecOptions: List<DashboardFilterOption>,
    desaOptions: List<DashboardFilterOption>,
    selectedKecCode: String?,
    selectedDesaCode: String?,
    onKecSelected: (String?) -> Unit,
    onDesaSelected: (String?) -> Unit,
    assignedSlsCodes: Set<String>?,
    showOnlyAssigned: Boolean,
    onOnlyAssignedChanged: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Filter wilayah",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        DashboardDropdown(
            label = "Kecamatan",
            selectedLabel = kecOptions
                .firstOrNull { it.code == selectedKecCode }
                ?.label,
            options = kecOptions,
            onSelected = onKecSelected
        )
        DashboardDropdown(
            label = "Desa",
            selectedLabel = desaOptions
                .firstOrNull { it.code == selectedDesaCode }
                ?.label,
            options = desaOptions,
            onSelected = onDesaSelected
        )
        if (assignedSlsCodes != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Hanya wilayah penugasan saya",
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Batasi marker dan daftar ke SLS yang ditugaskan.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = showOnlyAssigned,
                    onCheckedChange = onOnlyAssignedChanged
                )
            }
        }
    }
}

private data class DashboardFilterOption(
    val code: String,
    val label: String
)

@Composable
private fun DashboardDropdown(
    label: String,
    selectedLabel: String?,
    options: List<DashboardFilterOption>,
    onSelected: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge
        )
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(selectedLabel ?: "Semua")
                    Text("▾")
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Semua") },
                    onClick = {
                        expanded = false
                        onSelected(null)
                    }
                )
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.code + " — " + option.label) },
                        onClick = {
                            expanded = false
                            onSelected(option.code)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CoverageLegend() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Legenda cakupan",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CoverageClass.values().forEach { coverageClass ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(Color(coverageClass.colorArgb))
                    )
                    Text(
                        text = coverageClass.label,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardMap(
    coverage: List<SlsCoverage>,
    selectedSlsCode: String?,
    cameraPositionState: com.google.maps.android.compose.CameraPositionState,
    onSlsSelected: (SlsCoverage) -> Unit
) {
    val markers = coverage.filter { it.hasValidCentroid }

    if (markers.isEmpty()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Belum ada centroid SLS yang valid untuk ditampilkan.",
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
        return
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(380.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(mapType = MapType.NORMAL),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = true,
                myLocationButtonEnabled = false
            )
        ) {
            markers.forEach { item ->
                key(item.kodeSls) {
                    val markerState = rememberUpdatedMarkerState(
                        position = LatLng(
                            item.latCentroid ?: 0.0,
                            item.lonCentroid ?: 0.0
                        )
                    )
                    val markerIcon = remember(item.coverageClass.colorArgb) {
                        createCoverageMarkerIcon(item.coverageClass.colorArgb)
                    }
                    Marker(
                        state = markerState,
                        icon = markerIcon,
                        anchor = Offset(0.5f, 0.5f),
                        title = item.namaSls,
                        snippet = item.namaDesa + " • " +
                            formatCoverage(item),
                        zIndex = if (item.kodeSls == selectedSlsCode) 1f else 0f,
                        onClick = {
                            onSlsSelected(item)
                            false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SlsDetailCard(
    item: SlsCoverage,
    onNavigate: () -> Unit,
    onOpenSlsData: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(item.coverageClass.colorArgb).copy(alpha = 0.14f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = item.namaSls,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(item.kodeSls)
            Text(item.namaDesa + " • " + item.namaKec)
            Text(
                text = "Target: " + formatInteger(item.targetResponden) +
                    " • Terdata: " + formatInteger(item.jumlahTerdata) +
                    " (" + formatCoverage(item) + ")"
            )
            if (item.jumlahLokalBelumTerkirim > 0L) {
                Text(
                    text = "Termasuk " +
                        formatInteger(item.jumlahLokalBelumTerkirim) +
                        " entri lokal belum terkirim.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onNavigate,
                    enabled = item.hasValidCentroid,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Navigasi")
                }
                OutlinedButton(
                    onClick = onOpenSlsData,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Data di SLS ini")
                }
            }
        }
    }
}

@Composable
private fun LowestCoverageList(
    items: List<SlsCoverage>,
    onItemClicked: (SlsCoverage) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "10 SLS dengan cakupan terendah",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        if (items.isEmpty()) {
            Text("Belum ada data SLS untuk ditampilkan.")
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    items.forEachIndexed { index, item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onItemClicked(item) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(Color(item.coverageClass.colorArgb))
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.namaSls,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = item.namaDesa + " • " + item.namaKec,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Text(
                                text = formatCoverage(item),
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (index < items.lastIndex) {
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

private fun formatCoverage(item: SlsCoverage): String {
    if (!item.targetValid) {
        return "—"
    }
    return String.format(
        Locale.US,
        "%.0f%%",
        item.coveragePercent
    )
}

private fun formatInteger(value: Long): String =
    NumberFormat.getIntegerInstance(Locale("id", "ID")).format(value)

private fun formatDateTime(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(
        DateFormat.MEDIUM,
        DateFormat.SHORT,
        Locale("id", "ID")
    ).format(Date(epochMillis))

private fun createCoverageMarkerIcon(
    @ColorInt colorArgb: Int
): BitmapDescriptor {
    val sizePx = 42
    val center = sizePx / 2f
    val radius = 13f
    val bitmap = Bitmap.createBitmap(
        sizePx,
        sizePx,
        Bitmap.Config.ARGB_8888
    )
    val canvas = Canvas(bitmap)
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(90, 0, 0, 0)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center + 1.5f, center + 2f, radius + 1f, shadowPaint)

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorArgb
        style = Paint.Style.FILL
        typeface = Typeface.DEFAULT_BOLD
    }
    canvas.drawCircle(center, center, radius, fillPaint)

    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    canvas.drawCircle(center, center, radius, borderPaint)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}
