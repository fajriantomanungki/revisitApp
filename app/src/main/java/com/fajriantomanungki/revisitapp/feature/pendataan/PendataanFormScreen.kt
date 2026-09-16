@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.fajriantomanungki.revisitapp.feature.pendataan

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fajriantomanungki.revisitapp.data.local.entity.WilayahEntity
import com.fajriantomanungki.revisitapp.data.local.model.JenisObjek
import com.fajriantomanungki.revisitapp.data.local.model.StatusPendataan
import com.fajriantomanungki.revisitapp.data.pendataan.PendataanFormSubmission
import com.fajriantomanungki.revisitapp.domain.location.CapturedLocation
import com.fajriantomanungki.revisitapp.domain.location.LocationCaptureResult
import com.fajriantomanungki.revisitapp.domain.location.LocationFailureReason
import com.fajriantomanungki.revisitapp.domain.location.LocationHelper
import com.fajriantomanungki.revisitapp.domain.media.WatermarkData
import com.fajriantomanungki.revisitapp.domain.media.WatermarkedPhoto
import com.fajriantomanungki.revisitapp.domain.media.WatermarkEngine
import com.fajriantomanungki.revisitapp.domain.safety.LocationAuditResult
import com.fajriantomanungki.revisitapp.domain.safety.LocationIntegrityChecker
import com.fajriantomanungki.revisitapp.domain.safety.SlsCentroid
import com.fajriantomanungki.revisitapp.feature.wilayah.WilayahSelection
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Form satu halaman F-04. Semua jalur simpan berakhir di Room, bukan HTTP. */
@Composable
fun PendataanFormScreen(
    idPetugas: String,
    wilayah: List<WilayahEntity>,
    locationHelper: LocationHelper,
    watermarkEngine: WatermarkEngine,
    onSave: suspend (PendataanFormSubmission) -> Result<Unit>,
    onSaved: () -> Unit,
    onBack: () -> Unit
) {
    var selection by remember { mutableStateOf(WilayahSelection()) }
    var capturedLocation by remember { mutableStateOf<CapturedLocation?>(null) }
    var locationAudit by remember { mutableStateOf<LocationAuditResult?>(null) }
    var jenisObjek by rememberSaveable { mutableStateOf(JenisObjek.KELUARGA) }
    var namaObjek by rememberSaveable { mutableStateOf("") }
    var alamat by rememberSaveable { mutableStateOf("") }
    var statusPendataan by rememberSaveable {
        mutableStateOf(StatusPendataan.LENGKAP)
    }
    var catatan by rememberSaveable { mutableStateOf("") }
    var photos by remember { mutableStateOf<List<File>>(emptyList()) }
    var showCamera by rememberSaveable { mutableStateOf(false) }
    var showLocationRationale by rememberSaveable { mutableStateOf(false) }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    var locationDisabled by rememberSaveable { mutableStateOf(false) }
    var isProcessingPhoto by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val locationChecker = remember { LocationIntegrityChecker() }
    val context = LocalContext.current

    val isCompleteEnough = selection.sls != null &&
        capturedLocation != null &&
        jenisObjek.isNotBlank() &&
        namaObjek.isNotBlank() &&
        (statusPendataan != StatusPendataan.TIDAK_LENGKAP ||
            catatan.isNotBlank()) &&
        photos.isNotEmpty()

    fun makeSubmission(asDraft: Boolean): PendataanFormSubmission {
        val location = capturedLocation
        return PendataanFormSubmission(
            idPetugas = idPetugas,
            wilayah = selection.sls,
            jenisObjek = jenisObjek,
            namaObjek = namaObjek.trim(),
            alamat = alamat.trim(),
            statusPendataan = statusPendataan,
            catatan = catatan.trim(),
            latitude = location?.latitude,
            longitude = location?.longitude,
            accuracyM = location?.accuracyM,
            isMock = locationAudit?.isMock == true,
            waktuPendataan = location?.capturedAtEpochMillis,
            photoFiles = photos,
            simpanSebagaiDraf = asDraft,
            versiApp = "0.1.0"
        )
    }

    fun save(asDraft: Boolean) {
        if (!asDraft && !isCompleteEnough) {
            errorMessage = validationMessage(
                selection = selection,
                location = capturedLocation,
                namaObjek = namaObjek,
                statusPendataan = statusPendataan,
                catatan = catatan,
                photoCount = photos.size
            )
            return
        }

        isSaving = true
        errorMessage = null
        coroutineScope.launch {
            val result = try {
                onSave(makeSubmission(asDraft))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Result.failure(error)
            }

            isSaving = false
            result.fold(
                onSuccess = { onSaved() },
                onFailure = { error ->
                    errorMessage = error.message
                        ?: "Gagal menyimpan pendataan ke perangkat."
                }
            )
        }
    }

    suspend fun captureLocation() {
        errorMessage = null
        locationDisabled = false
        when (val result = locationHelper.captureCurrentLocation()) {
            is LocationCaptureResult.Success -> {
                val centroid = selection.sls?.let {
                    SlsCentroid(
                        latitude = it.latCentroid ?: Double.NaN,
                        longitude = it.lonCentroid ?: Double.NaN
                    )
                }
                capturedLocation = result.location
                locationAudit = locationChecker.inspect(
                    location = result.location,
                    centroid = centroid
                )
            }

            is LocationCaptureResult.Failure -> {
                locationDisabled = result.reason == LocationFailureReason.LOCATION_DISABLED
                errorMessage = when (result.reason) {
                    LocationFailureReason.PERMISSION_DENIED ->
                        "Izin lokasi belum diberikan."

                    LocationFailureReason.LOCATION_DISABLED ->
                        "Lokasi perangkat sedang mati. Aktifkan GPS lalu ambil ulang."

                    LocationFailureReason.UNAVAILABLE ->
                        "Titik lokasi belum tersedia. Coba ambil ulang di area terbuka."

                    LocationFailureReason.ERROR ->
                        result.cause?.message
                            ?: "Gagal mengambil titik lokasi."
                }
            }
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            coroutineScope.launch { captureLocation() }
        } else {
            errorMessage = "Izin lokasi diperlukan untuk merekam koordinat pendataan."
        }
    }

    fun requestLocation() {
        if (locationHelper.hasFineLocationPermission()) {
            coroutineScope.launch { captureLocation() }
        } else {
            showLocationRationale = true
        }
    }

    fun processCapturedPhoto(rawFile: File) {
        showCamera = false
        val location = capturedLocation
        val selectedSls = selection.sls
        if (location == null || selectedSls == null) {
            rawFile.delete()
            errorMessage = "Pilih SLS dan ambil lokasi sebelum mengambil gambar."
            return
        }
        if (photos.size >= MAX_PHOTOS) {
            rawFile.delete()
            errorMessage = "Maksimal tiga foto per pendataan."
            return
        }

        isProcessingPhoto = true
        coroutineScope.launch {
            val source = BitmapFactory.decodeFile(rawFile.absolutePath)
            val result: Result<WatermarkedPhoto> = try {
                val bitmap = source
                    ?: throw IllegalStateException("File foto tidak dapat dibaca")
                watermarkEngine.renderAndSave(
                    source = bitmap,
                    data = WatermarkData(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracyM = location.accuracyM,
                        kodeSls = selectedSls.kodeSls,
                        namaSls = selectedSls.namaSls,
                        namaDesa = selectedSls.namaDesa,
                        namaKec = selectedSls.namaKec,
                        timestampEpochMillis = location.capturedAtEpochMillis
                    ),
                    outputName = "${System.currentTimeMillis()}_${photos.size + 1}.jpg"
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Result.failure(error)
            } finally {
                source?.takeIf { !it.isRecycled }?.recycle()
                rawFile.delete()
            }

            isProcessingPhoto = false
            result.fold(
                onSuccess = { photo -> photos = photos + photo.file },
                onFailure = { error ->
                    errorMessage = error.message
                        ?: "Gagal memproses watermark foto."
                }
            )
        }
    }

    fun attemptBack() {
        val hasChanges = selection.sls != null ||
            namaObjek.isNotBlank() ||
            alamat.isNotBlank() ||
            catatan.isNotBlank() ||
            capturedLocation != null ||
            photos.isNotEmpty()
        if (hasChanges && !isSaving) {
            showDiscardDialog = true
        } else if (!isSaving) {
            onBack()
        }
    }

    BackHandler(onBack = ::attemptBack)

    if (showCamera) {
        CameraCaptureScreen(
            onPhotoCaptured = ::processCapturedPhoto,
            onCancel = { showCamera = false },
            onCaptureError = { errorMessage = it }
        )
        return
    }

    if (showLocationRationale) {
        AlertDialog(
            onDismissRequest = { showLocationRationale = false },
            title = { Text("Izin lokasi") },
            text = {
                Text(
                    "Lokasi digunakan untuk menandai koordinat dan waktu " +
                        "pendataan. Pengambilan hanya dilakukan saat tombol ditekan."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLocationRationale = false
                        locationPermissionLauncher.launch(
                            arrayOf(
                                android.Manifest.permission.ACCESS_FINE_LOCATION,
                                android.Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                ) {
                    Text("Izinkan")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLocationRationale = false }) {
                    Text("Batal")
                }
            }
        )
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Buang perubahan?") },
            text = { Text("Data yang belum disimpan dan foto watermark akan dihapus dari draf ini.") },
            confirmButton = {
                Button(
                    onClick = {
                        photos.forEach { it.delete() }
                        showDiscardDialog = false
                        onBack()
                    }
                ) {
                    Text("Buang")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text("Lanjut mengisi")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Tambah Pendataan")
                        Text(
                            text = "Form lapangan SE2026",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = ::attemptBack, enabled = !isSaving) {
                        Text("‹  Kembali")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
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
                        text = "Form Pendataan Responden",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Lengkapi wilayah, lokasi, identitas objek, dan bukti foto.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.86f)
                    )
                }
            }

            FormWilayahSection(
                wilayah = wilayah,
                initialSelection = selection,
                onSelectionChanged = {
                    selection = it
                    if (capturedLocation != null) {
                        locationAudit = locationChecker.inspect(
                            capturedLocation!!,
                            it.sls?.let { sls ->
                                SlsCentroid(
                                    sls.latCentroid ?: Double.NaN,
                                    sls.lonCentroid ?: Double.NaN
                                )
                            }
                        )
                    }
                }
            )

            FormSectionHeader(
                number = "02",
                title = "Lokasi & waktu",
                subtitle = "Ambil titik aktual sebelum memotret objek."
            )
            Button(
                onClick = ::requestLocation,
                enabled = !isSaving && !isProcessingPhoto,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (capturedLocation == null) "Ambil Titik Lokasi" else "Ambil Ulang Lokasi")
            }
            capturedLocation?.let { location ->
                LocationSummary(location = location, audit = locationAudit)
            }
            if (locationDisabled) {
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                locationHelper.createLocationSettingsIntent()
                            )
                        }.onFailure {
                            errorMessage = "Pengaturan lokasi tidak dapat dibuka."
                        }
                    },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Buka Pengaturan Lokasi")
                }
            }

            FormSectionHeader(
                number = "03",
                title = "Identitas objek",
                subtitle = "Informasi dasar usaha atau keluarga yang didata."
            )
            FormChoiceDropdown(
                label = "Jenis objek",
                selected = if (jenisObjek == JenisObjek.KELUARGA) "Keluarga" else "Usaha",
                options = listOf("Keluarga" to JenisObjek.KELUARGA, "Usaha" to JenisObjek.USAHA),
                onSelected = { jenisObjek = it }
            )
            OutlinedTextField(
                value = namaObjek,
                onValueChange = { namaObjek = it.take(100) },
                label = {
                    Text(
                        if (jenisObjek == JenisObjek.KELUARGA) {
                            "Nama Kepala Keluarga"
                        } else {
                            "Nama Usaha"
                        }
                    )
                },
                supportingText = { Text("${namaObjek.length}/100") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = alamat,
                onValueChange = { alamat = it.take(200) },
                label = { Text("Alamat / catatan lokasi") },
                supportingText = { Text("${alamat.length}/200") },
                minLines = 2,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )
            FormChoiceDropdown(
                label = "Status pendataan",
                selected = if (statusPendataan == StatusPendataan.LENGKAP) {
                    "Terisi Lengkap"
                } else {
                    "Terisi Tidak Lengkap"
                },
                options = listOf(
                    "Terisi Lengkap" to StatusPendataan.LENGKAP,
                    "Terisi Tidak Lengkap" to StatusPendataan.TIDAK_LENGKAP
                ),
                onSelected = { statusPendataan = it }
            )
            if (statusPendataan == StatusPendataan.TIDAK_LENGKAP) {
                OutlinedTextField(
                    value = catatan,
                    onValueChange = { catatan = it.take(500) },
                    label = { Text("Catatan (wajib)") },
                    supportingText = { Text("${catatan.length}/500") },
                    minLines = 3,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            FormSectionHeader(
                number = "04",
                title = "Foto bukti",
                subtitle = "Setiap foto akan diberi watermark koordinat dan waktu."
            )
            Text(
                text = "${photos.size}/$MAX_PHOTOS foto watermark tersimpan di penyimpanan privat aplikasi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                photos.forEachIndexed { index, file ->
                    PhotoPreview(
                        file = file,
                        index = index,
                        onDelete = {
                            file.delete()
                            photos = photos.filterIndexed { photoIndex, _ -> photoIndex != index }
                        },
                        enabled = !isSaving && !isProcessingPhoto
                    )
                }
            }
            Button(
                onClick = { showCamera = true },
                enabled = selection.sls != null &&
                    capturedLocation != null &&
                    photos.size < MAX_PHOTOS &&
                    !isSaving &&
                    !isProcessingPhoto,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isProcessingPhoto) "Memproses watermark..." else "Ambil Gambar")
            }

            errorMessage?.let {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = it,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = { save(asDraft = false) },
                enabled = !isSaving && !isProcessingPhoto && isCompleteEnough,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isSaving) "Menyimpan..." else "Simpan")
            }
            OutlinedButton(
                onClick = { save(asDraft = true) },
                enabled = !isSaving && !isProcessingPhoto,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Simpan sebagai Draf")
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FormSectionHeader(
    number: String,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Text(
                text = number,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LocationSummary(
    location: CapturedLocation,
    audit: LocationAuditResult?
) {
    val timestamp = remember(location.capturedAtEpochMillis) {
        SimpleDateFormat(
            "dd-MM-yyyy HH:mm:ss 'WITA'",
            Locale("id", "ID")
        ).apply {
            timeZone = TimeZone.getTimeZone("Asia/Makassar")
        }.format(Date(location.capturedAtEpochMillis))
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.22f)
        ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Titik lokasi tersimpan",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondary
                ) {
                    Text(
                        text = "GPS",
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                "${formatCoordinate(location.latitude)}, ${formatCoordinate(location.longitude)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Akurasi: ${location.accuracyM?.let { "%.1f m".format(it) } ?: "--"}  •  $timestamp",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            audit?.warningMessage?.let {
                Text(
                    text = it,
                    color = if (audit.hasFakeGpsWarning) {
                        MaterialTheme.colorScheme.error
                    } else {
                        Color(0xFF8A5A00)
                    }
                )
            }
        }
    }
}

@Composable
private fun FormChoiceDropdown(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit
) {
    var expanded by remember(label) { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            shape = MaterialTheme.shapes.medium,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp,
                vertical = 9.dp
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(horizontalAlignment = Alignment.Start) {
                    Text(label, style = MaterialTheme.typography.labelSmall)
                    Text(selected)
                }
                Text("▾")
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth()
        ) {
            options.forEach { (display, value) ->
                DropdownMenuItem(
                    text = { Text(display) },
                    onClick = {
                        expanded = false
                        onSelected(value)
                    }
                )
            }
        }
    }
}

@Composable
private fun PhotoPreview(
    file: File,
    index: Int,
    onDelete: () -> Unit,
    enabled: Boolean
) {
    val bitmap = remember(file.absolutePath) {
        BitmapFactory.decodeFile(file.absolutePath)
    }
    Card(
        modifier = Modifier.width(104.dp),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Pratinjau foto ${index + 1}",
                    modifier = Modifier
                        .size(94.dp)
                        .clip(MaterialTheme.shapes.small)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(94.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Foto")
                }
            }
            TextButton(onClick = onDelete, enabled = enabled) {
                Text("Hapus")
            }
        }
    }
}

private fun validationMessage(
    selection: WilayahSelection,
    location: CapturedLocation?,
    namaObjek: String,
    statusPendataan: String,
    catatan: String,
    photoCount: Int
): String {
    return when {
        selection.sls == null -> "Pilih Kode Kecamatan, Desa, dan SLS."
        location == null -> "Ambil titik lokasi terlebih dahulu."
        namaObjek.isBlank() -> "Nama objek wajib diisi."
        statusPendataan == StatusPendataan.TIDAK_LENGKAP && catatan.isBlank() ->
            "Catatan wajib diisi untuk status Terisi Tidak Lengkap."
        photoCount == 0 -> "Minimal satu foto watermark wajib diambil."
        else -> "Lengkapi field wajib sebelum menyimpan."
    }
}

private fun formatCoordinate(value: Double): String =
    String.format(Locale.US, "%.6f", value)

private const val MAX_PHOTOS = 3
