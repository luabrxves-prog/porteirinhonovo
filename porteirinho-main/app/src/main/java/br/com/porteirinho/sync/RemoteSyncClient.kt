package br.com.porteirinho.sync

import android.content.Context
import androidx.room.withTransaction
import br.com.porteirinho.BuildConfig
import br.com.porteirinho.data.local.*
import br.com.porteirinho.domain.DeviceIdentity
import br.com.porteirinho.security.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class RemoteSyncClient(
    private val context: Context,
    private val database: AppDatabase,
    private val deviceIdentity: DeviceIdentity = DeviceIdentity(context),
) {
    sealed interface ReservationResult {
        data object Reserved : ReservationResult
        data object LocalDemo : ReservationResult
        data class Conflict(val message: String) : ReservationResult
        data class Unavailable(val message: String) : ReservationResult
    }

    suspend fun reservePatrol(scheduleId: String, userId: String, scheduledWindowStartEpochMillis: Long): ReservationResult = withContext(Dispatchers.IO) {
        if (BuildConfig.SUPABASE_URL.isBlank()) return@withContext ReservationResult.LocalDemo
        val token = deviceToken() ?: return@withContext ReservationResult.Unavailable("Este aparelho ainda não foi provisionado para sincronização.")

        runCatching {
            val connection = openConnection("reserve-patrol", "POST", token)
            val body = JSONObject()
                .put("schedule_id", scheduleId)
                .put("user_id", userId)
                .put("scheduled_window_start_ms", scheduledWindowStartEpochMillis)
                .toString()
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            val code = connection.responseCode
            val response = responseText(connection, code)
            connection.disconnect()
            when {
                code in 200..299 -> ReservationResult.Reserved
                code == 409 -> ReservationResult.Conflict(
                    runCatching { JSONObject(response).optString("message") }.getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?: "Esta ronda já foi iniciada ou concluída por outro porteiro.",
                )
                else -> ReservationResult.Unavailable("Não foi possível reservar a ronda no servidor. Verifique a internet e tente novamente.")
            }
        }.getOrElse {
            ReservationResult.Unavailable("É necessário estar conectado à internet para iniciar uma nova ronda.")
        }
    }

    suspend fun pullSnapshot(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (BuildConfig.SUPABASE_URL.isBlank()) return@runCatching
            val token = deviceToken() ?: return@runCatching
            val connection = openConnection("sync-snapshot", "GET", token)
            val code = connection.responseCode
            val response = responseText(connection, code)
            connection.disconnect()
            check(code in 200..299) { "Snapshot HTTP $code" }
            applySnapshot(JSONObject(response))
        }
    }

    suspend fun enrichedPayload(event: OutboxEventEntity): String {
        val payload = JSONObject(event.payloadJson)
        when (event.eventType) {
            "PATROL_STARTED" -> database.patrolDao().findExecution(event.aggregateId)?.let { execution ->
                payload.put("execution_id", execution.id)
                    .put("schedule_id", execution.scheduleId)
                    .put("shift_id", execution.shiftId)
                    .put("user_id", execution.userId)
                    .put("scheduled_window_start", execution.scheduledWindowStartEpochMillis)
                    .put("scheduled_window_end", execution.scheduledWindowEndEpochMillis)
                    .put("started_at", execution.startedAtEpochMillis)
            }
            "CHECKPOINT_VISITED" -> database.patrolDao().visits(event.aggregateId)
                .firstOrNull { payload.optString("visit_id") == it.id }
                ?.let { visit -> payload.put("suspicion_reason", visit.suspicionReason ?: JSONObject.NULL) }
            "ALERT_CREATED" -> database.alertDao().findById(event.aggregateId)?.let { alert ->
                payload.put("user_id", alert.userId ?: JSONObject.NULL)
                    .put("execution_id", alert.executionId ?: JSONObject.NULL)
                    .put("device_id", alert.deviceId ?: JSONObject.NULL)
            }
            "GATEKEEPER_CREATED", "PIN_CHANGED" -> database.userDao().findById(event.aggregateId)?.let { user ->
                payload.put("pin_salt_base64", user.pinSaltBase64)
                    .put("pin_hash_base64", user.pinHashBase64)
                    .put("pin_issued_at", user.pinIssuedAtEpochMillis ?: JSONObject.NULL)
                    .put("changed_at", user.pinChangedAtEpochMillis ?: JSONObject.NULL)
            }
            "SCHEDULE_UPDATED" -> database.scheduleDao().findById(event.aggregateId)?.let { schedule ->
                payload.put("name", schedule.name)
                    .put("start_minute", schedule.startMinuteOfDay)
                    .put("end_minute", schedule.endMinuteOfDay)
                    .put("start_tolerance_minutes", schedule.startToleranceMinutes)
                    .put("end_tolerance_minutes", schedule.endToleranceMinutes)
                    .put("target_duration_minutes", schedule.targetDurationMinutes)
            }
            "CHECKPOINT_CREATED" -> database.directoryDao().findCheckpoint(event.aggregateId)?.let { checkpoint ->
                payload.put("location_node_id", checkpoint.locationNodeId)
                    .put("description", checkpoint.description ?: JSONObject.NULL)
            }
            "QR_REPLACED" -> database.directoryDao().activeQrForCheckpoint(event.aggregateId)?.let { qr ->
                payload.put("qr_credential_id", qr.id)
                    .put("token_hash", qr.tokenHash)
                    .put("version", qr.version)
                    .put("issued_at", qr.issuedAtEpochMillis)
            }
        }
        return payload.toString()
    }

    private suspend fun applySnapshot(root: JSONObject) {
        val users = root.optJSONArray("users") ?: JSONArray()
        val locations = root.optJSONArray("locations") ?: JSONArray()
        val checkpoints = root.optJSONArray("checkpoints") ?: JSONArray()
        val qrCredentials = root.optJSONArray("qr_credentials") ?: JSONArray()
        val schedules = root.optJSONArray("schedules") ?: JSONArray()
        val links = root.optJSONArray("schedule_checkpoints") ?: JSONArray()
        val alerts = root.optJSONArray("alerts") ?: JSONArray()

        database.withTransaction {
            for (i in 0 until users.length()) {
                val row = users.getJSONObject(i)
                val salt = row.nullableString("pin_salt_base64") ?: continue
                val hash = row.nullableString("pin_hash_base64") ?: continue
                database.userDao().upsert(
                    UserEntity(
                        id = row.getString("id"),
                        displayName = row.getString("display_name"),
                        photoUrl = row.nullableString("photo_url"),
                        role = row.getString("role"),
                        active = row.optBoolean("active", true),
                        pinSaltBase64 = salt,
                        pinHashBase64 = hash,
                        mustChangePin = row.optBoolean("must_change_pin", false),
                        pinIssuedAtEpochMillis = row.nullableLong("pin_issued_at_ms"),
                        pinChangedAtEpochMillis = row.nullableLong("pin_changed_at_ms"),
                        failedPinAttempts = row.optInt("failed_pin_attempts", 0),
                        lockedUntilEpochMillis = row.nullableLong("locked_until_ms"),
                        archivedAtEpochMillis = row.nullableLong("archived_at_ms"),
                        updatedAtEpochMillis = row.nullableLong("updated_at_ms") ?: System.currentTimeMillis(),
                    ),
                )
            }

            val locationRows = (0 until locations.length()).map { locations.getJSONObject(it) }
                .sortedBy { locationRank(it.optString("type")) }
            locationRows.forEach { row ->
                database.directoryDao().upsertLocation(
                    LocationNodeEntity(
                        id = row.getString("id"),
                        parentId = row.nullableString("parent_id"),
                        type = row.getString("type"),
                        name = row.getString("name"),
                        active = row.optBoolean("active", true),
                        archivedAtEpochMillis = row.nullableLong("archived_at_ms"),
                        updatedAtEpochMillis = row.nullableLong("updated_at_ms") ?: System.currentTimeMillis(),
                    ),
                )
            }

            for (i in 0 until checkpoints.length()) {
                val row = checkpoints.getJSONObject(i)
                database.directoryDao().upsertCheckpoint(
                    CheckpointEntity(
                        id = row.getString("id"),
                        locationNodeId = row.getString("location_node_id"),
                        name = row.getString("name"),
                        description = row.nullableString("description"),
                        sequenceHint = i + 1,
                        minimumTravelSecondsFromPrevious = row.optInt("minimum_travel_seconds_from_previous", 15),
                        fixed = row.optBoolean("fixed", false),
                        systemKey = row.nullableString("system_key"),
                        active = row.optBoolean("active", true),
                        archivedAtEpochMillis = row.nullableLong("archived_at_ms"),
                        updatedAtEpochMillis = row.nullableLong("updated_at_ms") ?: System.currentTimeMillis(),
                    ),
                )
            }

            for (i in 0 until qrCredentials.length()) {
                val row = qrCredentials.getJSONObject(i)
                database.directoryDao().upsertQrCredential(
                    QrCredentialEntity(
                        id = row.getString("id"),
                        checkpointId = row.getString("checkpoint_id"),
                        tokenHash = row.getString("token_hash"),
                        version = row.getInt("version"),
                        status = row.getString("status"),
                        issuedAtEpochMillis = row.nullableLong("issued_at_ms") ?: System.currentTimeMillis(),
                        revokedAtEpochMillis = row.nullableLong("revoked_at_ms"),
                    ),
                )
            }

            for (i in 0 until schedules.length()) {
                val row = schedules.getJSONObject(i)
                database.scheduleDao().upsertSchedule(
                    PatrolScheduleEntity(
                        id = row.getString("id"),
                        propertyId = row.getString("property_id"),
                        name = row.getString("name"),
                        weekdaysCsv = row.optString("weekdays_csv", "1,2,3,4,5,6,7"),
                        startMinuteOfDay = row.getInt("start_minute_of_day"),
                        endMinuteOfDay = row.getInt("end_minute_of_day"),
                        toleranceMinutes = row.optInt("tolerance_minutes", 10),
                        fixedSlot = row.optInt("fixed_slot", 0),
                        startToleranceMinutes = row.optInt("start_tolerance_minutes", 10),
                        endToleranceMinutes = row.optInt("end_tolerance_minutes", 10),
                        targetDurationMinutes = row.optInt("target_duration_minutes", 60),
                        active = row.optBoolean("active", true),
                        archivedAtEpochMillis = row.nullableLong("archived_at_ms"),
                        updatedAtEpochMillis = row.nullableLong("updated_at_ms") ?: System.currentTimeMillis(),
                    ),
                )
            }

            database.scheduleDao().clearCheckpointLinks()
            for (i in 0 until links.length()) {
                val row = links.getJSONObject(i)
                database.scheduleDao().upsertCheckpointLink(
                    ScheduleCheckpointEntity(
                        scheduleId = row.getString("schedule_id"),
                        checkpointId = row.getString("checkpoint_id"),
                        sequence = row.getInt("sequence"),
                    ),
                )
            }

            for (i in 0 until alerts.length()) {
                val row = alerts.getJSONObject(i)
                database.alertDao().insert(
                    AlertEntity(
                        id = row.getString("id"),
                        type = row.getString("type"),
                        description = row.getString("description"),
                        userId = row.nullableString("user_id"),
                        executionId = row.nullableString("execution_id"),
                        deviceId = row.nullableString("device_id"),
                        createdAtEpochMillis = row.nullableLong("created_at_ms") ?: System.currentTimeMillis(),
                        resolved = row.optBoolean("resolved", false),
                        resolvedAtEpochMillis = row.nullableLong("resolved_at_ms"),
                    ),
                )
            }
        }
    }

    private fun openConnection(functionName: String, method: String, token: String): HttpURLConnection =
        (URL(BuildConfig.SUPABASE_URL.trimEnd('/') + "/functions/v1/$functionName").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            doInput = true
            doOutput = method != "GET"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer ${BuildConfig.SUPABASE_PUBLISHABLE_KEY}")
            setRequestProperty("X-Device-Id", deviceIdentity.publicId)
            setRequestProperty("X-Device-Token", token)
        }

    private fun responseText(connection: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    private fun deviceToken(): String? = SecureStore(context).get("device_api_token")
        ?.toString(Charsets.UTF_8)
        ?.takeIf(String::isNotBlank)

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.nullableLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)

    private fun locationRank(type: String): Int = when (type) {
        "PROPERTY" -> 0
        "BUILDING" -> 1
        "BLOCK" -> 2
        "FLOOR" -> 3
        "PLACE" -> 4
        else -> 5
    }
}
