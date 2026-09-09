package br.com.porteirinho.sync

import android.content.Context
import br.com.porteirinho.BuildConfig
import br.com.porteirinho.domain.DeviceIdentity
import br.com.porteirinho.security.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class IncrementalSyncCoordinator(
    private val context: Context,
    private val remoteSyncClient: RemoteSyncClient,
    private val deviceIdentity: DeviceIdentity = DeviceIdentity(context),
) {
    suspend fun pullIfChanged(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (BuildConfig.SUPABASE_URL.isBlank()) return@runCatching
            val token = SecureStore(context).get("device_api_token")
                ?.toString(Charsets.UTF_8)
                ?.takeIf(String::isNotBlank)
                ?: return@runCatching

            val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            val cursor = preferences.getLong(CursorKey, 0L)

            val endpoint = BuildConfig.SUPABASE_URL.trimEnd('/') + "/functions/v1/sync-cursor?since=$cursor"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                doInput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
                setRequestProperty("Authorization", "Bearer ${BuildConfig.SUPABASE_PUBLISHABLE_KEY}")
                setRequestProperty("X-Device-Id", deviceIdentity.publicId)
                setRequestProperty("X-Device-Token", token)
                setRequestProperty("X-App-Version", BuildConfig.VERSION_NAME)
                setRequestProperty("X-Database-Version", DatabaseVersion.toString())
                setRequestProperty("X-Sync-Protocol-Version", ProtocolVersion.toString())
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()

            if (code !in 200..299) {
                remoteSyncClient.pullSnapshot().getOrThrow()
                return@runCatching
            }

            val payload = JSONObject(response)
            val changed = payload.optBoolean("changed", true)
            val currentCursor = payload.optLong("current_cursor", cursor)

            if (changed || cursor == 0L) {
                remoteSyncClient.pullSnapshot().getOrThrow()
            }

            preferences.edit().putLong(CursorKey, currentCursor).apply()
        }
    }

    private companion object {
        const val PreferencesName = "porteirinho_sync"
        const val CursorKey = "server_change_cursor"
        const val DatabaseVersion = 2
        const val ProtocolVersion = 2
    }
}
