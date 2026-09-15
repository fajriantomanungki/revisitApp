package com.fajriantomanungki.revisitapp.data.sync

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

data class SyncConfig(
    val endpointUrl: String,
    val token: String,
    val idPetugas: String
)

/**
 * Menyimpan konfigurasi sinkronisasi. Token tidak dimasukkan ke input
 * WorkRequest karena Data WorkManager bersifat persisten; token hanya dibaca
 * saat worker berjalan dari EncryptedSharedPreferences.
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

    fun read(): SyncConfig? {
        val endpointUrl = preferences.getString(KEY_ENDPOINT_URL, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val token = preferences.getString(KEY_TOKEN, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val idPetugas = preferences.getString(KEY_ID_PETUGAS, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        return SyncConfig(
            endpointUrl = endpointUrl,
            token = token,
            idPetugas = idPetugas
        )
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val PREFERENCES_FILE = "sync_secure_preferences"
        const val KEY_ENDPOINT_URL = "endpoint_url"
        const val KEY_TOKEN = "api_token"
        const val KEY_ID_PETUGAS = "id_petugas"
    }
}
