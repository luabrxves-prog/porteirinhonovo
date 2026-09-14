package br.com.porteirinho.sync

import android.content.Context
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import br.com.porteirinho.BuildConfig
import br.com.porteirinho.PorteirinhoApplication
import br.com.porteirinho.data.local.AlertEntity
import br.com.porteirinho.data.local.AppDatabase
import br.com.porteirinho.data.local.CheckpointEntity
import br.com.porteirinho.data.local.LocationNodeEntity
import br.com.porteirinho.data.local.OutboxEventEntity
import br.com.porteirinho.data.local.PatrolScheduleEntity
import br.com.porteirinho.data.local.QrCredentialEntity
import br.com.porteirinho.data.local.ScheduleCheckpointEntity
import br.com.porteirinho.domain.DeviceIdentity
import br.com.porteirinho.domain.FixedPatrolStructure
import br.com.porteirinho.security.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

class OutboxSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_PUBLISHABLE_KEY.isBlank()) {
            return Result.success()
        }

        val database = (applicationContext as PorteirinhoApplication).container.database
        val outbox = database.outboxDao()
        val deviceId = DeviceIdentity(applicationContext).publicId
        val deviceToken = SecureStore(applicationContext).get("device_api_token")
            ?.toString(Charsets.UTF_8)
            ?.takeIf(String::isNotBlank)
            ?: return Result.success()
        val batch = outbox.pendingBatch(BatchSize)

        var shouldRetry = false
        for (event in batch) {
            val now = System.currentTimeMillis()
            when (val response = send(event, deviceId, deviceToken)) {
                is SendResult.Accepted -> outbox.markSynced(event.eventId, now)
                is SendResult.PermanentFailure -> {
                    outbox.markPermanentFailure(event.eventId, now, response.message.take(MaxErrorLength))
                    createSyncAlert(database, event, response.message)
                }
                is SendResult.RetryableFailure -> {
                    outbox.markRetry(event.eventId, now, response.message.take(MaxErrorLength))
                    shouldRetry = true
                }
            }
        }

        when (val pull = pullConfiguration(database, deviceId, deviceToken)) {
            is PullResult.Accepted -> Unit
            is PullResult.RetryableFailure -> shouldRetry = true
            is PullResult.PermanentFailure -> createConfigurationSyncAlert(database, pull.message)
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
            val responseText = readResponse(connection, code)
            connection.disconnect()

            when {
                code in 200..299 || code == 409 -> SendResult.Accepted
                code == 408 || code == 425 || code == 429 || code >= 500 -> SendResult.RetryableFailure("HTTP $code: $responseText")
                else -> SendResult.PermanentFailure("HTTP $code: $responseText")
            }
        }.getOrElse { SendResult.RetryableFailure(it.message ?: it::class.java.simpleName) }
    }

    private suspend fun pullConfiguration(
        database: AppDatabase,
        deviceId: String,
        deviceToken: String,
    ): PullResult = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = BuildConfig.SUPABASE_URL.trimEnd('/') + "/functions/v1/config-snapshot"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
                setRequestProperty("Authorization", "Bearer ${BuildConfig.SUPABASE_PUBLISHABLE_KEY}")
                setRequestProperty("X-Device-Id", deviceId)
                setRequestProperty("X-Device-Token", deviceToken)
            }
            val code = connection.responseCode
            val responseText = readResponse(connection, code)
            connection.disconnect()

            when {
                code in 200..299 -> {
                    applyConfigurationSnapshot(database, JSONObject(responseText))
                    PullResult.Accepted
                }
                code == 408 || code == 425 || code == 429 || code >= 500 -> PullResult.RetryableFailure("HTTP $code: $responseText")
                else -> PullResult.PermanentFailure("HTTP $code: $responseText")
            }
        }.getOrElse { PullResult.RetryableFailure(it.message ?: it::class.java.simpleName) }
    }

    private suspend fun applyConfigurationSnapshot(database: AppDatabase, snapshot: JSONObject) {
        val directoryDao = database.directoryDao()
        val scheduleDao = database.scheduleDao()
        val now = System.currentTimeMillis()

        database.withTransaction {
            val locations = snapshot.optJSONArray("locations")
            if (locations != null) {
                for (index in 0 until locations.length()) {
                    val item = locations.getJSONObject(index)
                    directoryDao.upsertLocation(
                        LocationNodeEntity(
                            id = item.getString("id"),
                            parentId = item.optNullableString("parent_id"),
                            type = item.getString("kind"),
                            name = item.getString("name"),
                            active = item.optBoolean("active", true),
                            archivedAtEpochMillis = item.optEpochMillis("archived_at"),
                            updatedAtEpochMillis = item.optEpochMillis("updated_at") ?: now,
                        ),
                    )
                }
            }

            val checkpoints = snapshot.optJSONArray("checkpoints")
            if (checkpoints != null) {
                for (index in 0 until checkpoints.length()) {
                    val item = checkpoints.getJSONObject(index)
                    directoryDao.upsertCheckpoint(
                        CheckpointEntity(
                            id = item.getString("id"),
                            locationNodeId = item.getString("location_node_id"),
                            name = item.getString("name"),
                            description = item.optNullableString("description"),
                            sequenceHint = item.optInt("sequence_hint", 0),
                            minimumTravelSecondsFromPrevious = item.optInt("minimum_travel_seconds_from_previous", 0),
                            active = item.optBoolean("active", true),
                            archivedAtEpochMillis = item.optEpochMillis("archived_at"),
                            updatedAtEpochMillis = item.optEpochMillis("updated_at") ?: now,
                        ),
                    )
                }
            }

            val qrCredentials = snapshot.optJSONArray("qr_credentials")
            if (qrCredentials != null) {
                for (index in 0 until qrCredentials.length()) {
                    val item = qrCredentials.getJSONObject(index)
                    val checkpointId = item.getString("checkpoint_id")
                    directoryDao.revokeActiveQr(checkpointId, now)
                    directoryDao.upsertQrCredential(
                        QrCredentialEntity(
                            id = item.getString("id"),
                            checkpointId = checkpointId,
                            tokenHash = item.getString("token_hash"),
                            version = item.getInt("version"),
                            status = item.optString("status", "ACTIVE"),
                            issuedAtEpochMillis = item.optEpochMillis("issued_at") ?: now,
                            revokedAtEpochMillis = item.optEpochMillis("revoked_at"),
                        ),
                    )
                }
            }

            val schedules = snapshot.optJSONArray("schedules")
            if (schedules != null) {
                for (index in 0 until schedules.length()) {
                    val item = schedules.getJSONObject(index)
                    val weekdays = item.getJSONArray("weekdays")
                    val weekdaysCsv = buildList {
                        for (dayIndex in 0 until weekdays.length()) add(weekdays.getInt(dayIndex).toString())
                    }.joinToString(",")
                    scheduleDao.upsertSchedule(
                        PatrolScheduleEntity(
                            id = item.getString("id"),
                            propertyId = item.getString("property_id"),
                            name = item.getString("name"),
                            weekdaysCsv = weekdaysCsv,
                            startMinuteOfDay = item.getString("start_time").toMinuteOfDay(),
                            endMinuteOfDay = item.getString("end_time").toMinuteOfDay(),
                            toleranceMinutes = item.getInt("tolerance_minutes"),
                            active = item.optBoolean("active", true),
                            archivedAtEpochMillis = item.optEpochMillis("archived_at"),
                            updatedAtEpochMillis = item.optEpochMillis("updated_at") ?: now,
                        ),
                    )
                }
            }

            FixedPatrolStructure.scheduleIds.forEach { scheduleDao.deleteCheckpointLinks(it) }
            val links = snapshot.optJSONArray("schedule_checkpoints")
            if (links != null) {
                for (index in 0 until links.length()) {
                    val item = links.getJSONObject(index)
                    scheduleDao.upsertCheckpointLink(
                        ScheduleCheckpointEntity(
                            scheduleId = item.getString("schedule_id"),
                            checkpointId = item.getString("checkpoint_id"),
                            sequence = item.getInt("sequence"),
                        ),
                    )
                }
            }
        }
    }

    private suspend fun createSyncAlert(database: AppDatabase, event: OutboxEventEntity, message: String) {
        database.alertDao().insert(
            AlertEntity(
                id = UUID.randomUUID().toString(),
                type = "SYNC_PERMANENT_FAILURE",
                description = "Evento ${event.eventId} requer análise: ${message.take(180)}",
                createdAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun createConfigurationSyncAlert(database: AppDatabase, message: String) {
        database.alertDao().insert(
            AlertEntity(
                id = UUID.randomUUID().toString(),
                type = "CONFIG_SYNC_FAILURE",
                description = "A configuração de rondas não pôde ser atualizada: ${message.take(180)}",
                createdAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    private fun readResponse(connection: HttpURLConnection, code: Int): String = runCatching {
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }.getOrDefault("")

    private fun OutboxEventEntity.asRequestBody(): String = buildString {
        append("{\"event_id\":\"").append(eventId).append("\",")
        append("\"aggregate_type\":\"").append(aggregateType).append("\",")
        append("\"aggregate_id\":\"").append(aggregateId).append("\",")
        append("\"event_type\":\"").append(eventType).append("\",")
        append("\"created_at_device\":").append(createdAtEpochMillis).append(',')
        append("\"payload\":").append(payloadJson).append('}')
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.optEpochMillis(key: String): Long? =
        optNullableString(key)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private fun String.toMinuteOfDay(): Int {
        val time = LocalTime.parse(this)
        return time.hour * 60 + time.minute
    }

    private sealed interface SendResult {
        data object Accepted : SendResult
        data class RetryableFailure(val message: String) : SendResult
        data class PermanentFailure(val message: String) : SendResult
    }

    private sealed interface PullResult {
        data object Accepted : PullResult
        data class RetryableFailure(val message: String) : PullResult
        data class PermanentFailure(val message: String) : PullResult
    }

    private companion object {
        const val BatchSize = 50
        const val MaxErrorLength = 500
    }
}
