package br.com.porteirinho.data

import android.os.SystemClock
import androidx.room.withTransaction
import br.com.porteirinho.data.local.*
import br.com.porteirinho.domain.*
import br.com.porteirinho.security.PinSecurity
import kotlinx.coroutines.flow.Flow
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.abs

class PatrolRepository(
    private val database: AppDatabase,
    private val deviceIdentity: DeviceIdentity,
) {
    private val userDao = database.userDao()
    private val directoryDao = database.directoryDao()
    private val scheduleDao = database.scheduleDao()
    private val patrolDao = database.patrolDao()
    private val alertDao = database.alertDao()
    private val outboxDao = database.outboxDao()

    val activeUsers: Flow<List<UserEntity>> = userDao.observeActiveUsers()
    val schedules: Flow<List<PatrolScheduleEntity>> = scheduleDao.observeActiveSchedules()
    val recentAlerts: Flow<List<AlertEntity>> = alertDao.observeRecent()
    val pendingSyncCount: Flow<Int> = outboxDao.observePendingCount()
    val permanentSyncFailureCount: Flow<Int> = outboxDao.observePermanentFailureCount()
    val executionCount: Flow<Int> = patrolDao.observeExecutionCount()
    val problemExecutionCount: Flow<Int> = patrolDao.observeProblemExecutionCount()
    val unresolvedAlertCount: Flow<Int> = alertDao.observeUnresolvedCount()

    suspend fun seedDemoIfEmpty() {
        if (userDao.count() > 0) return
        val now = System.currentTimeMillis()
        database.withTransaction {
            val adminHash = PinSecurity.createHash("1234".toCharArray())
            val guardHash = PinSecurity.createHash("1234".toCharArray())
            userDao.upsert(UserEntity("admin-demo", "Administração", role = UserRole.ADMIN, pinSaltBase64 = adminHash.saltBase64, pinHashBase64 = adminHash.hashBase64, updatedAtEpochMillis = now))
            userDao.upsert(UserEntity("guard-demo", "Carlos Almeida", role = UserRole.GATEKEEPER, pinSaltBase64 = guardHash.saltBase64, pinHashBase64 = guardHash.hashBase64, updatedAtEpochMillis = now))

            val property = LocationNodeEntity("property-demo", null, "PROPERTY", "Condomínio Solar", updatedAtEpochMillis = now)
            val block = LocationNodeEntity("block-demo", property.id, "BLOCK", "Bloco A", updatedAtEpochMillis = now)
            val floor = LocationNodeEntity("floor-demo", block.id, "FLOOR", "Térreo", updatedAtEpochMillis = now)
            val place = LocationNodeEntity("place-demo", floor.id, "PLACE", "Áreas comuns", updatedAtEpochMillis = now)
            for (node in listOf(property, block, floor, place)) {
                directoryDao.upsertLocation(node)
            }

            val checkpoints = listOf(
                CheckpointEntity("checkpoint-gate", place.id, "Portão principal", sequenceHint = 1, updatedAtEpochMillis = now),
                CheckpointEntity("checkpoint-garage", place.id, "Garagem", sequenceHint = 2, minimumTravelSecondsFromPrevious = 45, updatedAtEpochMillis = now),
                CheckpointEntity("checkpoint-hall", place.id, "Hall do Bloco A", sequenceHint = 3, minimumTravelSecondsFromPrevious = 60, updatedAtEpochMillis = now),
            )
            for (checkpoint in checkpoints) {
                directoryDao.upsertCheckpoint(checkpoint)
            }
            checkpoints.forEachIndexed { index, checkpoint ->
                val credentialId = "qr-demo-${index + 1}"
                val raw = "porteirinho:v1:$credentialId:DEMO-${checkpoint.id.uppercase()}-2026"
                directoryDao.upsertQrCredential(
                    QrCredentialEntity(
                        id = credentialId,
                        checkpointId = checkpoint.id,
                        tokenHash = QrToken.sha256(raw),
                        version = 1,
                        issuedAtEpochMillis = now,
                    ),
                )
            }

            directoryDao.upsertDevice(DeviceEntity("device-local", deviceIdentity.publicId, "Dispositivo local"))
            scheduleDao.upsertSchedule(
                PatrolScheduleEntity(
                    id = "schedule-demo",
                    propertyId = property.id,
                    name = "Ronda Noturna 01",
                    weekdaysCsv = "1,2,3,4,5,6,7",
                    startMinuteOfDay = 0,
                    endMinuteOfDay = 0,
                    toleranceMinutes = 1439,
                    updatedAtEpochMillis = now,
                ),
            )
            checkpoints.forEachIndexed { index, checkpoint ->
                scheduleDao.upsertCheckpointLink(ScheduleCheckpointEntity("schedule-demo", checkpoint.id, index + 1))
            }
        }
    }

    suspend fun authenticate(userId: String, pinText: String): LoginResult {
        val user = userDao.findById(userId) ?: return LoginResult.Error("Perfil não encontrado.")
        val now = System.currentTimeMillis()
        if (!user.active || user.archivedAtEpochMillis != null) return LoginResult.Error("Perfil inativo.")
        if ((user.lockedUntilEpochMillis ?: 0L) > now) return LoginResult.Error("Acesso temporariamente bloqueado. Tente novamente mais tarde.")

        val valid = PinSecurity.verify(pinText.toCharArray(), user.pinSaltBase64, user.pinHashBase64)
        if (valid) {
            userDao.update(user.copy(failedPinAttempts = 0, lockedUntilEpochMillis = null, updatedAtEpochMillis = now))
            return LoginResult.Success(user.id, user.role)
        }

        val attempts = user.failedPinAttempts + 1
        val lockUntil = if (attempts >= MaxPinAttempts) now + PinLockMillis else null
        database.withTransaction {
            userDao.update(user.copy(failedPinAttempts = if (lockUntil == null) attempts else 0, lockedUntilEpochMillis = lockUntil, updatedAtEpochMillis = now))
            if (lockUntil != null) {
                createAlert("INVALID_PIN_ATTEMPTS", "Perfil bloqueado após tentativas inválidas de PIN.", user.id, null)
            }
        }
        return LoginResult.Error(if (lockUntil == null) "PIN incorreto." else "Muitas tentativas. Acesso bloqueado por 5 minutos.")
    }

    suspend fun ensureAuthorizedDevice(): DeviceEntity? =
        directoryDao.findDeviceByPublicId(deviceIdentity.publicId)?.takeIf { it.active && it.archivedAtEpochMillis == null }

    suspend fun hasActiveShift(userId: String): Boolean = patrolDao.activeShift(userId) != null

    suspend fun user(userId: String): UserEntity? = userDao.findById(userId)

    suspend fun startShift(userId: String): Result<ShiftEntity> = runCatching {
        val device = ensureAuthorizedDevice() ?: error("Este dispositivo não está autorizado.")
        patrolDao.activeShift(userId)?.let { return@runCatching it }
        val now = System.currentTimeMillis()
        val shift = ShiftEntity(UUID.randomUUID().toString(), userId, device.id, now)
        database.withTransaction {
            patrolDao.insertShift(shift)
            enqueue("SHIFT", shift.id, "SHIFT_STARTED", "{\"shift_id\":\"${shift.id}\",\"user_id\":\"$userId\",\"device_id\":\"${device.id}\",\"started_at\":$now}")
        }
        shift
    }

    suspend fun finishShift(userId: String): Result<Unit> = runCatching {
        check(patrolDao.activeExecution(userId) == null) { "Finalize a ronda ativa antes de encerrar o turno." }
        val shift = patrolDao.activeShift(userId) ?: error("Não existe turno ativo.")
        val now = System.currentTimeMillis()
        database.withTransaction {
            patrolDao.updateShift(shift.copy(endedAtEpochMillis = now))
            enqueue("SHIFT", shift.id, "SHIFT_ENDED", "{\"shift_id\":\"${shift.id}\",\"ended_at\":$now}")
        }
    }

    suspend fun availablePatrols(userId: String, now: ZonedDateTime = ZonedDateTime.now()): List<AvailablePatrol> {
        val items = mutableListOf<AvailablePatrol>()
        val activeSchedules = scheduleDao.activeSchedules()
        for (schedule in activeSchedules) {
            val assignees = scheduleDao.assigneeIds(schedule.id)
            if (assignees.isNotEmpty() && userId !in assignees) continue
            val window = ScheduleWindow.resolve(schedule.startMinuteOfDay, schedule.endMinuteOfDay, schedule.toleranceMinutes, now)
            val weekday = window.start.dayOfWeek.value.toString()
            if (weekday !in schedule.weekdaysCsv.split(',')) continue
            val format = DateTimeFormatter.ofPattern("HH:mm")
            items += AvailablePatrol(
                schedule = schedule,
                pointCount = scheduleDao.checkpointIds(schedule.id).size,
                windowLabel = "${window.start.format(format)}–${window.end.format(format)}",
                availableNow = ScheduleWindow.isVisible(window, now),
            )
        }
        return items
    }

    suspend fun resumePatrol(userId: String): ActivePatrolSnapshot? {
        val execution = patrolDao.activeExecution(userId) ?: return null
        return snapshot(execution)
    }

    suspend fun startPatrol(userId: String, scheduleId: String): Result<ActivePatrolSnapshot> = runCatching {
        resumePatrol(userId)?.let { return@runCatching it }
        val shift = patrolDao.activeShift(userId) ?: error("Inicie o turno antes da ronda.")
        val device = ensureAuthorizedDevice() ?: error("Este dispositivo não está autorizado.")
        val schedule = scheduleDao.findById(scheduleId) ?: error("Ronda não encontrada.")
        val assignees = scheduleDao.assigneeIds(scheduleId)
        check(assignees.isEmpty() || userId in assignees) { "Esta ronda está atribuída a outro porteiro." }

        val nowDate = ZonedDateTime.now()
        val window = ScheduleWindow.resolve(schedule.startMinuteOfDay, schedule.endMinuteOfDay, schedule.toleranceMinutes, nowDate)
        check(ScheduleWindow.isVisible(window, nowDate)) { "Esta ronda não está disponível neste horário." }
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val execution = PatrolExecutionEntity(
            id = UUID.randomUUID().toString(),
            scheduleId = schedule.id,
            shiftId = shift.id,
            userId = userId,
            deviceId = device.id,
            scheduledWindowStartEpochMillis = window.start.toInstant().toEpochMilli(),
            scheduledWindowEndEpochMillis = window.end.toInstant().toEpochMilli(),
            startedAtEpochMillis = now,
            startedElapsedRealtimeMillis = elapsed,
            lastWallClockEpochMillis = now,
            lastElapsedRealtimeMillis = elapsed,
        )
        database.withTransaction {
            patrolDao.insertExecution(execution)
            enqueue("PATROL", execution.id, "PATROL_STARTED", "{\"execution_id\":\"${execution.id}\",\"schedule_id\":\"${schedule.id}\",\"user_id\":\"$userId\",\"started_at\":$now}")
        }
        snapshot(execution)
    }

    suspend fun registerScan(executionId: String, rawValue: String): ScanResult {
        val execution = patrolDao.findExecution(executionId) ?: return ScanResult.Rejected("Ronda ativa não encontrada.")
        if (execution.status != PatrolStatus.IN_PROGRESS) return ScanResult.Rejected("Esta ronda já foi finalizada.")
        val token = QrToken.parse(rawValue) ?: return rejectQr("QR Code inválido.", execution)
        val credential = directoryDao.findQrByTokenHash(token.hash) ?: return rejectQr("QR Code não reconhecido.", execution)
        if (credential.status != QrStatus.ACTIVE || credential.revokedAtEpochMillis != null) {
            return rejectQr("Este QR Code foi revogado.", execution, "QR_REVOKED")
        }

        val expectedIds = scheduleDao.checkpointIds(execution.scheduleId)
        if (credential.checkpointId !in expectedIds) return rejectQr("Este ponto não pertence à ronda atual.", execution)
        val checkpoint = directoryDao.findCheckpoint(credential.checkpointId) ?: return rejectQr("Ponto de ronda indisponível.", execution)
        if (!checkpoint.active || checkpoint.archivedAtEpochMillis != null) return rejectQr("Ponto de ronda inativo.", execution)

        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val visits = patrolDao.visits(execution.id)
        visits.firstOrNull { it.checkpointId == checkpoint.id }?.let { return ScanResult.AlreadyVisited(checkpoint.name) }

        val wallDelta = now - execution.lastWallClockEpochMillis
        val elapsedDelta = elapsed - execution.lastElapsedRealtimeMillis
        val clockChanged = abs(wallDelta - elapsedDelta) > ClockDriftToleranceMillis
        val tooFast = visits.isNotEmpty() && elapsedDelta < checkpoint.minimumTravelSecondsFromPrevious * 1_000L
        val suspicious = clockChanged || tooFast
        val reason = when {
            clockChanged -> "CLOCK_CHANGE"
            tooFast -> "IMPOSSIBLE_TRAVEL_TIME"
            else -> null
        }
        val visit = CheckpointVisitEntity(
            id = UUID.randomUUID().toString(),
            executionId = execution.id,
            checkpointId = checkpoint.id,
            qrCredentialId = credential.id,
            scannedAtEpochMillis = now,
            scannedAtElapsedRealtimeMillis = elapsed,
            suspicious = suspicious,
            suspicionReason = reason,
        )

        database.withTransaction {
            val inserted = patrolDao.insertVisit(visit)
            if (inserted == -1L) return@withTransaction
            patrolDao.updateExecution(
                execution.copy(
                    suspicious = execution.suspicious || suspicious,
                    lastWallClockEpochMillis = now,
                    lastElapsedRealtimeMillis = elapsed,
                ),
            )
            if (suspicious) createAlert("POSSIBLE_FRAUD", "Leitura marcada para auditoria: $reason.", execution.userId, execution.id)
            enqueue("PATROL", execution.id, "CHECKPOINT_VISITED", "{\"visit_id\":\"${visit.id}\",\"execution_id\":\"${execution.id}\",\"checkpoint_id\":\"${checkpoint.id}\",\"qr_credential_id\":\"${credential.id}\",\"scanned_at\":$now,\"suspicious\":$suspicious}")
        }
        return ScanResult.Accepted(checkpoint.name, suspicious)
    }

    suspend fun finishPatrol(executionId: String): Result<String> = runCatching {
        val execution = patrolDao.findExecution(executionId) ?: error("Ronda não encontrada.")
        check(execution.status == PatrolStatus.IN_PROGRESS) { "Esta ronda já foi finalizada." }
        val visited = patrolDao.visits(execution.id).map { it.checkpointId }.toSet()
        val expected = scheduleDao.checkpointIds(execution.scheduleId).toSet()
        val now = System.currentTimeMillis()
        val status = when {
            execution.suspicious -> PatrolStatus.SUSPICIOUS
            visited.size < expected.size -> PatrolStatus.INCOMPLETE
            now > execution.scheduledWindowEndEpochMillis -> PatrolStatus.LATE
            else -> PatrolStatus.COMPLETED
        }
        database.withTransaction {
            patrolDao.updateExecution(execution.copy(endedAtEpochMillis = now, status = status))
            if (status != PatrolStatus.COMPLETED) createAlert("PATROL_$status", "Ronda finalizada com status $status.", execution.userId, execution.id)
            enqueue("PATROL", execution.id, "PATROL_FINISHED", "{\"execution_id\":\"${execution.id}\",\"ended_at\":$now,\"status\":\"$status\",\"visited\":${visited.size},\"expected\":${expected.size}}")
        }
        status
    }

    suspend fun addOccurrence(executionId: String, description: String): Result<Unit> = runCatching {
        require(description.isNotBlank()) { "Descreva a ocorrência." }
        val now = System.currentTimeMillis()
        val occurrence = OccurrenceEntity(UUID.randomUUID().toString(), executionId, "GENERAL", description.trim(), createdAtEpochMillis = now)
        database.withTransaction {
            patrolDao.insertOccurrence(occurrence)
            enqueue("PATROL", executionId, "OCCURRENCE_RECORDED", "{\"occurrence_id\":\"${occurrence.id}\",\"execution_id\":\"$executionId\",\"description\":${description.trim().asJsonString()},\"created_at\":$now}")
        }
    }

    suspend fun resolveAlert(alertId: String) = alertDao.resolve(alertId, System.currentTimeMillis())
    suspend fun requeueEvent(eventId: String) = outboxDao.requeue(eventId)

    private suspend fun snapshot(execution: PatrolExecutionEntity): ActivePatrolSnapshot {
        val schedule = scheduleDao.findById(execution.scheduleId) ?: error("Programação da ronda não encontrada.")
        val ids = scheduleDao.checkpointIds(schedule.id)
        val names = ids.mapNotNull { id -> directoryDao.findCheckpoint(id)?.let { id to it.name } }
        val visited = patrolDao.visits(execution.id).map { it.checkpointId }.toSet()
        return ActivePatrolSnapshot(execution.id, schedule.name, execution.startedAtEpochMillis, ids.size, visited, names, execution.suspicious)
    }

    private suspend fun rejectQr(
        message: String,
        execution: PatrolExecutionEntity,
        type: String = "QR_INVALID",
    ): ScanResult.Rejected {
        database.withTransaction { createAlert(type, message, execution.userId, execution.id) }
        return ScanResult.Rejected(message)
    }

    private suspend fun createAlert(type: String, description: String, userId: String?, executionId: String?) {
        val now = System.currentTimeMillis()
        val alert = AlertEntity(UUID.randomUUID().toString(), type, description, userId, executionId, createdAtEpochMillis = now)
        alertDao.insert(alert)
        enqueue("ALERT", alert.id, "ALERT_CREATED", "{\"alert_id\":\"${alert.id}\",\"type\":\"$type\",\"description\":${description.asJsonString()},\"created_at\":$now}")
    }

    private suspend fun enqueue(aggregateType: String, aggregateId: String, eventType: String, payload: String) {
        val event = OutboxEventEntity(
            eventId = UUID.randomUUID().toString(),
            aggregateType = aggregateType,
            aggregateId = aggregateId,
            eventType = eventType,
            payloadJson = payload,
            createdAtEpochMillis = System.currentTimeMillis(),
            createdAtElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
        )
        check(outboxDao.insert(event) != -1L) { "Não foi possível preservar o evento local." }
    }

    private fun String.asJsonString(): String = buildString {
        append('"')
        for (char in this@asJsonString) when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(char)
        }
        append('"')
    }

    private companion object {
        const val MaxPinAttempts = 5
        const val PinLockMillis = 5 * 60 * 1_000L
        const val ClockDriftToleranceMillis = 2 * 60 * 1_000L
    }
}
