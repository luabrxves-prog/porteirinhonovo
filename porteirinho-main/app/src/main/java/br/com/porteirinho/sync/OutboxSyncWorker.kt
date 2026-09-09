package br.com.porteirinho.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import br.com.porteirinho.BuildConfig
import br.com.porteirinho.PorteirinhoApplication
import br.com.porteirinho.data.local.OutboxEventEntity
import br.com.porteirinho.domain.DeviceIdentity
import br.com.porteirinho.security.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class OutboxSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_PUBLISHABLE_KEY.isBlank()) {
            return Result.success()
        }

        val outbox = (applicationContext as PorteirinhoApplication).container.database.outboxDao()
        val deviceId = DeviceIdentity(applicationContext).publicId
        val deviceToken = SecureStore(applicationContext).get("device_api_token")
            ?.toString(Charsets.UTF_8)
            ?.takeIf(String::isNotBlank)
            ?: return Result.success()
        val batch = outbox.pendingBatch(BatchSize)
        if (batch.isEmpty()) return Result.success()

        var shouldRetry = false
        for (event in batch) {
            val now = System.currentTimeMillis()
            when (val response = send(event, deviceId, deviceToken)) {
                is SendResult.Accepted -> outbox.markSynced(event.eventId, now)
                is SendResult.PermanentFailure -> {
                    outbox.markPermanentFailure(event.eventId, now, response.message.take(MaxErrorLength))
                    createSyncAlert(event, response.message)
                }
                is SendResult.RetryableFailure -> {
                    outbox.markRetry(event.eventId, now, response.message.take(MaxErrorLength))
                    shouldRetry = true
                }
            }
        }
        return when {
            shouldRetry -> Result.retry()
            outbox.pendingBatch(1).isNotEmpty() -> Result.retry()
            else -> Result.success()
        }
    }

    private suspend fun send(event: OutboxEventEntity, deviceId: String, deviceToken: String): SendResult = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = BuildConfig.SUPABASE_URL.trimEnd('/') + "/functions/v1/ingest-events"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
                setRequestProperty("Authorization", "Bearer ${BuildConfig.SUPABASE_PUBLISHABLE_KEY}")
                setRequestProperty("Idempotency-Key", event.eventId)
                setRequestProperty("X-Device-Id", deviceId)
                setRequestProperty("X-Device-Token", deviceToken)
            }
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(event.asRequestBody()) }
            val code = connection.responseCode
            val responseText = runCatching {
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
            connection.disconnect()

            when {
                code in 200..299 || code == 409 -> SendResult.Accepted
                code == 408 || code == 425 || code == 429 || code >= 500 -> SendResult.RetryableFailure("HTTP $code: $responseText")
                else -> SendResult.PermanentFailure("HTTP $code: $responseText")
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

    private fun OutboxEventEntity.asRequestBody(): String = buildString {
        append("{\"event_id\":\"").append(eventId).append("\",")
        append("\"aggregate_type\":\"").append(aggregateType).append("\",")
        append("\"aggregate_id\":\"").append(aggregateId).append("\",")
        append("\"event_type\":\"").append(eventType).append("\",")
        append("\"created_at_device\":").append(createdAtEpochMillis).append(',')
        append("\"payload\":").append(payloadJson).append('}')
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
