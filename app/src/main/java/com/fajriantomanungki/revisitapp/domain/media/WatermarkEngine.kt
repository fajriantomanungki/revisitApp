package com.fajriantomanungki.revisitapp.domain.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Data yang ditulis permanen ke empat baris watermark.
 *
 * [namaSls] diprioritaskan sebagai identitas SLS yang tampil. Jika kosong,
 * [kodeSls] digunakan sebagai fallback. [timestampEpochMillis] sebaiknya
 * berasal dari [CapturedLocation.capturedAtEpochMillis].
 */
data class WatermarkData(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Double?,
    val kodeSls: String = "",
    val namaSls: String = "",
    val namaDesa: String = "",
    val namaKec: String = "",
    val namaKota: String = "",
    val timestampEpochMillis: Long
)

data class WatermarkedPhoto(
    val file: File,
    val width: Int,
    val height: Int,
    val sizeBytes: Long
)

/**
 * Merender watermark ke bitmap secara permanen, menulis metadata EXIF, lalu
 * menyimpan JPEG hanya di internal storage aplikasi.
 *
 * Semua operasi bitmap dan file dijalankan pada dispatcher background. Bitmap
 * sumber tidak dimodifikasi dan tidak disimpan oleh engine; caller tetap
 * bertanggung jawab melepaskan bitmap atau menghapus file kamera sementara
 * setelah proses selesai.
 */
class WatermarkEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Membaca foto kamera secara hemat memori, membetulkan orientasi EXIF,
     * kemudian meneruskan bitmap tegak ke engine watermark.
     */
    suspend fun renderAndSave(
        sourceFile: File,
        data: WatermarkData,
        outputName: String = UUID.randomUUID().toString() + ".jpg"
    ): Result<WatermarkedPhoto> {
        val source = try {
            withContext(Dispatchers.IO) {
                decodeUpright(sourceFile)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            return Result.failure(error)
        }

        return try {
            renderAndSave(
                source = source,
                data = data,
                outputName = outputName
            )
        } finally {
            if (!source.isRecycled) source.recycle()
        }
    }

    suspend fun renderAndSave(
        source: Bitmap,
        data: WatermarkData,
        outputName: String = UUID.randomUUID().toString() + ".jpg"
    ): Result<WatermarkedPhoto> = withContext(Dispatchers.Default) {
        try {
            require(!source.isRecycled) { "Bitmap sumber sudah di-recycle" }
            validateWatermarkData(data)

            val directory = File(context.filesDir, PHOTO_DIRECTORY)
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("Gagal membuat direktori foto internal")
            }

            val fileName = sanitizeFileName(outputName)
            val outputFile = File(directory, fileName)
            val temporaryFile = File(
                directory,
                ".$fileName.${UUID.randomUUID()}.tmp"
            )
            val renderedBitmap = resizeToMaximumLongEdge(source)

            Result.success(
                try {
                    drawWatermark(renderedBitmap, data)

                    FileOutputStream(temporaryFile).use { stream ->
                        check(
                            renderedBitmap.compress(
                                Bitmap.CompressFormat.JPEG,
                                JPEG_QUALITY,
                                stream
                            )
                        ) {
                            "Gagal melakukan kompresi JPEG"
                        }
                    }

                    writeExif(temporaryFile, data)
                    temporaryFile.copyTo(outputFile, overwrite = true)

                    WatermarkedPhoto(
                        file = outputFile,
                        width = renderedBitmap.width,
                        height = renderedBitmap.height,
                        sizeBytes = outputFile.length()
                    )
                } finally {
                    if (!renderedBitmap.isRecycled) {
                        renderedBitmap.recycle()
                    }
                    temporaryFile.delete()
                }
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun decodeUpright(sourceFile: File): Bitmap {
        require(sourceFile.isFile) {
            "File foto tidak ditemukan: ${sourceFile.absolutePath}"
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourceFile.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "File foto tidak dapat dibaca"
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateDecodeSampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(sourceFile.absolutePath, options)
            ?: error("File foto tidak dapat didekode")

        val orientation = ExifInterface(sourceFile.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        val matrix = orientationMatrix(orientation)
            ?: return decoded

        return try {
            Bitmap.createBitmap(
                decoded,
                0,
                0,
                decoded.width,
                decoded.height,
                matrix,
                true
            ).also { transformed ->
                if (transformed !== decoded && !decoded.isRecycled) decoded.recycle()
            }
        } catch (error: Throwable) {
            if (!decoded.isRecycled) decoded.recycle()
            throw error
        }
    }

    private fun calculateDecodeSampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > MAX_DECODE_LONG_EDGE ||
            height / sample > MAX_DECODE_LONG_EDGE
        ) {
            sample *= 2
        }
        return sample
    }

    private fun orientationMatrix(orientation: Int): Matrix? {
        return when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply {
                setScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply {
                setRotate(180f)
            }

            ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply {
                setScale(1f, -1f)
            }

            ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply {
                setRotate(90f)
                postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply {
                setRotate(90f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply {
                setRotate(-90f)
                postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply {
                setRotate(-90f)
            }

            else -> null
        }
    }

    private fun resizeToMaximumLongEdge(source: Bitmap): Bitmap {
        val longestEdge = maxOf(source.width, source.height)
        val scale = min(1f, MAX_LONG_EDGE.toFloat() / longestEdge.toFloat())
        val targetWidth = (source.width * scale).roundToLong().toInt().coerceAtLeast(1)
        val targetHeight = (source.height * scale).roundToLong().toInt().coerceAtLeast(1)

        val scaled = if (targetWidth == source.width && targetHeight == source.height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        }

        val mutable = try {
            scaled.copy(Bitmap.Config.ARGB_8888, true)
        } finally {
            if (scaled !== source && !scaled.isRecycled) {
                scaled.recycle()
            }
        }
        return mutable
    }

    private fun drawWatermark(bitmap: Bitmap, data: WatermarkData) {
        val lines = buildWatermarkLines(data)
        val horizontalPadding = bitmap.width * HORIZONTAL_PADDING_RATIO
        val availableTextWidth = (bitmap.width - 2f * horizontalPadding).coerceAtLeast(1f)
        val baseTextSize = (bitmap.width * FONT_SIZE_RATIO).coerceAtLeast(MIN_FONT_SIZE)
        val maxTextSizeByHeight =
            (bitmap.height / (lines.size * LINE_HEIGHT_RATIO + VERTICAL_PADDING_RATIO * 2f))
                .coerceAtLeast(MIN_FONT_SIZE)
        val textSize = fitTextSize(
            lines = lines,
            requestedSize = min(baseTextSize, maxTextSizeByHeight),
            maxWidth = availableTextWidth
        )
        val lineHeight = textSize * LINE_HEIGHT_RATIO
        val verticalPadding = textSize * VERTICAL_PADDING_RATIO
        val overlayHeight = lineHeight * lines.size + verticalPadding * 2f
        val overlayTop = (bitmap.height - overlayHeight).coerceAtLeast(0f)

        val canvas = Canvas(bitmap)
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(BACKGROUND_ALPHA, Color.BLACK, Color.BLACK, Color.BLACK)
            style = Paint.Style.FILL
        }
        canvas.drawRect(
            0f,
            overlayTop,
            bitmap.width.toFloat(),
            bitmap.height.toFloat(),
            backgroundPaint
        )

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            isDither = true
        }
        var baseline = overlayTop + verticalPadding - textPaint.fontMetrics.ascent
        lines.forEach { line ->
            canvas.drawText(line, horizontalPadding, baseline, textPaint)
            baseline += lineHeight
        }
    }

    private fun fitTextSize(
        lines: List<String>,
        requestedSize: Float,
        maxWidth: Float
    ): Float {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = requestedSize
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val longestLine = lines.maxOfOrNull { paint.measureText(it) } ?: 0f
        if (longestLine <= maxWidth || longestLine == 0f) {
            return requestedSize
        }
        return (requestedSize * maxWidth / longestLine).coerceAtLeast(MIN_FONT_SIZE)
    }

    private fun buildWatermarkLines(data: WatermarkData): List<String> {
        val accuracyText = data.accuracyM
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { String.format(Locale.US, "(±%.0f m)", it) }
            ?: "(akurasi --)"

        val coordinateLine = String.format(
            Locale.US,
            "%.6f, %.6f %s",
            data.latitude,
            data.longitude,
            accuracyText
        )

        val slsIdentifier = data.namaSls.trim()
            .takeIf { it.isNotEmpty() }
            ?: data.kodeSls.trim().takeIf { it.isNotEmpty() }
            ?: "-"
        val slsPrefix = if (slsIdentifier.startsWith("SLS ", ignoreCase = true)) "" else "SLS "
        val desa = data.namaDesa.trim().ifEmpty { "-" }
        val kecamatan = data.namaKec.trim().ifEmpty { "-" }
        val kota = data.namaKota.trim()

        val slsLine = "$slsPrefix$slsIdentifier Kel. $desa"
        val kecamatanLine = if (kota.isEmpty()) {
            "Kec. $kecamatan"
        } else {
            "Kec. $kecamatan, $kota"
        }

        return listOf(
            coordinateLine,
            slsLine,
            kecamatanLine,
            formatTimestampWita(data.timestampEpochMillis)
        )
    }

    private fun writeExif(file: File, data: WatermarkData) {
        val exif = ExifInterface(file.absolutePath)
        exif.setAttribute(
            ExifInterface.TAG_GPS_LATITUDE,
            coordinateToExifDms(data.latitude)
        )
        exif.setAttribute(
            ExifInterface.TAG_GPS_LATITUDE_REF,
            if (data.latitude >= 0.0) "N" else "S"
        )
        exif.setAttribute(
            ExifInterface.TAG_GPS_LONGITUDE,
            coordinateToExifDms(data.longitude)
        )
        exif.setAttribute(
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            if (data.longitude >= 0.0) "E" else "W"
        )

        val exifTimestamp = SimpleDateFormat(
            EXIF_DATE_PATTERN,
            Locale.US
        ).apply {
            timeZone = WITA_TIME_ZONE
        }.format(Date(data.timestampEpochMillis))
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifTimestamp)
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, exifTimestamp)
        exif.setAttribute(ExifInterface.TAG_DATETIME, exifTimestamp)
        exif.setAttribute(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL.toString()
        )
        exif.saveAttributes()
    }

    private fun coordinateToExifDms(coordinate: Double): String {
        val totalThousandthsOfArcSecond =
            (abs(coordinate) * ARC_SECONDS_PER_DEGREE * 1_000.0).roundToLong()
        val degrees = totalThousandthsOfArcSecond / THOUSANDTHS_PER_DEGREE
        val remaining = totalThousandthsOfArcSecond % THOUSANDTHS_PER_DEGREE
        val minutes = remaining / THOUSANDTHS_PER_MINUTE
        val secondsInThousandths = remaining % THOUSANDTHS_PER_MINUTE
        return "$degrees/1,$minutes/1,$secondsInThousandths/1000"
    }

    private fun formatTimestampWita(epochMillis: Long): String {
        return SimpleDateFormat(
            WATERMARK_DATE_PATTERN,
            Locale.US
        ).apply {
            timeZone = WITA_TIME_ZONE
        }.format(Date(epochMillis))
    }

    private fun sanitizeFileName(value: String): String {
        val fileName = value
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(UNSAFE_FILE_NAME, "_")
            .trim('.', ' ')
            .ifEmpty { UUID.randomUUID().toString() }

        return if (fileName.endsWith(".jpg", ignoreCase = true)) {
            fileName
        } else {
            "$fileName.jpg"
        }
    }

    private fun validateWatermarkData(data: WatermarkData) {
        require(data.latitude.isFinite() && data.latitude in -90.0..90.0) {
            "Latitude harus berada di antara -90 dan 90"
        }
        require(data.longitude.isFinite() && data.longitude in -180.0..180.0) {
            "Longitude harus berada di antara -180 dan 180"
        }
        require(data.timestampEpochMillis > 0L) {
            "Timestamp watermark tidak valid"
        }
    }

    private companion object {
        const val PHOTO_DIRECTORY = "photos"
        const val MAX_LONG_EDGE = 1_600
        const val MAX_DECODE_LONG_EDGE = 3_200
        const val JPEG_QUALITY = 80
        const val BACKGROUND_ALPHA = 153 // 60% opacity.
        const val FONT_SIZE_RATIO = 0.025f
        const val MIN_FONT_SIZE = 12f
        const val HORIZONTAL_PADDING_RATIO = 0.035f
        const val VERTICAL_PADDING_RATIO = 0.45f
        const val LINE_HEIGHT_RATIO = 1.25f
        const val WATERMARK_DATE_PATTERN = "dd-MM-yyyy HH:mm:ss 'WITA'"
        const val EXIF_DATE_PATTERN = "yyyy:MM:dd HH:mm:ss"
        const val ARC_SECONDS_PER_DEGREE = 3_600.0
        const val THOUSANDTHS_PER_DEGREE = 3_600_000L
        const val THOUSANDTHS_PER_MINUTE = 60_000L
        val WITA_TIME_ZONE: TimeZone = TimeZone.getTimeZone("Asia/Makassar")
        val UNSAFE_FILE_NAME = Regex("[^A-Za-z0-9._-]")
    }
}
