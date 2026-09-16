@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.fajriantomanungki.revisitapp.feature.wilayah

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fajriantomanungki.revisitapp.data.local.entity.WilayahEntity

/**
 * Nilai pilihan wilayah yang dikirimkan ke form pendataan.
 *
 * Setiap level menggunakan WilayahEntity yang sama karena satu baris master
 * sudah memuat hierarki kecamatan, desa, dan SLS secara lengkap.
 */
data class WilayahSelection(
    val kecamatan: WilayahEntity? = null,
    val desa: WilayahEntity? = null,
    val sls: WilayahEntity? = null
) {
    val isComplete: Boolean
        get() = sls != null
}

private data class WilayahOption(
    val code: String,
    val name: String
)

/**
 * Selector reusable untuk F-02 dan F-04.1.
 *
 * Urutan filter:
 *   Kecamatan -> Desa -> SLS
 *
 * Dropdown kode mendukung pencarian berdasarkan kode maupun nama.
 * Nama wilayah ditampilkan pada field terpisah yang read-only.
 */
@Composable
fun CascadingWilayahSelector(
    wilayah: List<WilayahEntity>,
    modifier: Modifier = Modifier,
    initialSelection: WilayahSelection = WilayahSelection(),
    onSelectionChanged: (WilayahSelection) -> Unit
) {
    var selectedKodeKec by rememberSaveable {
        mutableStateOf(initialSelection.kecamatan?.kodeKec)
    }
    var selectedKodeDesa by rememberSaveable {
        mutableStateOf(initialSelection.desa?.kodeDesa)
    }
    var selectedKodeSls by rememberSaveable {
        mutableStateOf(initialSelection.sls?.kodeSls)
    }

    var queryKec by rememberSaveable { mutableStateOf("") }
    var queryDesa by rememberSaveable { mutableStateOf("") }
    var querySls by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(
        initialSelection.kecamatan?.kodeKec,
        initialSelection.desa?.kodeDesa,
        initialSelection.sls?.kodeSls
    ) {
        selectedKodeKec = initialSelection.kecamatan?.kodeKec
        selectedKodeDesa = initialSelection.desa?.kodeDesa
        selectedKodeSls = initialSelection.sls?.kodeSls
    }

    val selectedKecamatan = remember(
        wilayah,
        selectedKodeKec
    ) {
        wilayah.firstOrNull { it.kodeKec == selectedKodeKec }
    }

    val selectedDesa = remember(
        wilayah,
        selectedKodeKec,
        selectedKodeDesa
    ) {
        wilayah.firstOrNull {
            it.kodeKec == selectedKodeKec &&
                it.kodeDesa == selectedKodeDesa
        }
    }

    val selectedSls = remember(
        wilayah,
        selectedKodeKec,
        selectedKodeDesa,
        selectedKodeSls
    ) {
        wilayah.firstOrNull {
            it.kodeKec == selectedKodeKec &&
                it.kodeDesa == selectedKodeDesa &&
                it.kodeSls == selectedKodeSls
        }
    }

    /*
     * Jika master diperbarui dan kode lama sudah tidak ada, pilihan turunan
     * dibersihkan. Saat list masih kosong, pilihan awal tidak dibersihkan
     * karena Room mungkin masih dalam proses memuat cache.
     */
    LaunchedEffect(wilayah) {
        if (wilayah.isNotEmpty()) {
            if (selectedKodeKec != null && selectedKecamatan == null) {
                selectedKodeKec = null
                selectedKodeDesa = null
                selectedKodeSls = null
            } else if (selectedKodeDesa != null && selectedDesa == null) {
                selectedKodeDesa = null
                selectedKodeSls = null
            } else if (selectedKodeSls != null && selectedSls == null) {
                selectedKodeSls = null
            }
        }
    }

    val callback by rememberUpdatedState(onSelectionChanged)
    LaunchedEffect(
        wilayah,
        selectedKodeKec,
        selectedKodeDesa,
        selectedKodeSls
    ) {
        if (wilayah.isNotEmpty()) {
            callback(
                WilayahSelection(
                    kecamatan = selectedKecamatan,
                    desa = selectedDesa,
                    sls = selectedSls
                )
            )
        }
    }

    val kecamatanOptions = remember(wilayah, queryKec) {
        wilayah
            .distinctBy { it.kodeKec }
            .filter {
                it.kodeKec.contains(queryKec, ignoreCase = true) ||
                    it.namaKec.contains(queryKec, ignoreCase = true)
            }
            .sortedBy { it.namaKec.lowercase() }
            .map { WilayahOption(it.kodeKec, it.namaKec) }
    }

    val desaOptions = remember(
        wilayah,
        selectedKodeKec,
        queryDesa
    ) {
        wilayah
            .filter { it.kodeKec == selectedKodeKec }
            .distinctBy { it.kodeDesa }
            .filter {
                it.kodeDesa.contains(queryDesa, ignoreCase = true) ||
                    it.namaDesa.contains(queryDesa, ignoreCase = true)
            }
            .sortedBy { it.namaDesa.lowercase() }
            .map { WilayahOption(it.kodeDesa, it.namaDesa) }
    }

    val slsOptions = remember(
        wilayah,
        selectedKodeKec,
        selectedKodeDesa,
        querySls
    ) {
        wilayah
            .filter {
                it.kodeKec == selectedKodeKec &&
                    it.kodeDesa == selectedKodeDesa
            }
            .filter {
                it.kodeSls.contains(querySls, ignoreCase = true) ||
                    it.namaSls.contains(querySls, ignoreCase = true)
            }
            .sortedBy { it.namaSls.lowercase() }
            .map { WilayahOption(it.kodeSls, it.namaSls) }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        WilayahDropdown(
            label = "Kode Kecamatan",
            selectedCode = selectedKodeKec,
            query = queryKec,
            options = kecamatanOptions,
            enabled = kecamatanOptions.isNotEmpty() || selectedKecamatan != null,
            onQueryChanged = { queryKec = it },
            onSelected = { option ->
                selectedKodeKec = option.code
                selectedKodeDesa = null
                selectedKodeSls = null
                queryKec = option.code
                queryDesa = ""
                querySls = ""
            }
        )

        ReadOnlyRegionNameField(
            label = "Nama Kecamatan",
            value = selectedKecamatan?.namaKec.orEmpty(),
            enabled = selectedKecamatan != null
        )

        WilayahDropdown(
            label = "Kode Desa",
            selectedCode = selectedKodeDesa,
            query = queryDesa,
            options = desaOptions,
            enabled = selectedKecamatan != null,
            onQueryChanged = { queryDesa = it },
            onSelected = { option ->
                selectedKodeDesa = option.code
                selectedKodeSls = null
                queryDesa = option.code
                querySls = ""
            }
        )

        ReadOnlyRegionNameField(
            label = "Nama Desa",
            value = selectedDesa?.namaDesa.orEmpty(),
            enabled = selectedDesa != null
        )

        WilayahDropdown(
            label = "Kode SLS",
            selectedCode = selectedKodeSls,
            query = querySls,
            options = slsOptions,
            enabled = selectedDesa != null,
            onQueryChanged = { querySls = it },
            onSelected = { option ->
                selectedKodeSls = option.code
                querySls = option.code
            }
        )

        ReadOnlyRegionNameField(
            label = "Nama SLS",
            value = selectedSls?.namaSls.orEmpty(),
            enabled = selectedSls != null
        )
    }
}

@Composable
private fun WilayahDropdown(
    label: String,
    selectedCode: String?,
    query: String,
    options: List<WilayahOption>,
    enabled: Boolean,
    onQueryChanged: (String) -> Unit,
    onSelected: (WilayahOption) -> Unit
) {
    var expanded by rememberSaveable(label) {
        mutableStateOf(false)
    }

    LaunchedEffect(enabled) {
        if (!enabled) {
            expanded = false
            onQueryChanged("")
        }
    }

    val displayValue = if (expanded) query else selectedCode.orEmpty()

    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = {
            if (enabled) {
                expanded = !expanded
                onQueryChanged(
                    if (expanded) "" else selectedCode.orEmpty()
                )
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = displayValue,
            onValueChange = {
                if (enabled) {
                    expanded = true
                    onQueryChanged(it)
                }
            },
            enabled = enabled,
            singleLine = true,
            label = { Text(label) },
            placeholder = { Text("Ketik kode atau nama untuk mencari") },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(
                    expanded = expanded
                )
            },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )

        ExposedDropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = {
                expanded = false
                onQueryChanged(selectedCode.orEmpty())
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            if (options.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("Tidak ada wilayah yang sesuai") },
                    onClick = {
                        expanded = false
                        onQueryChanged(selectedCode.orEmpty())
                    },
                    enabled = false
                )
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Text(option.code + " — " + option.name)
                            },
                            onClick = {
                                onSelected(option)
                                expanded = false
                                onQueryChanged(option.code)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadOnlyRegionNameField(
    label: String,
    value: String,
    enabled: Boolean
) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        enabled = enabled,
        singleLine = true,
        label = { Text(label) },
        supportingText = {
            Text("Terisi otomatis dari master wilayah")
        },
        modifier = Modifier.fillMaxWidth()
    )
}
