package br.com.porteirinho.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import br.com.porteirinho.BuildConfig
import br.com.porteirinho.PorteirinhoApplication
import br.com.porteirinho.data.local.OutboxEventEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class OutboxSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_PUBLISHABLE_KEY.isBlank()) return Result.success()

        val app = applicationContext as PorteirinhoApplication
        val database = app.container.database
        val outbox = database.outboxDao()
        val remote = app.container.remoteSyncClient
        val credentials = remote.backendCredentials() ?: return Result.success()

        var shouldRetry = false
        for (event in outbox.pendingBatch(BatchSize)) {
            val now = System.currentTimeMillis()
            val result = when (event.eventType) {
                "SHIFT_STARTED", "PATROL_STARTED", "CHECKPOINT_VISITED", "PATROL_FINISHED", "SHIFT_ENDED" -> {
                    val body = runCatching { remote.offlineRequest(event) }.getOrNull()
                    if (body == null) SendResult.RetryableFailure("Evento ainda aguarda o evento pai local.")
                    else send("offline-ingest-v2", body.toString(), credentials)
                }
                "OCCURRENCE_RECORDED" -> sendOccurrence(event, credentials, database)
                // Estes eventos são reflexos locais. No backend atual, alertas são criados pelas RPCs/Edge Functions.
                "ALERT_CREATED" -> SendResult.Accepted
                // Cadastros administrativos são enviados diretamente pelos endpoints admin autenticados.
                "GATEKEEPER_CREATED", "PIN_CHANGED", "SCHEDULE_UPDATED", "CHECKPOINT_CREATED", "QR_REPLACED" -> SendResult.Accepted
                else -> SendResult.PermanentFailure("Tipo de evento não suportado: ${event.eventType}")
            }

            when (result) {
                SendResult.Accepted -> outbox.markSynced(event.eventId, now)
                is SendResult.PermanentFailure -> {
                    outbox.markPermanentFailure(event.eventId, now, result.message.take(MaxErrorLength))
                    createSyncAlert(event, result.message)
                }
                is SendResult.RetryableFailure -> {
                    outbox.markRetry(event.eventId, now, result.message.take(MaxErrorLength))
                    shouldRetry = true
                }
            }
        }

        remote.pullSnapshot().onFailure { shouldRetry = true }
        return when {
            shouldRetry -> Result.retry()
            outbox.pendingBatch(1).isNotEmpty() -> Result.retry()
            else -> Result.success()
        }
    }

    private suspend fun sendOccurrence(
        event: OutboxEventEntity,
        credentials: BackendDeviceCredentials.Credentials,
        database: br.com.porteirinho.data.local.AppDatabase,
    ): SendResult {
        val raw = JSONObject(event.payloadJson)
        val execution = database.patrolDao().findExecution(event.aggregateId) ?: return SendResult.RetryableFailure("Ronda local não encontrada.")
        val runEvent = database.outboxDao().findEvent(execution.id, "PATROL_STARTED") ?: return SendResult.RetryableFailure("Início da ronda ainda não foi sincronizado.")
        val body = JSONObject()
            .put("client_event_id", event.eventId)
            .put("guard_id", execution.userId)
            .put("run_client_event_id", runEvent.eventId)
            .put("description", raw.optString("description"))
            .put("captured_at_local", java.time.Instant.ofEpochMilli(raw.optLong("created_at", event.createdAtEpochMillis)).toString())
        return send("guard-occurrence", body.toString(), credentials)
    }

    private suspend fun send(
        functionName: String,
        body: String,
        credentials: BackendDeviceCredentials.Credentials,
    ): SendResult = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = BuildConfig.SUPABASE_URL.trimEnd('/') + "/functions/v1/$functionName"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 25_000
                doInput = true
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
                setRequestProperty("X-Device-Id", credentials.deviceId)
                setRequestProperty("X-Device-Secret", credentials.secret)
            }
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            val code = connection.responseCode
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            when {
                code in 200..299 -> SendResult.Accepted
                code == 408 || code == 425 || code == 429 || code >= 500 || response.contains("\"retryable\":true") -> SendResult.RetryableFailure("HTTP $code: $response")
                else -> SendResult.PermanentFailure("HTTP $code: $response")
            }
        }.getOrElse { SendResult.RetryableFailure(it.message ?: it::class.java.simpleName) }
    }

    private suspend fun createSyncAlert(event: OutboxEventEntity, message: String) {
        val database = (applicationContext as PorteirinhoApplication).container.database
        database.alertDao().insert(
            br.com.porteirinho.data.local.AlertEntity(
                id = java.util.UUID.randomUUID().toString(),
                type = "SYNC_PERMANENT_FAILURE",
                description = "Evento ${event.eventId} requer análise: ${message.take(180)}",
                createdAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    private sealed interface SendResult {
        data object Accepted : SendResult
        data class RetryableFailure(val message: String) : SendResult
        data class PermanentFailure(val message: String) : SendResult
    }

    private companion object {
        const val BatchSize = 50
        const val MaxErrorLength = 500
    }
}
