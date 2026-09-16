@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.fajriantomanungki.revisitapp.feature.wilayah

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fajriantomanungki.revisitapp.data.local.entity.WilayahEntity

/**
 * Layar F-02 untuk melihat master wilayah yang tersedia di Room.
 *
 * Pengunduhan master dilakukan oleh repository/sync layer. Layar ini menerima
 * callback onRefreshMaster agar mekanisme download dapat ditambahkan tanpa
 * mengikat UI ke implementasi API tertentu.
 */
@Composable
fun MasterWilayahScreen(
    wilayah: List<WilayahEntity>,
    modifier: Modifier = Modifier,
    fixedKodeKabupaten: String? = null,
    versiMaster: Int? = wilayah.maxOfOrNull { it.versiMaster },
    isRefreshing: Boolean = false,
    initialSelection: WilayahSelection = WilayahSelection(),
    onRefreshMaster: (() -> Unit)? = null,
    onSelectionChanged: (WilayahSelection) -> Unit = {}
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Master Wilayah")
                        Text(
                            text = "Kamus wilayah untuk kerja offline",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        MasterWilayahContent(
            paddingValues = paddingValues,
            wilayah = wilayah,
            fixedKodeKabupaten = fixedKodeKabupaten,
            versiMaster = versiMaster,
            isRefreshing = isRefreshing,
            initialSelection = initialSelection,
            onRefreshMaster = onRefreshMaster,
            onSelectionChanged = onSelectionChanged
        )
    }
}

@Composable
private fun MasterWilayahContent(
    paddingValues: PaddingValues,
    wilayah: List<WilayahEntity>,
    fixedKodeKabupaten: String?,
    versiMaster: Int?,
    isRefreshing: Boolean,
    initialSelection: WilayahSelection,
    onRefreshMaster: (() -> Unit)?,
    onSelectionChanged: (WilayahSelection) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(paddingValues)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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
                    text = "Siap bekerja di lapangan",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Pilih hierarki wilayah dan gunakan master ini meskipun koneksi sedang tidak tersedia.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.86f)
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.White.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "MASTER OFFLINE",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (wilayah.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = "Master wilayah belum tersedia. Perbarui data wilayah sebelum membuka fitur Pendataan.",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant
                ),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Pilih wilayah",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Nama akan terisi otomatis dari master.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = wilayah.size.toString() + " SLS",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        text = "Cache versi " + (versiMaster?.toString() ?: "—"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    CascadingWilayahSelector(
                        wilayah = wilayah,
                        fixedKodeKabupaten = fixedKodeKabupaten,
                        initialSelection = initialSelection,
                        onSelectionChanged = onSelectionChanged,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        if (onRefreshMaster != null) {
            Button(
                onClick = onRefreshMaster,
                enabled = !isRefreshing,
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Text("Memperbarui...")
                } else {
                    Text("Perbarui Data Wilayah")
                }
            }
        }
    }
}
