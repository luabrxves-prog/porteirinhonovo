package br.com.porteirinho.sync

import android.content.Context
import br.com.porteirinho.BuildConfig
import br.com.porteirinho.domain.DeviceIdentity
import br.com.porteirinho.security.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class AdminRemoteClient(
    private val context: Context,
    private val deviceIdentity: DeviceIdentity = DeviceIdentity(context),
) {
    data class QrPayload(
        val checkpointId: String,
        val credentialId: String,
        val version: Int,
        val rawPayload: String,
    )

    suspend fun getQr(checkpointId: String): Result<QrPayload> = qrOperation("get", checkpointId)

    suspend fun replaceQr(checkpointId: String): Result<QrPayload> = qrOperation("replace", checkpointId)

    suspend fun downloadReport(days: Int): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(days in setOf(30, 90, 120)) { "Período inválido." }
            check(BuildConfig.SUPABASE_URL.isNotBlank()) { "Relatórios precisam do servidor configurado." }
            val token = deviceToken() ?: error("Este aparelho ainda não foi provisionado para sincronização.")
            val endpoint = BuildConfig.SUPABASE_URL.trimEnd('/') + "/functions/v1/admin-report?days=$days"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 40_000
                doInput = true
                setHeaders(token)
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()
                error("Não foi possível gerar o relatório. HTTP $code ${error.take(160)}")
            }
            val file = File(context.cacheDir, "porteirinho-rondas-$days-dias.xlsx")
            connection.inputStream.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            connection.disconnect()
            file
        }
    }

    private suspend fun qrOperation(action: String, checkpointId: String): Result<QrPayload> = withContext(Dispatchers.IO) {
        runCatching {
            check(BuildConfig.SUPABASE_URL.isNotBlank()) { "QR administrativo precisa do servidor configurado." }
            val token = deviceToken() ?: error("Este aparelho ainda não foi provisionado para sincronização.")
            val endpoint = BuildConfig.SUPABASE_URL.trimEnd('/') + "/functions/v1/admin-qr"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 20_000
                doInput = true
                doOutput = true
                setHeaders(token)
            }
            val requestBody = JSONObject()
                .put("action", action)
                .put("checkpoint_id", checkpointId)
                .toString()
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(requestBody) }
            val code = connection.responseCode
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            check(code in 200..299) { "Não foi possível acessar o QR Code. HTTP $code ${response.take(160)}" }
            val json = JSONObject(response)
            QrPayload(
                checkpointId = json.getString("checkpoint_id"),
                credentialId = json.getString("qr_credential_id"),
                version = json.getInt("version"),
                rawPayload = json.getString("raw_payload"),
            )
        }
    }

    private fun HttpURLConnection.setHeaders(token: String) {
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
        setRequestProperty("Authorization", "Bearer ${BuildConfig.SUPABASE_PUBLISHABLE_KEY}")
        setRequestProperty("X-Device-Id", deviceIdentity.publicId)
        setRequestProperty("X-Device-Token", token)
    }

    private fun deviceToken(): String? = SecureStore(context).get("device_api_token")
        ?.toString(Charsets.UTF_8)
        ?.takeIf(String::isNotBlank)
}
