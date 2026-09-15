package com.fajriantomanungki.revisitapp.feature.pendataan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fajriantomanungki.revisitapp.data.local.entity.WilayahEntity
import com.fajriantomanungki.revisitapp.feature.wilayah.CascadingWilayahSelector
import com.fajriantomanungki.revisitapp.feature.wilayah.WilayahSelection

/**
 * F-04.1 Bagian Wilayah pada form pendataan satu halaman.
 */
@Composable
fun FormWilayahSection(
    wilayah: List<WilayahEntity>,
    modifier: Modifier = Modifier,
    initialSelection: WilayahSelection = WilayahSelection(),
    onSelectionChanged: (WilayahSelection) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Bagian Wilayah",
            style = MaterialTheme.typography.titleMedium
        )

        if (wilayah.isEmpty()) {
            Text(
                text = "Master wilayah belum diunduh. Unduh master terlebih dahulu.",
                color = MaterialTheme.colorScheme.error
            )
        } else {
            CascadingWilayahSelector(
                wilayah = wilayah,
                initialSelection = initialSelection,
                onSelectionChanged = onSelectionChanged
            )
        }
    }
}
