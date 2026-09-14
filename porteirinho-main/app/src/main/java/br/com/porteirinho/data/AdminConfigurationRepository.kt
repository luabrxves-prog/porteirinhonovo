package br.com.porteirinho.data

import android.content.Context
import android.os.SystemClock
import androidx.room.withTransaction
import br.com.porteirinho.data.local.AppDatabase
import br.com.porteirinho.data.local.CheckpointEntity
import br.com.porteirinho.data.local.LocationNodeEntity
import br.com.porteirinho.data.local.OutboxEventEntity
import br.com.porteirinho.data.local.PatrolScheduleEntity
import br.com.porteirinho.data.local.QrCredentialEntity
import br.com.porteirinho.data.local.ScheduleCheckpointEntity
import br.com.porteirinho.domain.AdminBlock
import br.com.porteirinho.domain.AdminPoint
import br.com.porteirinho.domain.AdminRound
import br.com.porteirinho.domain.FixedPatrolStructure
import br.com.porteirinho.domain.QrToken
import br.com.porteirinho.sync.SyncScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class AdminConfigurationRepository(
    private val context: Context,
    private val database: AppDatabase,
) {
    private val directoryDao = database.directoryDao()
    private val scheduleDao = database.scheduleDao()
    private val outboxDao = database.outboxDao()

    val blocks: Flow<List<AdminBlock>> = combine(
        directoryDao.observeLocationsByIds(FixedPatrolStructure.blockIds.toList()),
        directoryDao.observeActiveCheckpointsForLocations(FixedPatrolStructure.blockIds.toList()),
        directoryDao.observeActiveQrCredentials(),
    ) { locations, checkpoints, credentials ->
        val locationById = locations.associateBy { it.id }
        val credentialsByCheckpoint = credentials
            .sortedBy { it.version }
            .associateBy { it.checkpointId }

        FixedPatrolStructure.blocks.mapNotNull { definition ->
            val location = locationById[definition.id] ?: return@mapNotNull null
            val points = checkpoints
                .filter { it.locationNodeId == definition.id }
                .sortedWith(compareBy<CheckpointEntity> { it.sequenceHint }.thenBy { it.name })
                .map { checkpoint ->
                    val credential = credentialsByCheckpoint[checkpoint.id]
                    AdminPoint(
                        id = checkpoint.id,
                        blockId = checkpoint.locationNodeId,
                        name = checkpoint.name,
                        sequence = checkpoint.sequenceHint,
                        fixed = FixedPatrolStructure.isFixedCheckpoint(checkpoint.id),
                        qrRawValue = credential?.let {
                            FixedPatrolStructure.qrRawValue(it.id, it.checkpointId, it.version)
                        },
                        qrVersion = credential?.version,
                    )
                }
            AdminBlock(location.id, location.name, points)
        }
    }

    val rounds: Flow<List<AdminRound>> = scheduleDao
        .observeSchedulesByIds(FixedPatrolStructure.scheduleIds)
        .map { schedules ->
            val byId = schedules.associateBy { it.id }
            FixedPatrolStructure.rounds.mapNotNull { definition ->
                byId[definition.id]?.let { schedule ->
                    AdminRound(schedule.id, schedule.name, schedule.startMinuteOfDay)
                }
            }
        }

    suspend fun ensureFixedStructure() {
        val now = System.currentTimeMillis()
        database.withTransaction {
            if (directoryDao.findLocation(FixedPatrolStructure.PropertyId) == null) {
                directoryDao.upsertLocation(
                    LocationNodeEntity(
                        id = FixedPatrolStructure.PropertyId,
                        type = "PROPERTY",
                        name = FixedPatrolStructure.PropertyName,
                        updatedAtEpochMillis = now,
                    ),
                )
            }

            FixedPatrolStructure.blocks.forEach { block ->
                if (directoryDao.findLocation(block.id) == null) {
                    directoryDao.upsertLocation(
                        LocationNodeEntity(
                            id = block.id,
                            parentId = FixedPatrolStructure.PropertyId,
                            type = "BLOCK",
                            name = block.name,
                            updatedAtEpochMillis = now,
                        ),
                    )
                }

                block.points.forEach { point ->
                    if (directoryDao.findCheckpoint(point.id) == null) {
                        directoryDao.upsertCheckpoint(
                            CheckpointEntity(
                                id = point.id,
                                locationNodeId = block.id,
                                name = point.name,
                                description = "Ponto fixo de ronda",
                                sequenceHint = point.sequence,
                                updatedAtEpochMillis = now,
                            ),
                        )
                    }

                    if (directoryDao.activeQrForCheckpoint(point.id) == null) {
                        val raw = FixedPatrolStructure.qrRawValue(point.qrCredentialId, point.id, 1)
                        directoryDao.upsertQrCredential(
                            QrCredentialEntity(
                                id = point.qrCredentialId,
                                checkpointId = point.id,
                                tokenHash = QrToken.sha256(raw),
                                version = 1,
                                issuedAtEpochMillis = now,
                            ),
                        )
                    }
                }
            }

            val orderedFixedPoints = FixedPatrolStructure.blocks.flatMap { it.points }
            FixedPatrolStructure.rounds.forEach { round ->
                if (scheduleDao.findById(round.id) == null) {
                    scheduleDao.upsertSchedule(
                        PatrolScheduleEntity(
                            id = round.id,
                            propertyId = FixedPatrolStructure.PropertyId,
                            name = round.name,
                            weekdaysCsv = "1,2,3,4,5,6,7",
                            startMinuteOfDay = round.startMinuteOfDay,
                            endMinuteOfDay = FixedPatrolStructure.endMinuteOfDay(round.startMinuteOfDay),
                            toleranceMinutes = FixedPatrolStructure.RoundToleranceMinutes,
                            updatedAtEpochMillis = now,
                        ),
                    )
                }
                orderedFixedPoints.forEachIndexed { index, point ->
                    scheduleDao.upsertCheckpointLink(
                        ScheduleCheckpointEntity(round.id, point.id, index + 1),
                    )
                }
            }

            scheduleDao.deactivateSchedule("schedule-demo", now)
        }
        SyncScheduler.runNow(context)
    }

    suspend fun addExtraPoint(blockId: String, name: String): Result<Unit> = runCatching {
        require(blockId in FixedPatrolStructure.blockIds) { "Bloco inválido." }
        val cleanName = name.trim()
        require(cleanName.isNotBlank()) { "Informe o nome do ponto extra." }
        check(directoryDao.findLocation(blockId) != null) { "Bloco não encontrado." }

        val now = System.currentTimeMillis()
        val checkpointId = UUID.randomUUID().toString()
        val credentialId = UUID.randomUUID().toString()
        val sequence = directoryDao.maxSequenceForLocation(blockId) + 1
        val raw = FixedPatrolStructure.qrRawValue(credentialId, checkpointId, 1)

        database.withTransaction {
            directoryDao.upsertCheckpoint(
                CheckpointEntity(
                    id = checkpointId,
                    locationNodeId = blockId,
                    name = cleanName,
                    description = "Ponto extra de ronda",
                    sequenceHint = sequence,
                    updatedAtEpochMillis = now,
                ),
            )
            directoryDao.upsertQrCredential(
                QrCredentialEntity(
                    id = credentialId,
                    checkpointId = checkpointId,
                    tokenHash = QrToken.sha256(raw),
                    version = 1,
                    issuedAtEpochMillis = now,
                ),
            )

            FixedPatrolStructure.scheduleIds.forEach { scheduleId ->
                if (scheduleDao.findById(scheduleId) != null) {
                    scheduleDao.upsertCheckpointLink(
                        ScheduleCheckpointEntity(
                            scheduleId = scheduleId,
                            checkpointId = checkpointId,
                            sequence = scheduleDao.nextCheckpointSequence(scheduleId),
                        ),
                    )
                }
            }

            enqueue(
                aggregateId = checkpointId,
                eventType = "CHECKPOINT_CREATED",
                payload = JSONObject()
                    .put("checkpoint_id", checkpointId)
                    .put("block_id", blockId)
                    .put("name", cleanName)
                    .put("sequence_hint", sequence)
                    .put("qr_credential_id", credentialId)
                    .put("qr_token_hash", QrToken.sha256(raw))
                    .put("qr_version", 1)
                    .put("schedule_ids", JSONArray().apply {
                        FixedPatrolStructure.scheduleIds.forEach { put(it) }
                    }),
            )
        }
        SyncScheduler.runNow(context)
    }

    suspend fun replaceQr(checkpointId: String): Result<Unit> = runCatching {
        val checkpoint = directoryDao.findCheckpoint(checkpointId) ?: error("Ponto não encontrado.")
        require(checkpoint.locationNodeId in FixedPatrolStructure.blockIds) { "Ponto fora da estrutura de ronda." }
        val current = directoryDao.activeQrForCheckpoint(checkpointId)
        val version = (current?.version ?: 0) + 1
        val credentialId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val raw = FixedPatrolStructure.qrRawValue(credentialId, checkpointId, version)
        val hash = QrToken.sha256(raw)

        database.withTransaction {
            directoryDao.revokeActiveQr(checkpointId, now)
            directoryDao.upsertQrCredential(
                QrCredentialEntity(
                    id = credentialId,
                    checkpointId = checkpointId,
                    tokenHash = hash,
                    version = version,
                    issuedAtEpochMillis = now,
                ),
            )
            enqueue(
                aggregateId = checkpointId,
                eventType = "QR_REPLACED",
                payload = JSONObject()
                    .put("checkpoint_id", checkpointId)
                    .put("qr_credential_id", credentialId)
                    .put("qr_token_hash", hash)
                    .put("qr_version", version),
            )
        }
        SyncScheduler.runNow(context)
    }

    suspend fun updateFixedRound(scheduleId: String, name: String, startMinuteOfDay: Int): Result<Unit> = runCatching {
        require(scheduleId in FixedPatrolStructure.scheduleIds) { "Ronda fixa inválida." }
        require(startMinuteOfDay in 0..1439) { "Horário inválido." }
        val cleanName = name.trim()
        require(cleanName.isNotBlank()) { "Informe o nome da ronda." }
        val current = scheduleDao.findById(scheduleId) ?: error("Ronda não encontrada.")
        val now = System.currentTimeMillis()
        val updated = current.copy(
            name = cleanName,
            weekdaysCsv = "1,2,3,4,5,6,7",
            startMinuteOfDay = startMinuteOfDay,
            endMinuteOfDay = FixedPatrolStructure.endMinuteOfDay(startMinuteOfDay),
            toleranceMinutes = FixedPatrolStructure.RoundToleranceMinutes,
            active = true,
            archivedAtEpochMillis = null,
            updatedAtEpochMillis = now,
        )

        database.withTransaction {
            scheduleDao.upsertSchedule(updated)
            enqueue(
                aggregateId = scheduleId,
                eventType = "SCHEDULE_UPDATED",
                payload = JSONObject()
                    .put("schedule_id", scheduleId)
                    .put("name", cleanName)
                    .put("start_minute_of_day", startMinuteOfDay)
                    .put("end_minute_of_day", updated.endMinuteOfDay)
                    .put("tolerance_minutes", updated.toleranceMinutes),
            )
        }
        SyncScheduler.runNow(context)
    }

    private suspend fun enqueue(aggregateId: String, eventType: String, payload: JSONObject) {
        val event = OutboxEventEntity(
            eventId = UUID.randomUUID().toString(),
            aggregateType = "ADMIN_CONFIG",
            aggregateId = aggregateId,
            eventType = eventType,
            payloadJson = payload.toString(),
            createdAtEpochMillis = System.currentTimeMillis(),
            createdAtElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
        )
        check(outboxDao.insert(event) != -1L) { "Não foi possível preservar a alteração para sincronização." }
    }
}
