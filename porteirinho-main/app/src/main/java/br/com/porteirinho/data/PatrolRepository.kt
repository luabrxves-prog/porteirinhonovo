package br.com.porteirinho.data

import android.os.SystemClock
import androidx.room.withTransaction
import br.com.porteirinho.data.local.*
import br.com.porteirinho.domain.*
import br.com.porteirinho.security.PinSecurity
import kotlinx.coroutines.flow.Flow
import java.security.SecureRandom
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
    val checkpoints: Flow<List<CheckpointEntity>> = directoryDao.observeActiveCheckpoints()
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
            val place = LocationNodeEntity("place-demo", block.id, "PLACE", "Pontos de ronda", updatedAtEpochMillis = now)
            listOf(property, block, place).forEach(directoryDao::upsertLocation)

            val fixedNames = buildList {
                add("Térreo")
                add("Garagem")
                add("Play")
                (1..11).forEach { add("${it}º andar") }
                add("Cobertura")
            }
            val fixedPoints = fixedNames.mapIndexed { index, name ->
                CheckpointEntity(
                    id = "checkpoint-fixed-${index + 1}",
                    locationNodeId = place.id,
                    name = name,
                    sequenceHint = index + 1,
                    minimumTravelSecondsFromPrevious = MinimumSecondsBetweenScans,
                    fixed = true,
                    systemKey = "FIXED_${index + 1}",
                    updatedAtEpochMillis = now,
                )
            }
            fixedPoints.forEach(directoryDao::upsertCheckpoint)
            fixedPoints.forEachIndexed { index, checkpoint ->
                val credentialId = "qr-fixed-${index + 1}"
                val raw = "porteirinho:v1:$credentialId:FIXED-${UUID.randomUUID()}"
                directoryDao.upsertQrCredential(QrCredentialEntity(credentialId, checkpoint.id, QrToken.sha256(raw), 1, issuedAtEpochMillis = now))
            }

            directoryDao.upsertDevice(DeviceEntity("device-local", deviceIdentity.publicId, "Dispositivo local"))

            val defaultStarts = listOf(0, 360, 720, 1080)
            defaultStarts.forEachIndexed { index, start ->
                val scheduleId = "schedule-fixed-${index + 1}"
                scheduleDao.upsertSchedule(
                    PatrolScheduleEntity(
                        id = scheduleId,
                        propertyId = property.id,
                        name = "Ronda ${index + 1}",
                        weekdaysCsv = "1,2,3,4,5,6,7",
                        startMinuteOfDay = start,
                        endMinuteOfDay = (start + 60) % 1440,
                        toleranceMinutes = 10,
                        fixedSlot = index + 1,
                        startToleranceMinutes = 10,
                        endToleranceMinutes = 10,
                        targetDurationMinutes = 60,
                        updatedAtEpochMillis = now,
                    ),
                )
                fixedPoints.forEachIndexed { pointIndex, checkpoint ->
                    scheduleDao.upsertCheckpointLink(ScheduleCheckpointEntity(scheduleId, checkpoint.id, pointIndex + 1))
                }
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
            return LoginResult.Success(user.id, user.role, user.mustChangePin)
        }
        val attempts = user.failedPinAttempts + 1
        val lockUntil = if (attempts >= MaxPinAttempts) now + PinLockMillis else null
        database.withTransaction {
            userDao.update(user.copy(failedPinAttempts = if (lockUntil == null) attempts else 0, lockedUntilEpochMillis = lockUntil, updatedAtEpochMillis = now))
            if (lockUntil != null) createAlert("INVALID_PIN_ATTEMPTS", "Perfil bloqueado após tentativas inválidas de PIN.", user.id, null)
        }
        return LoginResult.Error(if (lockUntil == null) "PIN incorreto." else "Muitas tentativas. Acesso bloqueado por 5 minutos.")
    }

    suspend fun createGatekeeper(displayName: String): Result<Pair<UserEntity, String>> = runCatching {
        require(displayName.trim().length >= 2) { "Informe o nome do porteiro." }
        val generatedPin = (SecureRandom().nextInt(900000) + 100000).toString()
        val hash = PinSecurity.createHash(generatedPin.toCharArray())
        val now = System.currentTimeMillis()
        val user = UserEntity(
            id = UUID.randomUUID().toString(),
            displayName = displayName.trim(),
            role = UserRole.GATEKEEPER,
            pinSaltBase64 = hash.saltBase64,
            pinHashBase64 = hash.hashBase64,
            mustChangePin = true,
            pinIssuedAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        database.withTransaction {
            userDao.upsert(user)
            enqueue("USER", user.id, "GATEKEEPER_CREATED", "{\"user_id\":\"${user.id}\",\"display_name\":${user.displayName.asJsonString()}}")
        }
        user to generatedPin
    }

    suspend fun changePin(userId: String, newPin: String): Result<Unit> = runCatching {
        require(newPin.length in 4..8 && newPin.all(Char::isDigit)) { "A senha deve ter de 4 a 8 números." }
        val user = userDao.findById(userId) ?: error("Perfil não encontrado.")
        val hash = PinSecurity.createHash(newPin.toCharArray())
        val now = System.currentTimeMillis()
        database.withTransaction {
            userDao.update(user.copy(pinSaltBase64 = hash.saltBase64, pinHashBase64 = hash.hashBase64, mustChangePin = false, pinChangedAtEpochMillis = now, updatedAtEpochMillis = now))
            enqueue("USER", user.id, "PIN_CHANGED", "{\"user_id\":\"${user.id}\",\"changed_at\":$now}")
        }
    }

    suspend fun updateFixedSchedule(scheduleId: String, name: String, startMinuteOfDay: Int): Result<Unit> = runCatching {
        require(startMinuteOfDay in 0..1439) { "Horário inválido." }
        val schedule = scheduleDao.findById(scheduleId) ?: error("Ronda não encontrada.")
        check(schedule.fixedSlot in 1..4) { "Somente as quatro rondas fixas podem ser configuradas." }
        val now = System.currentTimeMillis()
        val updated = schedule.copy(
            name = name.trim().ifBlank { schedule.name },
            startMinuteOfDay = startMinuteOfDay,
            endMinuteOfDay = (startMinuteOfDay + schedule.targetDurationMinutes) % 1440,
            updatedAtEpochMillis = now,
        )
        database.withTransaction {
            scheduleDao.upsertSchedule(updated)
            enqueue("SCHEDULE", schedule.id, "SCHEDULE_UPDATED", "{\"schedule_id\":\"${schedule.id}\",\"name\":${updated.name.asJsonString()},\"start_minute\":$startMinuteOfDay}")
        }
    }

    suspend fun addExtraCheckpoint(locationNodeId: String, name: String): Result<CheckpointEntity> = runCatching {
        require(name.trim().isNotBlank()) { "Informe o nome do ponto extra." }
        val now = System.currentTimeMillis()
        val checkpoint = CheckpointEntity(UUID.randomUUID().toString(), locationNodeId, name.trim(), updatedAtEpochMillis = now)
        database.withTransaction {
            directoryDao.upsertCheckpoint(checkpoint)
            val nextSequence = directoryDao.activeCheckpoints().size
            scheduleDao.activeSchedules().filter { it.fixedSlot in 1..4 }.forEach {
                scheduleDao.upsertCheckpointLink(ScheduleCheckpointEntity(it.id, checkpoint.id, nextSequence))
            }
            enqueue("CHECKPOINT", checkpoint.id, "CHECKPOINT_CREATED", "{\"checkpoint_id\":\"${checkpoint.id}\",\"name\":${checkpoint.name.asJsonString()}}")
        }
        checkpoint
    }

    suspend fun replaceQr(checkpointId: String): Result<String> = runCatching {
        val checkpoint = directoryDao.findCheckpoint(checkpointId) ?: error("Ponto não encontrado.")
        val now = System.currentTimeMillis()
        val current = directoryDao.activeQrForCheckpoint(checkpointId)
        val version = (current?.version ?: 0) + 1
        val credentialId = UUID.randomUUID().toString()
        val raw = "porteirinho:v1:$credentialId:${UUID.randomUUID()}"
        database.withTransaction {
            current?.let { directoryDao.updateQrCredential(it.copy(status = QrStatus.REVOKED, revokedAtEpochMillis = now)) }
            directoryDao.upsertQrCredential(QrCredentialEntity(credentialId, checkpointId, QrToken.sha256(raw), version, issuedAtEpochMillis = now))
            enqueue("CHECKPOINT", checkpoint.id, "QR_REPLACED", "{\"checkpoint_id\":\"${checkpoint.id}\",\"version\":$version}")
        }
        raw
    }

    suspend fun ensureAuthorizedDevice(): DeviceEntity? = directoryDao.findDeviceByPublicId(deviceIdentity.publicId)?.takeIf { it.active && it.archivedAtEpochMillis == null }
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
        for (schedule in scheduleDao.activeSchedules().filter { it.fixedSlot in 1..4 }) {
            val assignees = scheduleDao.assigneeIds(schedule.id)
            if (assignees.isNotEmpty() && userId !in assignees) continue
            val window = ScheduleWindow.resolve(schedule.startMinuteOfDay, schedule.endMinuteOfDay, schedule.endToleranceMinutes, now)
            if (window.start.dayOfWeek.value.toString() !in schedule.weekdaysCsv.split(',')) continue
            val format = DateTimeFormatter.ofPattern("HH:mm")
            items += AvailablePatrol(
                schedule = schedule,
                pointCount = scheduleDao.checkpointIds(schedule.id).size,
                windowLabel = "${window.start.format(format)}–${window.end.format(format)}",
                durationLabel = "Tempo previsto: ${schedule.targetDurationMinutes} min",
                toleranceLabel = "Tolerância: -${schedule.startToleranceMinutes}/+${schedule.endToleranceMinutes} min",
                availableNow = ScheduleWindow.isVisible(window, now, schedule.startToleranceMinutes, schedule.endToleranceMinutes),
            )
        }
        return items
    }

    suspend fun resumePatrol(userId: String): ActivePatrolSnapshot? = patrolDao.activeExecution(userId)?.let { snapshot(it) }

    suspend fun startPatrol(userId: String, scheduleId: String): Result<ActivePatrolSnapshot> = runCatching {
        resumePatrol(userId)?.let { return@runCatching it }
        val shift = patrolDao.activeShift(userId) ?: error("Inicie o turno antes da ronda.")
        val device = ensureAuthorizedDevice() ?: error("Este dispositivo não está autorizado.")
        val schedule = scheduleDao.findById(scheduleId) ?: error("Ronda não encontrada.")
        check(schedule.fixedSlot in 1..4) { "Ronda inválida." }
        val assignees = scheduleDao.assigneeIds(scheduleId)
        check(assignees.isEmpty() || userId in assignees) { "Esta ronda está atribuída a outro porteiro." }
        val nowDate = ZonedDateTime.now()
        val window = ScheduleWindow.resolve(schedule.startMinuteOfDay, schedule.endMinuteOfDay, schedule.endToleranceMinutes, nowDate)
        check(ScheduleWindow.isVisible(window, nowDate, schedule.startToleranceMinutes, schedule.endToleranceMinutes)) { "Esta ronda não está disponível neste horário." }
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val execution = PatrolExecutionEntity(UUID.randomUUID().toString(), schedule.id, shift.id, userId, device.id, window.start.toInstant().toEpochMilli(), window.end.toInstant().toEpochMilli(), now, elapsed, lastWallClockEpochMillis = now, lastElapsedRealtimeMillis = elapsed)
        database.withTransaction {
            patrolDao.insertExecution(execution)
            when (ScheduleWindow.startTiming(window, nowDate, schedule.startToleranceMinutes)) {
                StartTiming.TOO_EARLY -> createAlert("PATROL_TOO_EARLY", "A ronda foi iniciada cedo demais.", userId, execution.id)
                StartTiming.LATE -> createAlert("PATROL_LATE_START", "A ronda foi iniciada após a tolerância de início.", userId, execution.id)
                StartTiming.ON_TIME -> Unit
            }
            enqueue("PATROL", execution.id, "PATROL_STARTED", "{\"execution_id\":\"${execution.id}\",\"schedule_id\":\"${schedule.id}\",\"user_id\":\"$userId\",\"started_at\":$now}")
        }
        snapshot(execution)
    }

    suspend fun registerScan(executionId: String, rawValue: String): ScanResult {
        val execution = patrolDao.findExecution(executionId) ?: return ScanResult.Rejected("Ronda ativa não encontrada.")
        if (execution.status != PatrolStatus.IN_PROGRESS) return ScanResult.Rejected("Esta ronda já foi finalizada.")
        val token = QrToken.parse(rawValue) ?: return rejectQr("QR Code inválido.", execution)
        val credential = directoryDao.findQrByTokenHash(token.hash) ?: return rejectQr("QR Code não reconhecido.", execution)
        if (credential.status != QrStatus.ACTIVE || credential.revokedAtEpochMillis != null) return rejectQr("Este QR Code foi substituído e não funciona mais.", execution, "QR_REVOKED")
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
        val tooFast = visits.isNotEmpty() && elapsedDelta < MinimumSecondsBetweenScans * 1_000L
        val suspicious = clockChanged || tooFast
        val reason = when {
            clockChanged -> "CLOCK_CHANGE"
            tooFast -> "QR_READ_TOO_FAST"
            else -> null
        }
        val visit = CheckpointVisitEntity(UUID.randomUUID().toString(), execution.id, checkpoint.id, credential.id, now, elapsed, suspicious, reason)
        database.withTransaction {
            if (patrolDao.insertVisit(visit) == -1L) return@withTransaction
            patrolDao.updateExecution(execution.copy(suspicious = execution.suspicious || suspicious, lastWallClockEpochMillis = now, lastElapsedRealtimeMillis = elapsed))
            if (tooFast) createAlert("QR_READ_TOO_FAST", "QR Code lido menos de 15 segundos após o ponto anterior. A leitura foi aceita.", execution.userId, execution.id)
            if (clockChanged) createAlert("CLOCK_CHANGE", "Alteração de relógio detectada durante a ronda.", execution.userId, execution.id)
            enqueue("PATROL", execution.id, "CHECKPOINT_VISITED", "{\"visit_id\":\"${visit.id}\",\"execution_id\":\"${execution.id}\",\"checkpoint_id\":\"${checkpoint.id}\",\"qr_credential_id\":\"${credential.id}\",\"scanned_at\":$now,\"suspicious\":$suspicious}")
        }
        return ScanResult.Accepted(checkpoint.id, checkpoint.name, suspicious)
    }

    suspend fun finishPatrol(executionId: String): Result<String> = runCatching {
        val execution = patrolDao.findExecution(executionId) ?: error("Ronda não encontrada.")
        check(execution.status == PatrolStatus.IN_PROGRESS) { "Esta ronda já foi finalizada." }
        val visited = patrolDao.visits(execution.id).map { it.checkpointId }.toSet()
        val expected = scheduleDao.checkpointIds(execution.scheduleId).toSet()
        check(expected.isNotEmpty() && visited.containsAll(expected)) { "A ronda só pode ser finalizada após todos os pontos serem concluídos." }
        val schedule = scheduleDao.findById(execution.scheduleId) ?: error("Programação não encontrada.")
        val now = System.currentTimeMillis()
        val durationMinutes = (SystemClock.elapsedRealtime() - execution.startedElapsedRealtimeMillis) / 60_000L
        database.withTransaction {
            patrolDao.updateExecution(execution.copy(endedAtEpochMillis = now, status = PatrolStatus.COMPLETED))
            if (durationMinutes > schedule.targetDurationMinutes + schedule.endToleranceMinutes) {
                createAlert("PATROL_TOO_LONG", "A ronda levou $durationMinutes min; previsto ${schedule.targetDurationMinutes} min.", execution.userId, execution.id)
            }
            if (now > execution.scheduledWindowEndEpochMillis + schedule.endToleranceMinutes * 60_000L) {
                createAlert("PATROL_LATE_FINISH", "A ronda terminou após a tolerância configurada.", execution.userId, execution.id)
            }
            enqueue("PATROL", execution.id, "PATROL_FINISHED", "{\"execution_id\":\"${execution.id}\",\"ended_at\":$now,\"status\":\"COMPLETED\",\"visited\":${visited.size},\"expected\":${expected.size}}")
        }
        PatrolStatus.COMPLETED
    }

    suspend fun addOccurrence(executionId: String, checkpointId: String?, description: String): Result<Unit> = runCatching {
        require(description.isNotBlank()) { "Descreva a observação." }
        if (checkpointId != null) check(checkpointId in scheduleDao.checkpointIds(patrolDao.findExecution(executionId)?.scheduleId ?: error("Ronda não encontrada."))) { "Ponto inválido." }
        val now = System.currentTimeMillis()
        val occurrence = OccurrenceEntity(UUID.randomUUID().toString(), executionId, checkpointId, "OBSERVATION", description.trim(), createdAtEpochMillis = now)
        val execution = patrolDao.findExecution(executionId) ?: error("Ronda não encontrada.")
        database.withTransaction {
            patrolDao.insertOccurrence(occurrence)
            createAlert("CHECKPOINT_OBSERVATION", "Observação registrada durante a ronda: ${description.trim()}", execution.userId, executionId)
            enqueue("PATROL", executionId, "OCCURRENCE_RECORDED", "{\"occurrence_id\":\"${occurrence.id}\",\"execution_id\":\"$executionId\",\"checkpoint_id\":${checkpointId?.asJsonString() ?: "null"},\"description\":${description.trim().asJsonString()},\"created_at\":$now}")
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

    private suspend fun rejectQr(message: String, execution: PatrolExecutionEntity, type: String = "QR_INVALID"): ScanResult.Rejected {
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
        val event = OutboxEventEntity(UUID.randomUUID().toString(), aggregateType, aggregateId, eventType, payload, System.currentTimeMillis(), SystemClock.elapsedRealtime())
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
        const val MinimumSecondsBetweenScans = 15
    }
}
