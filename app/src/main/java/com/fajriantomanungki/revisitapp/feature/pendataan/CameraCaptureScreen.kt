package com.fajriantomanungki.revisitapp.feature.pendataan

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Capture atau memilih satu foto bukti.
 *
 * Hasil kamera dibuat sementara di cache agar tetap kompatibel dengan alur
 * watermark yang sudah ada. Sebelum callback dipanggil, salinan foto kamera
 * disimpan ke galeri perangkat. Foto yang dipilih dari galeri hanya disalin
 * sementara ke cache dan tidak mengubah file asli milik pengguna.
 */
@Composable
fun CameraCaptureScreen(
    onPhotoCaptured: (File) -> Unit,
    onCancel: () -> Unit,
    onCaptureError: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val coroutineScope = rememberCoroutineScope()
    var hasCameraPermission by remember {
        mutableStateOf(context.hasCameraPermission())
    }
    var hasLegacyGalleryWritePermission by remember {
        mutableStateOf(context.hasLegacyGalleryWritePermission())
    }
    var showPermissionRationale by rememberSaveable {
        mutableStateOf(
            !hasCameraPermission || !hasLegacyGalleryWritePermission
        )
    }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var isTakingPhoto by remember { mutableStateOf(false) }
    var isImportingGallery by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasCameraPermission = permissions[Manifest.permission.CAMERA]
            ?: context.hasCameraPermission()
        hasLegacyGalleryWritePermission = context.hasLegacyGalleryWritePermission()
        if (!hasCameraPermission) {
            onCaptureError("Izin kamera diperlukan untuk mengambil foto bukti.")
        } else if (!hasLegacyGalleryWritePermission) {
            onCaptureError(
                "Izin penyimpanan diperlukan pada Android 9 atau lebih lama " +
                    "agar foto kamera dapat tersimpan di galeri."
            )
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        isImportingGallery = true
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                copyGalleryImageToCache(context, uri)
            }
            isImportingGallery = false
            result.fold(
                onSuccess = onPhotoCaptured,
                onFailure = { error ->
                    onCaptureError(
                        "Gagal mengambil foto dari galeri: " +
                            (error.message ?: "file tidak dapat dibaca")
                    )
                }
            )
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission || !hasLegacyGalleryWritePermission) {
            showPermissionRationale = true
        }
    }

    DisposableEffect(lifecycleOwner, hasCameraPermission) {
        var cameraProvider: ProcessCameraProvider? = null
        if (hasCameraPermission) {
            val providerFuture = ProcessCameraProvider.getInstance(context)
            val listener = Runnable {
                try {
                    cameraProvider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
                        .build()
                    cameraProvider?.unbindAll()
                    cameraProvider?.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        capture
                    )
                    imageCapture = capture
                } catch (error: Exception) {
                    onCaptureError(
                        "Kamera tidak dapat dibuka: " +
                            (error.message ?: "kesalahan tidak diketahui")
                    )
                }
            }
            providerFuture.addListener(
                listener,
                ContextCompat.getMainExecutor(context)
            )
        }

        onDispose {
            cameraProvider?.unbindAll()
            imageCapture = null
        }
    }

    if (showPermissionRationale &&
        (!hasCameraPermission || !hasLegacyGalleryWritePermission)
    ) {
        AlertDialog(
            onDismissRequest = { showPermissionRationale = false },
            title = { Text("Izin kamera") },
            text = {
                Text(
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                        "Kamera digunakan untuk mengambil foto bukti. Pada Android 9 " +
                            "atau lebih lama, izin penyimpanan juga diperlukan agar " +
                            "hasil foto kamera dapat disimpan ke galeri perangkat."
                    } else {
                        "Kamera digunakan untuk mengambil foto bukti. Foto hasil " +
                            "jepretan juga akan disalin ke galeri perangkat."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPermissionRationale = false
                        permissionLauncher.launch(context.requiredCameraPermissions())
                    }
                ) {
                    Text("Izinkan")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showPermissionRationale = false }
                ) {
                    Text("Nanti")
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (hasCameraPermission) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = "Izin kamera belum diberikan. Anda tetap dapat memilih foto dari galeri.",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    enabled = !isTakingPhoto && !isImportingGallery,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Batal")
                }
                Button(
                    onClick = {
                        val capture = imageCapture ?: return@Button
                        isTakingPhoto = true
                        val outputFile = try {
                            File.createTempFile(
                                "camera_",
                                ".jpg",
                                context.cacheDir
                            )
                        } catch (error: Exception) {
                            isTakingPhoto = false
                            onCaptureError(
                                "Tidak dapat menyiapkan file foto: " +
                                    (error.message ?: "kesalahan tidak diketahui")
                            )
                            return@Button
                        }
                        val outputOptions = ImageCapture.OutputFileOptions
                            .Builder(outputFile)
                            .build()
                        capture.takePicture(
                            outputOptions,
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(
                                    outputFileResults: ImageCapture.OutputFileResults
                                ) {
                                    coroutineScope.launch {
                                        val galleryResult = withContext(Dispatchers.IO) {
                                            publishCapturedPhotoToGallery(
                                                context = context,
                                                sourceFile = outputFile
                                            )
                                        }
                                        isTakingPhoto = false
                                        galleryResult.exceptionOrNull()?.let { error ->
                                            onCaptureError(
                                                "Foto berhasil diambil, tetapi salinan ke galeri gagal: " +
                                                    (error.message ?: "kesalahan penyimpanan")
                                            )
                                        }
                                        onPhotoCaptured(outputFile)
                                    }
                                }

                                override fun onError(
                                    exception: ImageCaptureException
                                ) {
                                    isTakingPhoto = false
                                    outputFile.delete()
                                    onCaptureError(
                                        "Gagal mengambil foto: " +
                                            (exception.message ?: "kesalahan kamera")
                                    )
                                }
                            }
                        )
                    },
                    enabled = hasCameraPermission &&
                        hasLegacyGalleryWritePermission &&
                        imageCapture != null &&
                        !isTakingPhoto &&
                        !isImportingGallery,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isTakingPhoto) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                    } else {
                        Text("Ambil Gambar")
                    }
                }
            }

            OutlinedButton(
                onClick = { galleryLauncher.launch("image/*") },
                enabled = !isTakingPhoto && !isImportingGallery,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isImportingGallery) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                } else {
                    Text("Pilih dari Galeri")
                }
            }
        }
    }
}

private fun copyGalleryImageToCache(
    context: Context,
    uri: Uri
): Result<File> = runCatching {
    val input = context.contentResolver.openInputStream(uri)
        ?: throw IOException("File galeri tidak dapat dibuka")
    val outputFile = File.createTempFile(
        "gallery_",
        ".img",
        context.cacheDir
    )
    try {
        input.use { source ->
            outputFile.outputStream().use { target ->
                source.copyTo(target)
            }
        }
        if (!outputFile.isFile || outputFile.length() <= 0L) {
            throw IOException("File galeri kosong atau tidak dapat dibaca")
        }
        outputFile
    } catch (error: Throwable) {
        outputFile.delete()
        throw error
    }
}

private fun publishCapturedPhotoToGallery(
    context: Context,
    sourceFile: File
): Result<Unit> = runCatching {
    require(sourceFile.isFile) {
        "File kamera tidak tersedia"
    }

    val displayName = "RevisitSE2026_${System.currentTimeMillis()}.jpg"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/" + GALLERY_DIRECTORY
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: throw IOException("Galeri perangkat tidak dapat membuat file baru")

        try {
            val output = resolver.openOutputStream(uri, "w")
                ?: throw IOException("Galeri perangkat tidak dapat ditulis")
            sourceFile.inputStream().use { source ->
                output.use { target -> source.copyTo(target) }
            }
            val readyValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, readyValues, null, null)
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    } else {
        if (!context.hasLegacyGalleryWritePermission()) {
            throw SecurityException("Izin penyimpanan belum diberikan")
        }
        @Suppress("DEPRECATION")
        val picturesDirectory = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES
        )
        val targetDirectory = File(picturesDirectory, GALLERY_DIRECTORY)
        if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
            throw IOException("Folder galeri RevisitSE2026 tidak dapat dibuat")
        }
        val targetFile = File(targetDirectory, displayName)
        sourceFile.copyTo(targetFile, overwrite = false)
        MediaScannerConnection.scanFile(
            context,
            arrayOf(targetFile.absolutePath),
            arrayOf("image/jpeg"),
            null
        )
    }
}

private fun Context.requiredCameraPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
        arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    } else {
        arrayOf(Manifest.permission.CAMERA)
    }
}

private fun Context.hasCameraPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED
}

private fun Context.hasLegacyGalleryWritePermission(): Boolean {
    return Build.VERSION.SDK_INT > Build.VERSION_CODES.P ||
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
}

private const val GALLERY_DIRECTORY = "RevisitSE2026"
