package com.fajriantomanungki.revisitapp.feature.pendataan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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

/**
 * Capture satu foto menggunakan CameraX. File output dibuat pada cache
 * internal, lalu callback menyerahkannya ke form untuk diberi watermark.
 * File asli tidak pernah dipindahkan ke galeri publik.
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
    var hasCameraPermission by remember {
        mutableStateOf(context.hasCameraPermission())
    }
    var showPermissionRationale by rememberSaveable {
        mutableStateOf(!hasCameraPermission)
    }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var isTakingPhoto by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) {
            onCaptureError("Izin kamera diperlukan untuk mengambil foto bukti.")
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
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

    if (showPermissionRationale && !hasCameraPermission) {
        AlertDialog(
            onDismissRequest = { showPermissionRationale = false },
            title = { Text("Izin kamera") },
            text = {
                Text(
                    "Kamera digunakan untuk mengambil foto bukti pendataan. " +
                        "Foto asli hanya diproses di penyimpanan privat aplikasi."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPermissionRationale = false
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                ) {
                    Text("Izinkan")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = onCancel) {
                    Text("Batal")
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
                text = "Izin kamera belum diberikan.",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
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
                    enabled = !isTakingPhoto,
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
                                    isTakingPhoto = false
                                    onPhotoCaptured(outputFile)
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
                        imageCapture != null &&
                        !isTakingPhoto,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isTakingPhoto) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                    } else {
                        Text("Ambil Gambar")
                    }
                }
            }
        }
    }
}

private fun Context.hasCameraPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED
}
