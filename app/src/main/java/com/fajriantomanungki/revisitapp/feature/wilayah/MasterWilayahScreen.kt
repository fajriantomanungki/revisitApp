package com.fajriantomanungki.revisitapp.feature.wilayah

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
                title = { Text("Master Wilayah") }
            )
        }
    ) { paddingValues ->
        MasterWilayahContent(
            paddingValues = paddingValues,
            wilayah = wilayah,
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
        Text(
            text = "Pilih wilayah untuk digunakan secara offline.",
            style = MaterialTheme.typography.bodyLarge
        )

        if (wilayah.isEmpty()) {
            Text(
                text = "Master wilayah belum tersedia. Perbarui data wilayah sebelum membuka fitur Pendataan.",
                color = MaterialTheme.colorScheme.error
            )
        } else {
            Text(
                text = "Cache tersedia: " + wilayah.size + " SLS" +
                    (versiMaster?.let { " • Versi " + it } ?: ""),
                style = MaterialTheme.typography.bodyMedium
            )

            CascadingWilayahSelector(
                wilayah = wilayah,
                initialSelection = initialSelection,
                onSelectionChanged = onSelectionChanged,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (onRefreshMaster != null) {
            Button(
                onClick = onRefreshMaster,
                enabled = !isRefreshing,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
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
