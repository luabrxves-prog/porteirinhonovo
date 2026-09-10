package br.com.porteirinho.sync

import android.content.Context
import androidx.room.withTransaction
import br.com.porteirinho.BuildConfig
import br.com.porteirinho.data.local.*
import br.com.porteirinho.domain.DeviceIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalTime

class RemoteSyncClient(
    private val context: Context,
    private val database: AppDatabase,
    private val deviceIdentity: DeviceIdentity = DeviceIdentity(context),
) {
    private val backendCredentials = BackendDeviceCredentials(context)

    sealed interface ReservationResult {
        data object Reserved : ReservationResult
        data object LocalDemo : ReservationResult
        data class Conflict(val message: String) : ReservationResult
        data class Unavailable(val message: String) : ReservationResult
    }

    suspend fun reservePatrol(scheduleId: String, userId: String, scheduledWindowStartEpochMillis: Long): ReservationResult = withContext(Dispatchers.IO) {
        if (BuildConfig.SUPABASE_URL.isBlank()) return@withContext ReservationResult.LocalDemo
        val credentials = backendCredentials.get()
            ?: return@withContext ReservationResult.Unavailable("Este aparelho ainda precisa ser pareado pela administração.")
        runCatching {
            val connection = openConnection("portaria-reserve", credentials)
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use {
                it.write(JSONObject().put("guard_id", userId).put("patrol_template_id", scheduleId).toString())
            }
            val code = connection.responseCode
            val response = responseText(connection, code)
            connection.disconnect()
            when {
                code in 200..299 -> ReservationResult.Reserved
                code == 409 -> ReservationResult.Conflict(
                    if (response.contains("PATROL_NOT_AVAILABLE")) "Esta ronda não está disponível neste horário."
                    else "Esta ronda já foi iniciada ou reservada por outro porteiro.",
                )
                else -> ReservationResult.Unavailable("Não foi possível reservar a ronda. Verifique a internet.")
            }
        }.getOrElse { ReservationResult.Unavailable("É necessário estar conectado à internet para iniciar uma nova ronda.") }
    }

    suspend fun pullSnapshot(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (BuildConfig.SUPABASE_URL.isBlank()) return@runCatching
            val credentials = backendCredentials.get() ?: return@runCatching
            val connection = openConnection("portaria-cache", credentials)
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write("{}") }
            val code = connection.responseCode
            val response = responseText(connection, code)
            connection.disconnect()
            check(code in 200..299) { "Cache HTTP $code" }
            applyExistingBackendSnapshot(JSONObject(response), credentials.deviceId)
        }
    }

    suspend fun offlineRequest(event: OutboxEventEntity): JSONObject? {
        val payload = JSONObject()
        val type = when (event.eventType) {
            "SHIFT_STARTED" -> "SHIFT_STARTED"
            "PATROL_STARTED" -> "PATROL_STARTED"
            "CHECKPOINT_VISITED" -> "QR_SCANNED"
            "PATROL_FINISHED" -> "PATROL_FINISHED"
            "SHIFT_ENDED" -> "SHIFT_ENDED"
            else -> return null
        }
        when (event.eventType) {
            "SHIFT_STARTED" -> {
                val userId = JSONObject(event.payloadJson).optString("user_id")
                payload.put("guard_id", userId)
                    .put("started_at_local", iso(event.createdAtEpochMillis))
                    .put("captured_offline", true)
            }
            "PATROL_STARTED" -> {
                val execution = database.patrolDao().findExecution(event.aggregateId) ?: return null
                val shiftEvent = database.outboxDao().findEvent(execution.shiftId, "SHIFT_STARTED") ?: return null
                payload.put("guard_id", execution.userId)
                    .put("patrol_template_id", execution.scheduleId)
                    .put("schedule_window_id", windowIdFor(execution.scheduleId, execution.scheduledWindowStartEpochMillis) ?: return null)
                    .put("scheduled_for", iso(execution.scheduledWindowStartEpochMillis))
                    .put("started_at_local", iso(execution.startedAtEpochMillis))
                    .put("shift_client_event_id", shiftEvent.eventId)
                    .put("is_late", false)
                    .put("captured_offline", true)
            }
            "CHECKPOINT_VISITED" -> {
                val visitId = JSONObject(event.payloadJson).optString("visit_id")
                val visit = database.patrolDao().visits(event.aggregateId).firstOrNull { it.id == visitId } ?: return null
                val execution = database.patrolDao().findExecution(event.aggregateId) ?: return null
                val runEvent = database.outboxDao().findEvent(execution.id, "PATROL_STARTED") ?: return null
                val qr = database.directoryDao().findQrById(visit.qrCredentialId) ?: return null
                payload.put("guard_id", execution.userId)
                    .put("run_client_event_id", runEvent.eventId)
                    .put("token_hash", qr.tokenHash)
                    .put("captured_at_local", iso(visit.scannedAtEpochMillis))
                    .put("captured_monotonic_ms", visit.scannedAtElapsedRealtimeMillis)
                    .put("captured_offline", true)
            }
            "PATROL_FINISHED" -> {
                val execution = database.patrolDao().findExecution(event.aggregateId) ?: return null
                val runEvent = database.outboxDao().findEvent(execution.id, "PATROL_STARTED") ?: return null
                payload.put("guard_id", execution.userId)
                    .put("run_client_event_id", runEvent.eventId)
                    .put("finished_at_local", iso(execution.endedAtEpochMillis ?: event.createdAtEpochMillis))
            }
            "SHIFT_ENDED" -> {
                val raw = JSONObject(event.payloadJson)
                val shiftId = raw.optString("shift_id")
                val shiftEvent = database.outboxDao().findEvent(shiftId, "SHIFT_STARTED") ?: return null
                val shift = database.patrolDao().findShift(shiftId) ?: return null
                payload.put("guard_id", shift.userId)
                    .put("shift_client_event_id", shiftEvent.eventId)
                    .put("ended_at_local", iso(shift.endedAtEpochMillis ?: event.createdAtEpochMillis))
            }
        }
        return JSONObject().put("client_event_id", event.eventId).put("type", type).put("payload", payload)
    }

    fun backendCredentials(): BackendDeviceCredentials.Credentials? = backendCredentials.get()

    private suspend fun applyExistingBackendSnapshot(root: JSONObject, backendDeviceId: String) {
        val now = System.currentTimeMillis()
        val building = root.getJSONObject("building")
        val guards = root.optJSONArray("guards") ?: JSONArray()
        val patrols = root.optJSONArray("patrols") ?: JSONArray()
        val windows = root.optJSONArray("windows") ?: JSONArray()
        val checkpoints = root.optJSONArray("checkpoints") ?: JSONArray()
        val links = root.optJSONArray("patrol_checkpoints") ?: JSONArray()
        val qrs = root.optJSONArray("qr_tokens") ?: JSONArray()
        val assignments = root.optJSONArray("assignments") ?: JSONArray()

        context.getSharedPreferences("porteirinho_backend_cache", Context.MODE_PRIVATE)
            .edit().putString("windows", windows.toString()).putString("timezone", building.optString("timezone", "America/Sao_Paulo")).apply()

        database.withTransaction {
            database.directoryDao().upsertLocation(LocationNodeEntity(building.getString("id"), null, "PROPERTY", building.getString("name"), updatedAtEpochMillis = now))
            database.directoryDao().upsertDevice(DeviceEntity(backendDeviceId, deviceIdentity.publicId, "Dispositivo pareado", lastSyncAtEpochMillis = now))

            for (i in 0 until guards.length()) {
                val row = guards.getJSONObject(i)
                val credential = row.optJSONObject("credential") ?: continue
                database.userDao().upsert(UserEntity(
                    id = row.getString("id"),
                    displayName = row.getString("name"),
                    photoUrl = row.optString("photo_url").takeIf { it.isNotBlank() },
                    role = UserRole.GATEKEEPER,
                    pinSaltBase64 = credential.getString("pin_salt"),
                    pinHashBase64 = credential.getString("pin_hash"),
                    pinEncoding = "HEX",
                    pinIterations = credential.optInt("iterations", 210000),
                    mustChangePin = credential.optBoolean("must_change_pin", false),
                    updatedAtEpochMillis = now,
                ))
            }

            val fixedPatrols = (0 until patrols.length()).map { patrols.getJSONObject(it) }
                .filter { it.optBoolean("system_fixed", true) }
                .take(4)
            fixedPatrols.forEachIndexed { index, patrol ->
                val templateId = patrol.getString("id")
                val related = (0 until windows.length()).map { windows.getJSONObject(it) }.filter { it.getString("patrol_template_id") == templateId }
                val first = related.firstOrNull() ?: return@forEachIndexed
                val start = minuteOfDay(first.getString("start_time"))
                val end = minuteOfDay(first.getString("end_time"))
                val weekdays = related.map { it.getInt("day_of_week") }.distinct().sorted().joinToString(",")
                val duration = durationMinutes(start, end)
                database.scheduleDao().upsertSchedule(PatrolScheduleEntity(
                    id = templateId,
                    propertyId = building.getString("id"),
                    name = patrol.getString("name"),
                    weekdaysCsv = weekdays.ifBlank { "1,2,3,4,5,6,7" },
                    startMinuteOfDay = start,
                    endMinuteOfDay = end,
                    toleranceMinutes = first.optInt("late_tolerance_minutes", 10),
                    fixedSlot = index + 1,
                    startToleranceMinutes = first.optInt("late_tolerance_minutes", 10),
                    endToleranceMinutes = first.optInt("late_tolerance_minutes", 10),
                    targetDurationMinutes = duration,
                    updatedAtEpochMillis = now,
                ))
            }

            for (i in 0 until checkpoints.length()) {
                val row = checkpoints.getJSONObject(i)
                val floor = unwrapObject(row.opt("floors"))
                val block = floor?.let { unwrapObject(it.opt("blocks")) }
                if (block != null) database.directoryDao().upsertLocation(LocationNodeEntity(block.getString("id"), building.getString("id"), "BLOCK", block.getString("name"), updatedAtEpochMillis = now))
                if (floor != null) database.directoryDao().upsertLocation(LocationNodeEntity(floor.getString("id"), block?.getString("id"), "FLOOR", floor.getString("name"), updatedAtEpochMillis = now))
                database.directoryDao().upsertCheckpoint(CheckpointEntity(
                    id = row.getString("id"),
                    locationNodeId = floor?.getString("id") ?: building.getString("id"),
                    name = row.getString("name"),
                    sequenceHint = row.optInt("sort_order", i + 1),
                    minimumTravelSecondsFromPrevious = 15,
                    fixed = row.optBoolean("system_fixed", false),
                    active = row.optBoolean("active", true),
                    updatedAtEpochMillis = now,
                ))
            }

            for (i in 0 until qrs.length()) {
                val row = qrs.getJSONObject(i)
                database.directoryDao().upsertQrCredential(QrCredentialEntity(
                    id = row.getString("qr_token_id"), checkpointId = row.getString("checkpoint_id"),
                    tokenHash = row.getString("token_hash"), version = row.optInt("version", 1), issuedAtEpochMillis = now,
                ))
            }

            database.scheduleDao().clearCheckpointLinks()
            for (i in 0 until links.length()) {
                val row = links.getJSONObject(i)
                database.scheduleDao().upsertCheckpointLink(ScheduleCheckpointEntity(row.getString("patrol_template_id"), row.getString("checkpoint_id"), i + 1))
            }

            database.scheduleDao().clearAssignees()
            val windowToTemplate = (0 until windows.length()).associate { windows.getJSONObject(it).getString("id") to windows.getJSONObject(it).getString("patrol_template_id") }
            for (i in 0 until assignments.length()) {
                val row = assignments.getJSONObject(i)
                val scheduleId = windowToTemplate[row.getString("schedule_window_id")] ?: continue
                database.scheduleDao().upsertAssignee(ScheduleAssigneeEntity(scheduleId, row.getString("guard_id")))
            }
        }
    }

    private fun windowIdFor(templateId: String, scheduledStartMillis: Long): String? {
        val raw = context.getSharedPreferences("porteirinho_backend_cache", Context.MODE_PRIVATE).getString("windows", null) ?: return null
        val windows = JSONArray(raw)
        val zoneId = java.time.ZoneId.of(context.getSharedPreferences("porteirinho_backend_cache", Context.MODE_PRIVATE).getString("timezone", "America/Sao_Paulo"))
        val day = java.time.Instant.ofEpochMilli(scheduledStartMillis).atZone(zoneId).dayOfWeek.value
        for (i in 0 until windows.length()) {
            val row = windows.getJSONObject(i)
            if (row.getString("patrol_template_id") == templateId && row.getInt("day_of_week") == day) return row.getString("id")
        }
        return null
    }

    private fun openConnection(functionName: String, credentials: BackendDeviceCredentials.Credentials): HttpURLConnection =
        (URL(BuildConfig.SUPABASE_URL.trimEnd('/') + "/functions/v1/$functionName").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15_000; readTimeout = 25_000; doInput = true; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
            setRequestProperty("X-Device-Id", credentials.deviceId)
            setRequestProperty("X-Device-Secret", credentials.secret)
        }

    private fun responseText(connection: HttpURLConnection, code: Int): String =
        (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()

    private fun minuteOfDay(value: String): Int { val t = LocalTime.parse(value.take(8)); return t.hour * 60 + t.minute }
    private fun durationMinutes(start: Int, end: Int): Int = if (end >= start) end - start else 1440 - start + end
    private fun iso(epochMillis: Long): String = java.time.Instant.ofEpochMilli(epochMillis).toString()
    private fun unwrapObject(value: Any?): JSONObject? = when (value) { is JSONObject -> value; is JSONArray -> if (value.length() > 0) value.optJSONObject(0) else null; else -> null }
}
