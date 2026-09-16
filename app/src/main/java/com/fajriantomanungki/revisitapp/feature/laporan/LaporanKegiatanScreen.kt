@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.fajriantomanungki.revisitapp.feature.laporan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.fajriantomanungki.revisitapp.data.local.entity.LaporanKegiatanEntity
import com.fajriantomanungki.revisitapp.data.local.entity.PendataanEntity
import com.fajriantomanungki.revisitapp.data.local.model.LaporanKegiatanStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Layar laporan kegiatan harian; seluruh input disimpan ke Room. */
@Composable
fun LaporanKegiatanScreen(
    modifier: Modifier,
    idPetugas: String,
    kabupaten: String,
    pendataan: List<PendataanEntity>,
    laporan: List<LaporanKegiatanEntity>,
    isMasterRefreshing: Boolean,
    onRefreshMaster: () -> Unit,
    onSave: suspend (tanggal: String, rangkuman: String, siapKirim: Boolean) -> Result<Unit>,
    onSend: () -> Unit
) {
    val today = remember { formatDay(System.currentTimeMillis()) }
    val activityDates = remember(pendataan, laporan) {
        (
            pendataan.map { formatDay(it.waktuPendataan ?: it.waktuDibuat) } +
                laporan.map { it.tanggal }
            ).distinct().sortedDescending()
    }
    val dates = remember(activityDates, today) {
        (activityDates + today).distinct().sortedDescending()
    }
    var selectedDate by rememberSaveable { mutableStateOf(today) }
    var summary by rememberSaveable(selectedDate) { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var infoMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var dateMenuExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(dates) {
        if (selectedDate !in dates) selectedDate = dates.firstOrNull() ?: today
    }
    LaunchedEffect(selectedDate, laporan) {
        summary = laporan.firstOrNull { it.tanggal == selectedDate }
            ?.rangkuman
            .orEmpty()
        errorMessage = null
    }

    val recordsForDay = remember(pendataan, selectedDate) {
        pendataan.filter {
            formatDay(it.waktuPendataan ?: it.waktuDibuat) == selectedDate
        }
    }
    val selectedReport = laporan.firstOrNull { it.tanggal == selectedDate }

    fun saveReport(siapKirim: Boolean) {
        if (summary.trim().isBlank()) {
            errorMessage = "Rangkuman kegiatan wajib diisi."
            return
        }
        isSaving = true
        errorMessage = null
        infoMessage = null
        scope.launch {
            val result = try {
                onSave(selectedDate, summary, siapKirim)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Result.failure(error)
            }
            isSaving = false
            result.fold(
                onSuccess = {
                    infoMessage = if (siapKirim) {
                        "Laporan harian tersimpan dan masuk antrean pengiriman."
                    } else {
                        "Draf laporan harian tersimpan di perangkat."
                    }
                    if (siapKirim) onSend()
                },
                onFailure = { errorMessage = error.message ?: "Laporan gagal disimpan." }
            )
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Laporan Kegiatan",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "$idPetugas · ${kabupaten.ifBlank { "Kabupaten belum diatur" }}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = onRefreshMaster,
                    enabled = !isMasterRefreshing,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(if (isMasterRefreshing) "Memuat..." else "Perbarui wilayah")
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Laporan harian",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Tulis rangkuman kegiatan untuk tanggal pendataan. Laporan dapat disimpan sebagai draf atau dikirim bersama antrean sinkronisasi.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column {
                        OutlinedButton(
                            onClick = { dateMenuExpanded = true },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("Tanggal laporan", style = MaterialTheme.typography.labelSmall)
                                    Text(selectedDate)
                                }
                                Text("▾")
                            }
                        }
                        DropdownMenu(
                            expanded = dateMenuExpanded,
                            onDismissRequest = { dateMenuExpanded = false },
                            offset = DpOffset(0.dp, 4.dp),
                            modifier = Modifier.heightIn(max = 280.dp)
                        ) {
                            dates.forEach { date ->
                                DropdownMenuItem(
                                    text = { Text(date) },
                                    onClick = {
                                        selectedDate = date
                                        dateMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = summary,
                        onValueChange = { summary = it.take(MAX_SUMMARY_LENGTH) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 6,
                        maxLines = 10,
                        label = { Text("Rangkuman kegiatan") },
                        supportingText = { Text("${summary.length}/$MAX_SUMMARY_LENGTH") },
                        shape = MaterialTheme.shapes.medium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { saveReport(siapKirim = false) },
                            enabled = !isSaving,
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text("Simpan Draf")
                        }
                        Button(
                            onClick = { saveReport(siapKirim = true) },
                            enabled = !isSaving,
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text(if (isSaving) "Menyimpan..." else "Simpan & Kirim")
                        }
                    }
                    selectedReport?.let { report ->
                        StatusPill(report.statusKirim)
                    }
                    errorMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    infoMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        item {
            Text(
                "Hasil pendataan pada $selectedDate (${recordsForDay.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        if (recordsForDay.isEmpty()) {
            item {
                Text(
                    "Belum ada hasil pendataan pada tanggal ini.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(recordsForDay, key = { it.idRecord }) { row ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(row.namaObjek.ifBlank { "Tanpa nama objek" }, fontWeight = FontWeight.Bold)
                        Text(
                            listOf(row.namaSls, row.namaKec, row.statusKirim)
                                .filter { it.isNotBlank() }
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        item {
            Text(
                "Status laporan per tanggal",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        if (activityDates.isEmpty()) {
            item {
                Text(
                    "Belum ada tanggal pendataan yang tersimpan.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(
                activityDates,
                key = { date -> "tanggal-pendataan-$date" }
            ) { date ->
                val recordCount = pendataan.count {
                    formatDay(it.waktuPendataan ?: it.waktuDibuat) == date
                }
                val report = laporan.firstOrNull { it.tanggal == date }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(date, fontWeight = FontWeight.SemiBold)
                            Text(
                                "$recordCount hasil pendataan",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        StatusPill(report?.statusKirim.orEmpty())
                    }
                }
            }
        }
        item {
            Text(
                "Riwayat laporan harian",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        if (laporan.isEmpty()) {
            item {
                Text(
                    "Belum ada laporan harian tersimpan.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(laporan, key = { it.idLaporan }) { report ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(report.tanggal, fontWeight = FontWeight.SemiBold)
                        Text(
                            report.rangkuman,
                            maxLines = 2,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    StatusPill(report.statusKirim)
                }
            }
        }
    }
}

@Composable
private fun StatusPill(status: String) {
    val (label, color) = when (status) {
        LaporanKegiatanStatus.TERKIRIM -> "TERKIRIM" to Color(0xFF1B5E20)
        LaporanKegiatanStatus.SIAP_KIRIM -> "SIAP KIRIM" to Color(0xFF1565C0)
        LaporanKegiatanStatus.MENGIRIM -> "MENGIRIM" to Color(0xFF1565C0)
        LaporanKegiatanStatus.GAGAL -> "GAGAL" to Color(0xFFB71C1C)
        LaporanKegiatanStatus.DRAFT -> "DRAFT" to Color.Gray
        else -> "BELUM DIBUAT" to Color.Gray
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.13f)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun formatDay(epochMillis: Long): String = SimpleDateFormat(
    "yyyy-MM-dd",
    Locale.US
).apply {
    timeZone = TimeZone.getTimeZone("Asia/Makassar")
}.format(Date(epochMillis))

private const val MAX_SUMMARY_LENGTH = 5_000
