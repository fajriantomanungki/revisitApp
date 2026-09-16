package com.fajriantomanungki.revisitapp.data.sync

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.fajriantomanungki.revisitapp.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.inject.Inject

data class SyncConfig(
    val endpointUrl: String,
    val token: String,
    val idPetugas: String
)

data class SyncServerConfig(
    val endpointUrl: String,
    val token: String
)

data class CachedIdentity(
    val idPetugas: String,
    val nama: String,
    val kodeKabupaten: String,
    val kabupaten: String
)

/**
 * Menyimpan konfigurasi sinkronisasi. URL dan token dibaca dari BuildConfig
 * yang dibuat saat build dari local.properties, lalu disalin ke
 * EncryptedSharedPreferences setelah login. Token tidak dimasukkan ke input
 * WorkRequest karena Data WorkManager bersifat persisten.
 */
class SyncConfigStore @Inject constructor(
    @ApplicationContext context: Context
) {

    private val preferences: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFERENCES_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun save(
        endpointUrl: String,
        token: String,
        idPetugas: String
    ) {
        val normalizedEndpoint = endpointUrl.trim().removeSuffix("/")
        require(Uri.parse(normalizedEndpoint).scheme.equals("https", ignoreCase = true)) {
            "Endpoint Apps Script wajib menggunakan HTTPS"
        }
        require(token.isNotBlank()) { "Token API tidak boleh kosong" }
        require(idPetugas.isNotBlank()) { "id_petugas tidak boleh kosong" }

        preferences.edit()
            .putString(KEY_ENDPOINT_URL, normalizedEndpoint)
            .putString(KEY_TOKEN, token.trim())
            .putString(KEY_ID_PETUGAS, idPetugas.trim())
            .apply()
    }

    /**
     * Menyimpan sesi setelah login online. PIN hanya disimpan sebagai
     * SHA-256 sehingga login offline tidak membutuhkan PIN mentah di disk.
     */
    fun saveAuthenticated(
        endpointUrl: String,
        token: String,
        idPetugas: String,
        nama: String,
        pin: String,
        kodeKabupaten: String,
        kabupaten: String
    ) {
        require(pin.isNotBlank()) { "PIN tidak boleh kosong" }
        require(kodeKabupaten.isNotBlank()) {
            "kode_kabupaten tidak boleh kosong"
        }
        require(kabupaten.isNotBlank()) {
            "kabupaten tidak boleh kosong"
        }
        save(endpointUrl, token, idPetugas)
        preferences.edit()
            .putString(KEY_NAMA, nama.trim())
            .putString(KEY_KODE_KABUPATEN, kodeKabupaten.trim())
            .putString(KEY_KABUPATEN, kabupaten.trim())
            .putString(KEY_PIN_DIGEST, sha256(pin))
            .apply()
    }

    /**
     * Mengambil konfigurasi server tanpa memerlukan identitas petugas.
     * Nilai tersimpan diprioritaskan agar konfigurasi lama tetap kompatibel;
     * jika belum ada, gunakan nilai dari BuildConfig.
     */
    fun readServerConfig(): SyncServerConfig? {
        val endpointUrl = preferences.getString(KEY_ENDPOINT_URL, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: BuildConfig.APPS_SCRIPT_URL.trim()
                .takeIf { it.isNotEmpty() }
        val token = preferences.getString(KEY_TOKEN, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: BuildConfig.APPS_SCRIPT_TOKEN.trim()
                .takeIf { it.isNotEmpty() }

        if (endpointUrl == null || token == null) return null

        val normalizedEndpoint = endpointUrl.removeSuffix("/")
        if (!Uri.parse(normalizedEndpoint).scheme.equals("https", ignoreCase = true)) {
            return null
        }

        return SyncServerConfig(
            endpointUrl = normalizedEndpoint,
            token = token
        )
    }

    fun readCachedIdentity(): CachedIdentity? {
        val idPetugas = preferences.getString(KEY_ID_PETUGAS, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val nama = preferences.getString(KEY_NAMA, "")?.trim().orEmpty()
        val kodeKabupaten = preferences
            .getString(KEY_KODE_KABUPATEN, "")
            ?.trim()
            .orEmpty()
        val kabupaten = preferences
            .getString(KEY_KABUPATEN, "")
            ?.trim()
            .orEmpty()
        return CachedIdentity(
            idPetugas = idPetugas,
            nama = nama,
            kodeKabupaten = kodeKabupaten,
            kabupaten = kabupaten
        )
    }

    /** Memvalidasi sesi perangkat tanpa jaringan. */
    fun canLoginOffline(idPetugas: String, pin: String): Boolean {
        val storedId = preferences.getString(KEY_ID_PETUGAS, null)
            ?.trim()
            ?: return false
        val storedDigest = preferences.getString(KEY_PIN_DIGEST, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return false
        if (storedId != idPetugas.trim() || pin.isBlank()) return false

        return MessageDigest.isEqual(
            storedDigest.toByteArray(StandardCharsets.UTF_8),
            sha256(pin).toByteArray(StandardCharsets.UTF_8)
        )
    }

    fun read(): SyncConfig? {
        val serverConfig = readServerConfig() ?: return null
        val idPetugas = preferences.getString(KEY_ID_PETUGAS, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        return SyncConfig(
            endpointUrl = serverConfig.endpointUrl,
            token = serverConfig.token,
            idPetugas = idPetugas
        )
    }

    /** Menghapus sesi petugas, tetapi mempertahankan URL dan token server. */
    fun clearSession() {
        preferences.edit()
            .remove(KEY_ID_PETUGAS)
            .remove(KEY_NAMA)
            .remove(KEY_KODE_KABUPATEN)
            .remove(KEY_KABUPATEN)
            .remove(KEY_PIN_DIGEST)
            .apply()
    }

    /** Menghapus seluruh konfigurasi, termasuk konfigurasi server. */
    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private companion object {
        const val PREFERENCES_FILE = "sync_secure_preferences"
        const val KEY_ENDPOINT_URL = "endpoint_url"
        const val KEY_TOKEN = "api_token"
        const val KEY_ID_PETUGAS = "id_petugas"
        const val KEY_NAMA = "nama_petugas"
        const val KEY_KODE_KABUPATEN = "kode_kabupaten"
        const val KEY_KABUPATEN = "kabupaten"
        const val KEY_PIN_DIGEST = "pin_digest"
    }
}
