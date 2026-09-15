package com.fajriantomanungki.revisitapp.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class LoginCredentials(
    val endpointUrl: String,
    val token: String,
    val idPetugas: String,
    val pin: String
)

/** Login online F-01. PIN hanya dikirim ke Apps Script melalui HTTPS. */
@Composable
fun LoginScreen(
    onLogin: suspend (LoginCredentials) -> Result<String>
) {
    var endpointUrl by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var idPetugas by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var successMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (endpointUrl.isBlank() || token.isBlank() ||
            idPetugas.isBlank() || pin.isBlank()
        ) {
            errorMessage = "Endpoint, token, kode petugas, dan PIN wajib diisi."
            return
        }
        isLoading = true
        errorMessage = null
        successMessage = null
        scope.launch {
            val result = try {
                onLogin(
                    LoginCredentials(
                        endpointUrl = endpointUrl.trim(),
                        token = token.trim(),
                        idPetugas = idPetugas.trim(),
                        pin = pin
                    )
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Result.failure(error)
            }
            isLoading = false
            result.fold(
                onSuccess = { successMessage = it },
                onFailure = { error ->
                    errorMessage = error.message ?: "Login gagal."
                }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "RevisitApp",
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = "Login petugas. Setelah login berhasil, identitas dan PIN " +
                "disimpan secara aman untuk penggunaan offline."
        )
        OutlinedTextField(
            value = endpointUrl,
            onValueChange = { endpointUrl = it },
            label = { Text("URL Apps Script /exec") },
            supportingText = { Text("Wajib HTTPS") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Token API") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = idPetugas,
            onValueChange = { idPetugas = it.take(100) },
            label = { Text("Kode petugas") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.take(100) },
            label = { Text("PIN / password") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = ::submit,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isLoading) "Memvalidasi..." else "Login")
        }
        errorMessage?.let {
            Card(
                modifier = Modifier.fillMaxWidth(),
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
        successMessage?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
