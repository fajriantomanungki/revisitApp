package com.fajriantomanungki.revisitapp.data.sync

import android.util.Base64
import android.net.Uri
import com.fajriantomanungki.revisitapp.data.local.entity.FotoEntity
import com.fajriantomanungki.revisitapp.data.local.entity.PendataanEntity
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

data class UploadedPhotoReference(
    val idFoto: String,
    val driveFileId: String,
    val url: String?,
    val urutan: Int
)

data class SyncPayloadRecord(
    val record: PendataanEntity,
    val photos: List<UploadedPhotoReference>
)

data class RemoteSyncItemResult(
    val idRecord: String,
    val status: String,
    val message: String?
)

data class RemoteSyncResponse(
    val results: List<RemoteSyncItemResult>
)

data class RemoteWilayahRow(
    val kodeKab: String,
    val kabupaten: String,
    val kodeKec: String,
    val namaKec: String,
    val kodeDesa: String,
    val namaDesa: String,
    val kodeSls: String,
    val namaSls: String,
    val targetResponden: Int,
    val version: Int
)

data class MasterApiResponse(
    val version: Int,
    val hasChanges: Boolean,
    val rows: List<RemoteWilayahRow>
)

data class RemoteCoverageRow(
    val kodeSls: String,
    val target: Long,
    val jumlahTerdata: Long
)

data class CoverageApiResponse(
    val rows: List<RemoteCoverageRow>
)

data class LoginApiResponse(
    val idPetugas: String,
    val nama: String,
    val kodeKabupaten: String,
    val kabupaten: String,
    val waktuServer: String?
)

class AppsScriptApiException(
    message: String,
    val errorCode: String? = null,
    val httpCode: Int? = null,
    val retryable: Boolean = true,
    cause: Throwable? = null
) : IOException(message, cause)

/**
 * HTTP client tipis untuk kontrak JSON Apps Script.
 *
 * HttpURLConnection dipakai agar layer ini tidak memerlukan Retrofit atau
 * converter tambahan. Semua pemanggilan publik berjalan di Dispatchers.IO.
 */
class AppsScriptApi @Inject constructor() {

    suspend fun uploadPhoto(
        config: SyncConfig,
        idRecord: String,
        photo: FotoEntity,
        bytes: ByteArray
    ): UploadedPhotoReference = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty()) { "File foto kosong: ${photo.pathLokal}" }

        val payload = JSONObject()
            .put("action", "upload_foto")
            .put("token", config.token)
            .put("id_record", idRecord)
            .put("id_foto", photo.idFoto)
            .put("nama_file", photo.pathLokal.substringAfterLast('/'))
            .put("mime", "image/jpeg")
            .put(
                "data_base64",
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            )

        val response = requireSuccessfulResponse(
            postJson(config.endpointUrl, payload)
        )
        val driveFileId = response.optString("drive_file_id", "").trim()
        if (driveFileId.isEmpty()) {
            throw AppsScriptApiException(
                message = "Respons upload foto tidak memiliki drive_file_id",
                errorCode = "RESPONS_TIDAK_VALID",
                retryable = true
            )
        }

        UploadedPhotoReference(
            idFoto = photo.idFoto,
            driveFileId = driveFileId,
            url = response.optString("url", "").takeIf { it.isNotBlank() },
            urutan = photo.urutan
        )
    }

    /** Mengambil master wilayah hanya jika versi server lebih baru. */
    suspend fun getMaster(
        config: SyncConfig,
        localVersion: Int
    ): MasterApiResponse = withContext(Dispatchers.IO) {
        val response = requireSuccessfulResponse(
            getJson(
                endpointUrl = config.endpointUrl,
                parameters = mapOf(
                    "action" to "master",
                    "token" to config.token,
                    "versi" to localVersion.coerceAtLeast(0).toString()
                )
            )
        )
        val version = response.optInt("versi_master", localVersion)
        val hasChanges = response.optBoolean(
            "ada_perubahan",
            response.optBoolean("has_update", false)
        )
        val array = response.optJSONArray("data") ?: JSONArray()
        val rows = ArrayList<RemoteWilayahRow>(array.length())

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
                ?: throw AppsScriptApiException(
                    message = "Item master wilayah tidak valid pada index $index",
                    errorCode = "RESPONS_TIDAK_VALID",
                    retryable = true
                )
            val kodeSls = item.optString("kode_sls", "").trim()
            if (kodeSls.isEmpty()) {
                throw AppsScriptApiException(
                    message = "Master wilayah tidak memiliki kode_sls",
                    errorCode = "RESPONS_TIDAK_VALID",
                    retryable = true
                )
            }
            rows += RemoteWilayahRow(
                kodeKab = item.optString("kode_kab", "").trim(),
                kabupaten = item.optString("kabupaten", "").trim(),
                kodeKec = item.optString("kode_kec", "").trim(),
                namaKec = item.optString("nama_kec", "").trim(),
                kodeDesa = item.optString("kode_desa", "").trim(),
                namaDesa = item.optString("nama_desa", "").trim(),
                kodeSls = kodeSls,
                namaSls = item.optString("nama_sls", "").trim(),
                targetResponden = item.optLong("target_responden", 0L)
                    .coerceAtLeast(0L)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt(),
                version = item.optInt("versi_master", version)
            )
        }

        MasterApiResponse(
            version = version,
            hasChanges = hasChanges,
            rows = rows
        )
    }

    /** Mengambil cakupan server untuk cache Dashboard offline. */
    suspend fun getCoverage(
        config: SyncConfig,
        idPetugas: String
    ): CoverageApiResponse = withContext(Dispatchers.IO) {
        val response = requireSuccessfulResponse(
            getJson(
                endpointUrl = config.endpointUrl,
                parameters = mapOf(
                    "action" to "cakupan",
                    "token" to config.token,
                    "id_petugas" to idPetugas
                )
            )
        )
        val array = response.optJSONArray("data") ?: JSONArray()
        val rows = ArrayList<RemoteCoverageRow>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
                ?: throw AppsScriptApiException(
                    message = "Item cakupan tidak valid pada index $index",
                    errorCode = "RESPONS_TIDAK_VALID",
                    retryable = true
                )
            val kodeSls = item.optString("kode_sls", "").trim()
            if (kodeSls.isEmpty()) {
                throw AppsScriptApiException(
                    message = "Cakupan tidak memiliki kode_sls",
                    errorCode = "RESPONS_TIDAK_VALID",
                    retryable = true
                )
            }
            rows += RemoteCoverageRow(
                kodeSls = kodeSls,
                target = item.optLong("target", 0L),
                jumlahTerdata = item.optLong(
                    "jumlah_terdata",
                    item.optLong("terdata", 0L)
                )
            )
        }
        CoverageApiResponse(rows = rows)
    }

    /** Memvalidasi PIN ke endpoint Apps Script tanpa menyimpan PIN mentah. */
    suspend fun login(
        config: SyncConfig,
        idPetugas: String,
        pin: String
    ): LoginApiResponse = withContext(Dispatchers.IO) {
        require(idPetugas.isNotBlank()) { "id_petugas tidak boleh kosong" }
        require(pin.isNotBlank()) { "PIN tidak boleh kosong" }

        val payload = JSONObject()
            .put("action", "login")
            .put("token", config.token)
            .put("id_petugas", idPetugas.trim())
            .put("pin", pin)

        val response = requireSuccessfulResponse(
            postJson(config.endpointUrl, payload)
        )
        val responseId = response.optString("id_petugas", "").trim()
        if (responseId.isEmpty()) {
            throw AppsScriptApiException(
                message = "Respons login tidak memiliki id_petugas",
                errorCode = "RESPONS_TIDAK_VALID",
                retryable = true
            )
        }

        LoginApiResponse(
            idPetugas = responseId,
            nama = response.optString("nama", "").trim(),
            kodeKabupaten = response.optString("kode_kabupaten", "")
                .ifBlank { response.optString("kode_kab", "") }
                .trim(),
            kabupaten = response.optString("kabupaten", "").trim(),
            waktuServer = response.optString("waktu_server", "")
                .takeIf { it.isNotBlank() }
        )
    }

    suspend fun sync(
        config: SyncConfig,
        idPetugas: String,
        records: List<SyncPayloadRecord>
    ): RemoteSyncResponse = withContext(Dispatchers.IO) {
        require(records.isNotEmpty()) { "Batch sync tidak boleh kosong" }
        require(records.size <= MAX_BATCH_SIZE) {
            "Batch sync maksimal $MAX_BATCH_SIZE record"
        }

        val recordArray = JSONArray()
        records.forEach { payloadRecord ->
            recordArray.put(payloadRecord.toJson())
        }

        val payload = JSONObject()
            .put("action", "sync")
            .put("token", config.token)
            .put("id_petugas", idPetugas)
            .put("records", recordArray)

        val response = requireSuccessfulResponse(
            postJson(config.endpointUrl, payload)
        )
        val resultArray = response.optJSONArray("hasil")
            ?: throw AppsScriptApiException(
                message = "Respons sync tidak memiliki array hasil",
                errorCode = "RESPONS_TIDAK_VALID",
                retryable = true
            )

        val results = ArrayList<RemoteSyncItemResult>(resultArray.length())
        for (index in 0 until resultArray.length()) {
            val item = resultArray.optJSONObject(index)
                ?: throw AppsScriptApiException(
                    message = "Item hasil sync tidak valid pada index $index",
                    errorCode = "RESPONS_TIDAK_VALID",
                    retryable = true
                )
            val idRecord = item.optString("id_record", "").trim()
            val status = item.optString("status", "").trim().uppercase()
            if (idRecord.isEmpty() || status !in VALID_STATUSES) {
                throw AppsScriptApiException(
                    message = "Hasil sync memiliki id_record/status tidak valid",
                    errorCode = "RESPONS_TIDAK_VALID",
                    retryable = true
                )
            }

            results += RemoteSyncItemResult(
                idRecord = idRecord,
                status = status,
                message = item.optString("pesan", "")
                    .takeIf { it.isNotBlank() }
                    ?: item.optString("message", "")
                        .takeIf { it.isNotBlank() }
            )
        }

        RemoteSyncResponse(results = results)
    }

    private fun SyncPayloadRecord.toJson(): JSONObject {
        val recordJson = JSONObject()
            .put("id_record", record.idRecord)
            .put("id_petugas", record.idPetugas)
            .put("kode_kab", record.kodeKab)
            .put("kabupaten", record.kabupaten)
            .put("kode_kec", record.kodeKec)
            .put("nama_kec", record.namaKec)
            .put("kode_desa", record.kodeDesa)
            .put("nama_desa", record.namaDesa)
            .put("kode_sls", record.kodeSls)
            .put("nama_sls", record.namaSls)
            .put("jenis_objek", record.jenisObjek)
            .put("nama_objek", record.namaObjek)
            .put("alamat", record.alamat)
            .put("status_pendataan", record.statusPendataan)
            .put("catatan", record.catatan)
            .put("is_mock", record.isMock)
            .put("versi_app", record.versiApp)

        record.latitude?.let { recordJson.put("latitude", it) }
        record.longitude?.let { recordJson.put("longitude", it) }
        record.akurasiM?.let { recordJson.put("akurasi_m", it) }
        record.waktuPendataan?.let { recordJson.put("waktu_pendataan", it) }

        val photoArray = JSONArray()
        photos.forEach { photo ->
            photoArray.put(
                JSONObject()
                    .put("id_foto", photo.idFoto)
                    .put("drive_file_id", photo.driveFileId)
                    .put("urutan", photo.urutan)
                    .apply {
                        photo.url?.let { put("url", it) }
                    }
            )
        }
        recordJson.put("foto", photoArray)
        return recordJson
    }

    private fun postJson(endpointUrl: String, payload: JSONObject): JSONObject {
        val connection = openConnection(endpointUrl)
        return try {
            val requestBytes = payload.toString().toByteArray(StandardCharsets.UTF_8)
            connection.outputStream.use { output ->
                output.write(requestBytes)
            }

            val responseCode = connection.responseCode
            val body = readResponseBody(connection, responseCode)
            if (responseCode !in HTTP_SUCCESS..HTTP_SUCCESS_MAX) {
                throw AppsScriptApiException(
                    message = "Apps Script HTTP $responseCode" +
                        body.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty(),
                    httpCode = responseCode,
                    retryable = responseCode == HTTP_TIMEOUT ||
                        responseCode == HTTP_TOO_MANY_REQUESTS ||
                        responseCode >= HTTP_SERVER_ERROR
                )
            }

            try {
                JSONObject(body)
            } catch (error: Exception) {
                throw AppsScriptApiException(
                    message = "Respons Apps Script bukan JSON yang valid",
                    errorCode = "RESPONS_TIDAK_VALID",
                    retryable = true,
                    cause = error
                )
            }
        } catch (error: AppsScriptApiException) {
            throw error
        } catch (error: IOException) {
            throw AppsScriptApiException(
                message = "Gagal terhubung ke Apps Script: " +
                    (error.message ?: "I/O error"),
                errorCode = "NETWORK_ERROR",
                retryable = true,
                cause = error
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun getJson(
        endpointUrl: String,
        parameters: Map<String, String>
    ): JSONObject {
        val uri = try {
            Uri.parse(endpointUrl).buildUpon().apply {
                parameters.forEach { (key, value) ->
                    appendQueryParameter(key, value)
                }
            }.build().toString()
        } catch (error: Exception) {
            throw AppsScriptApiException(
                message = "Endpoint Apps Script tidak valid",
                errorCode = "ENDPOINT_TIDAK_VALID",
                retryable = false,
                cause = error
            )
        }
        val connection = openGetConnection(uri)
        return try {
            val responseCode = connection.responseCode
            val body = readResponseBody(connection, responseCode)
            if (responseCode !in HTTP_SUCCESS..HTTP_SUCCESS_MAX) {
                throw AppsScriptApiException(
                    message = "Apps Script HTTP $responseCode" +
                        body.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty(),
                    httpCode = responseCode,
                    retryable = responseCode == HTTP_TIMEOUT ||
                        responseCode == HTTP_TOO_MANY_REQUESTS ||
                        responseCode >= HTTP_SERVER_ERROR
                )
            }
            try {
                JSONObject(body)
            } catch (error: Exception) {
                throw AppsScriptApiException(
                    message = "Respons Apps Script bukan JSON yang valid",
                    errorCode = "RESPONS_TIDAK_VALID",
                    retryable = true,
                    cause = error
                )
            }
        } catch (error: AppsScriptApiException) {
            throw error
        } catch (error: IOException) {
            throw AppsScriptApiException(
                message = "Gagal terhubung ke Apps Script: " +
                    (error.message ?: "I/O error"),
                errorCode = "NETWORK_ERROR",
                retryable = true,
                cause = error
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(endpointUrl: String): HttpURLConnection {
        val url = try {
            URL(endpointUrl)
        } catch (error: Exception) {
            throw AppsScriptApiException(
                message = "Endpoint Apps Script tidak valid",
                errorCode = "ENDPOINT_TIDAK_VALID",
                retryable = false,
                cause = error
            )
        }

        if (!url.protocol.equals("https", ignoreCase = true)) {
            throw AppsScriptApiException(
                message = "Endpoint Apps Script wajib menggunakan HTTPS",
                errorCode = "ENDPOINT_TIDAK_AMAN",
                retryable = false
            )
        }

        return try {
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                doInput = true
                doOutput = true
                useCaches = false
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        } catch (error: IOException) {
            throw AppsScriptApiException(
                message = "Gagal membuka koneksi ke Apps Script: " +
                    (error.message ?: "I/O error"),
                errorCode = "NETWORK_ERROR",
                retryable = true,
                cause = error
            )
        }
    }

    private fun openGetConnection(endpointUrl: String): HttpURLConnection {
        val url = try {
            URL(endpointUrl)
        } catch (error: Exception) {
            throw AppsScriptApiException(
                message = "Endpoint Apps Script tidak valid",
                errorCode = "ENDPOINT_TIDAK_VALID",
                retryable = false,
                cause = error
            )
        }

        if (!url.protocol.equals("https", ignoreCase = true)) {
            throw AppsScriptApiException(
                message = "Endpoint Apps Script wajib menggunakan HTTPS",
                errorCode = "ENDPOINT_TIDAK_AMAN",
                retryable = false
            )
        }

        return try {
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                doInput = true
                useCaches = false
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
            }
        } catch (error: IOException) {
            throw AppsScriptApiException(
                message = "Gagal membuka koneksi ke Apps Script: " +
                    (error.message ?: "I/O error"),
                errorCode = "NETWORK_ERROR",
                retryable = true,
                cause = error
            )
        }
    }

    private fun readResponseBody(
        connection: HttpURLConnection,
        responseCode: Int
    ): String {
        val stream = if (responseCode in HTTP_SUCCESS..HTTP_SUCCESS_MAX) {
            connection.inputStream
        } else {
            connection.errorStream
        } ?: return ""

        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    private fun requireSuccessfulResponse(response: JSONObject): JSONObject {
        if (response.optBoolean("ok", false)) {
            return response
        }

        val code = response.optString("error_code", "API_ERROR")
            .takeIf { it.isNotBlank() }
        val message = response.optString("message", "Apps Script menolak request")
        throw AppsScriptApiException(
            message = message,
            errorCode = code,
            retryable = code?.let { it in RETRYABLE_SERVER_ERRORS } == true
        )
    }

    private companion object {
        const val MAX_BATCH_SIZE = 20
        const val CONNECT_TIMEOUT_MILLIS = 20_000
        const val READ_TIMEOUT_MILLIS = 60_000
        const val HTTP_SUCCESS = 200
        const val HTTP_SUCCESS_MAX = 299
        const val HTTP_TIMEOUT = 408
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
        val VALID_STATUSES = setOf("TERKIRIM", "GAGAL")
        val RETRYABLE_SERVER_ERRORS = setOf(
            "SERVER_SIBUK",
            "INTERNAL_ERROR",
            "NETWORK_ERROR",
            "RESPONS_TIDAK_VALID"
        )
    }
}
