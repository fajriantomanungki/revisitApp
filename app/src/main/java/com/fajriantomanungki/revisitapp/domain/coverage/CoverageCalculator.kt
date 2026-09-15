package com.fajriantomanungki.revisitapp.domain.coverage

import android.graphics.Color as AndroidColor
import androidx.annotation.ColorInt

/**
 * Lima kelas warna F-07. Ambang dihitung dari rasio
 * jumlah_terdata / target_responden.
 */
enum class CoverageClass(
    val label: String,
    @ColorInt val colorArgb: Int
) {
    MERAH_TUA(
        label = "0–24%",
        colorArgb = AndroidColor.rgb(183, 28, 28)
    ),
    ORANYE(
        label = "25–49%",
        colorArgb = AndroidColor.rgb(239, 108, 0)
    ),
    KUNING(
        label = "50–74%",
        colorArgb = AndroidColor.rgb(249, 168, 37)
    ),
    HIJAU_MUDA(
        label = "75–99%",
        colorArgb = AndroidColor.rgb(124, 179, 66)
    ),
    HIJAU_TUA(
        label = "100%",
        colorArgb = AndroidColor.rgb(27, 94, 32)
    )
}

/**
 * Bentuk data wilayah yang dibutuhkan domain, agar kalkulator tidak terikat
 * langsung pada entity Room.
 */
data class WilayahCoverageSource(
    val kodeKec: String,
    val namaKec: String,
    val kodeDesa: String,
    val namaDesa: String,
    val kodeSls: String,
    val namaSls: String,
    val targetResponden: Long,
    val latCentroid: Double?,
    val lonCentroid: Double?
)

data class ServerCoverageSource(
    val kodeSls: String,
    val jumlahTerdata: Long,
    val target: Long
)

data class LocalCoverageSource(
    val kodeSls: String,
    val jumlahTerdata: Long
)

/**
 * Model siap tampil pada kartu, daftar prioritas, dan marker peta.
 */
data class SlsCoverage(
    val kodeKec: String,
    val namaKec: String,
    val kodeDesa: String,
    val namaDesa: String,
    val kodeSls: String,
    val namaSls: String,
    val targetResponden: Long,
    val jumlahServer: Long,
    val jumlahLokalBelumTerkirim: Long,
    val jumlahTerdata: Long,
    val coverageRatio: Double,
    val coveragePercent: Double,
    val coverageClass: CoverageClass,
    val latCentroid: Double?,
    val lonCentroid: Double?,
    val targetValid: Boolean
) {
    val hasValidCentroid: Boolean
        get() {
            val latitude = latCentroid ?: return false
            val longitude = lonCentroid ?: return false
            return latitude in -90.0..90.0 && longitude in -180.0..180.0
        }
}

/**
 * Mengubah rasio cakupan menjadi kelas warna sesuai PRD:
 * 0–24, 25–49, 50–74, 75–99, dan >=100 persen.
 *
 * Target nol tidak memiliki rasio yang bermakna. Untuk menjaga lima kelas
 * tetap konsisten, data tersebut ditampilkan sebagai merah tua dengan flag
 * targetValid=false sehingga UI dapat menampilkan "Target belum tersedia".
 */
fun classifyCoverage(
    coverageRatio: Double,
    targetValid: Boolean = true
): CoverageClass {
    if (!targetValid || !coverageRatio.isFinite()) {
        return CoverageClass.MERAH_TUA
    }

    return when {
        coverageRatio < 0.25 -> CoverageClass.MERAH_TUA
        coverageRatio < 0.50 -> CoverageClass.ORANYE
        coverageRatio < 0.75 -> CoverageClass.KUNING
        coverageRatio < 1.00 -> CoverageClass.HIJAU_MUDA
        else -> CoverageClass.HIJAU_TUA
    }
}

/**
 * Menggabungkan snapshot cakupan server dengan entri lokal yang belum
 * berstatus TERKIRIM. Record lokal tersebut belum tercermin di snapshot
 * server, sehingga sengaja ditambahkan ke jumlah server.
 */
fun mergeCoverage(
    wilayah: List<WilayahCoverageSource>,
    serverCache: List<ServerCoverageSource>,
    localPending: List<LocalCoverageSource>
): List<SlsCoverage> {
    val serverBySls = serverCache.associateBy { it.kodeSls }
    val localBySls = localPending
        .groupingBy { it.kodeSls }
        .fold(0L) { total, row ->
            total + row.jumlahTerdata.coerceAtLeast(0L)
        }

    return wilayah
        .map { master ->
            val server = serverBySls[master.kodeSls]
            val target = server?.target
                ?.takeIf { it > 0L }
                ?: master.targetResponden.coerceAtLeast(0L)
            val jumlahServer = server?.jumlahTerdata?.coerceAtLeast(0L) ?: 0L
            val jumlahLokal = localBySls[master.kodeSls] ?: 0L
            val jumlahTerdata = jumlahServer + jumlahLokal
            val targetValid = target > 0L
            val ratio = if (targetValid) {
                jumlahTerdata.toDouble() / target.toDouble()
            } else {
                0.0
            }

            SlsCoverage(
                kodeKec = master.kodeKec,
                namaKec = master.namaKec,
                kodeDesa = master.kodeDesa,
                namaDesa = master.namaDesa,
                kodeSls = master.kodeSls,
                namaSls = master.namaSls,
                targetResponden = target,
                jumlahServer = jumlahServer,
                jumlahLokalBelumTerkirim = jumlahLokal,
                jumlahTerdata = jumlahTerdata,
                coverageRatio = ratio,
                coveragePercent = ratio * 100.0,
                coverageClass = classifyCoverage(ratio, targetValid),
                latCentroid = master.latCentroid,
                lonCentroid = master.lonCentroid,
                targetValid = targetValid
            )
        }
        .sortedWith(
            compareBy<SlsCoverage> { it.namaKec }
                .thenBy { it.namaDesa }
                .thenBy { it.namaSls }
        )
}
