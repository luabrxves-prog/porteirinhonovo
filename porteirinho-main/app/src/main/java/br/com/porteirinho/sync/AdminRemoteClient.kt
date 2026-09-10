package br.com.porteirinho.sync

import android.content.Context
import android.os.Build
import android.util.Base64
import br.com.porteirinho.BuildConfig
import br.com.porteirinho.domain.DeviceIdentity
import br.com.porteirinho.security.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class AdminRemoteClient(private val context: Context) {
    private val secureStore = SecureStore(context)
    private val backendDeviceCredentials = BackendDeviceCredentials(context)
    private val deviceIdentity = DeviceIdentity(context)

    data class QrPayload(
        val checkpointId: String,
        val credentialId: String,
        val version: Int,
        val rawPayload: String,
    )

    data class GatekeeperCreated(val id: String?, val name: String, val temporaryPin: String)

    fun requestSyncNow() = SyncScheduler.runNow(context)
    fun hasSession(): Boolean = accessToken() != null

    suspend fun login(email: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(email.isNotBlank() && password.isNotBlank()) { "Informe e-mail e senha." }
            val endpoint = BuildConfig.SUPABASE_URL.trimEnd('/') + "/auth/v1/token?grant_type=password"
            val response = request(endpoint, "POST", JSONObject().put("email", email.trim()).put("password", password).toString(), bearer = null)
            check(response.code in 200..299) { "E-mail ou senha inválidos." }
            val json = JSONObject(response.body)
            val role = json.optJSONObject("user")?.optJSONObject("app_metadata")?.optString("role").orEmpty()
            check(role == "admin") { "Este usuário não possui acesso administrativo." }
            saveSession(json.getString("access_token"), json.optString("refresh_token"))
            ensureDeviceProvisioned()
        }
    }

    fun logout() {
        secureStore.put(KeyAccessToken, ByteArray(0))
        secureStore.put(KeyRefreshToken, ByteArray(0))
    }

    suspend fun resolveAlert(alertId: String): Result<Unit> = runCatching {
        val response = postAdmin("admin-alert", JSONObject().put("alert_id", alertId))
        check(response.code in 200..299) { "Não foi possível resolver o alerta." }
    }

    suspend fun createPoint(name: String): Result<Unit> = runCatching {
        val response = postAdmin("admin-point", JSONObject().put("name", name.trim()))
        check(response.code in 200..299) {
            if (response.body.contains("PATROL_ACTIVE")) "Aguarde a ronda em andamento terminar para alterar os pontos."
            else "Não foi possível cadastrar o ponto."
        }
    }

    suspend fun getQr(checkpointId: String): Result<QrPayload> = runCatching {
        val response = postAdmin("admin-qr", JSONObject().put("action", "get_active").put("checkpoint_id", checkpointId))
        check(response.code in 200..299) { "Não foi possível carregar o QR Code." }
        val qr = JSONObject(response.body).optJSONObject("qr") ?: error("Este ponto ainda não possui QR Code ativo.")
        QrPayload(checkpointId, qr.getString("qr_token_id"), qr.optInt("version", 1), qr.getString("token_value"))
    }

    suspend fun replaceQr(checkpointId: String): Result<QrPayload> = runCatching {
        val response = postAdmin("admin-qr", JSONObject().put("action", "replace").put("checkpoint_id", checkpointId))
        check(response.code in 200..299) { "Não foi possível substituir o QR Code." }
        val json = JSONObject(response.body)
        QrPayload(checkpointId, json.getString("qr_token_id"), json.optInt("version", 1), json.getString("token_value"))
    }

    suspend fun createGatekeeper(name: String): Result<GatekeeperCreated> = runCatching {
        val response = postAdmin("admin-guards", JSONObject().put("action", "create").put("name", name.trim()))
        check(response.code in 200..299) { "Não foi possível cadastrar o porteiro." }
        val json = JSONObject(response.body)
        val guard = json.optJSONObject("guard")
        GatekeeperCreated(
            id = guard?.optString("guard_id")?.takeIf(String::isNotBlank),
            name = guard?.optString("guard_name")?.takeIf(String::isNotBlank) ?: name.trim(),
            temporaryPin = json.getString("temporary_pin"),
        )
    }

    suspend fun updateSchedule(scheduleId: String, name: String, startMinute: Int): Result<Unit> = runCatching {
        val response = postAdmin("admin-schedules", JSONObject().put("patrol_template_id", scheduleId).put("name", name.trim()).put("start_minute", startMinute))
        check(response.code in 200..299) { "Não foi possível atualizar a ronda." }
    }

    suspend fun downloadReport(days: Int): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(days in setOf(30, 90, 120)) { "Período inválido." }
            val response = postAdmin("admin-reports", JSONObject().put("days", days))
            check(response.code in 200..299) { "Não foi possível gerar o relatório." }
            val json = JSONObject(response.body)
            val fileName = json.optString("file_name").ifBlank { "Porteirinho_${days}d.xlsx" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val bytes = Base64.decode(json.getString("file_base64"), Base64.DEFAULT)
            File(context.cacheDir, fileName).also { it.writeBytes(bytes) }
        }
    }

    private suspend fun ensureDeviceProvisioned() {
        if (backendDeviceCredentials.get() != null) return
        val response = postAdmin(
            "admin-devices",
            JSONObject()
                .put("action", "provision_portaria_default")
                .put("installation_id", deviceIdentity.publicId)
                .put("name", "Porteirinho ${Build.MODEL}")
                .put("model", Build.MODEL)
                .put("android_version", Build.VERSION.RELEASE)
                .put("app_version", BuildConfig.VERSION_NAME),
        )
        check(response.code in 200..299) { "Não foi possível preparar este aparelho para o condomínio." }
        val json = JSONObject(response.body)
        val deviceId = json.optJSONObject("device")?.optString("device_id")?.takeIf(String::isNotBlank)
            ?: error("O servidor não retornou o identificador do aparelho.")
        val secret = json.optString("device_secret").takeIf(String::isNotBlank)
            ?: error("O servidor não retornou a credencial do aparelho.")
        backendDeviceCredentials.save(deviceId, secret)
    }

    private suspend fun postAdmin(functionName: String, body: JSONObject): HttpResult = withContext(Dispatchers.IO) {
        val endpoint = BuildConfig.SUPABASE_URL.trimEnd('/') + "/functions/v1/$functionName"
        var token = accessToken() ?: error("Entre novamente na Administração.")
        var response = request(endpoint, "POST", body.toString(), token)
        if (response.code == 401) {
            token = refreshAccessToken() ?: error("Sua sessão expirou. Entre novamente na Administração.")
            response = request(endpoint, "POST", body.toString(), token)
        }
        response
    }

    private fun refreshAccessToken(): String? {
        val refresh = refreshToken() ?: return null
        val endpoint = BuildConfig.SUPABASE_URL.trimEnd('/') + "/auth/v1/token?grant_type=refresh_token"
        val response = request(endpoint, "POST", JSONObject().put("refresh_token", refresh).toString(), bearer = null)
        if (response.code !in 200..299) return null
        val json = JSONObject(response.body)
        val access = json.optString("access_token").takeIf(String::isNotBlank) ?: return null
        saveSession(access, json.optString("refresh_token").ifBlank { refresh })
        return access
    }

    private fun request(endpoint: String, method: String, body: String, bearer: String?): HttpResult {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 45_000
            doInput = true
            doOutput = body.isNotEmpty()
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
            if (!bearer.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $bearer")
        }
        if (body.isNotEmpty()) connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
        val code = connection.responseCode
        val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        return HttpResult(code, text)
    }

    private fun saveSession(access: String, refresh: String) {
        secureStore.put(KeyAccessToken, access.toByteArray(Charsets.UTF_8))
        if (refresh.isNotBlank()) secureStore.put(KeyRefreshToken, refresh.toByteArray(Charsets.UTF_8))
    }

    private fun accessToken(): String? = secureStore.get(KeyAccessToken)?.toString(Charsets.UTF_8)?.takeIf(String::isNotBlank)
    private fun refreshToken(): String? = secureStore.get(KeyRefreshToken)?.toString(Charsets.UTF_8)?.takeIf(String::isNotBlank)

    private data class HttpResult(val code: Int, val body: String)

    private companion object {
        const val KeyAccessToken = "admin_access_token"
        const val KeyRefreshToken = "admin_refresh_token"
    }
}
