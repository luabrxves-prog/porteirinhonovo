package br.com.porteirinho.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE active = 1 AND archivedAtEpochMillis IS NULL ORDER BY displayName")
    fun observeActiveUsers(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): UserEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(user: UserEntity)

    @Update
    suspend fun update(user: UserEntity)

    @Query("SELECT COUNT(*) FROM users")
    suspend fun count(): Int
}

@Dao
interface DirectoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLocation(node: LocationNodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCheckpoint(checkpoint: CheckpointEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertQrCredential(credential: QrCredentialEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevice(device: DeviceEntity)

    @Query("SELECT * FROM location_nodes WHERE id = :id LIMIT 1")
    suspend fun findLocation(id: String): LocationNodeEntity?

    @Query("SELECT * FROM location_nodes WHERE id IN (:ids) AND active = 1 AND archivedAtEpochMillis IS NULL")
    fun observeLocationsByIds(ids: List<String>): Flow<List<LocationNodeEntity>>

    @Query("SELECT * FROM checkpoints WHERE locationNodeId IN (:locationIds) AND active = 1 AND archivedAtEpochMillis IS NULL ORDER BY locationNodeId, sequenceHint, name")
    fun observeActiveCheckpointsForLocations(locationIds: List<String>): Flow<List<CheckpointEntity>>

    @Query("SELECT * FROM qr_credentials WHERE status = 'ACTIVE' AND revokedAtEpochMillis IS NULL")
    fun observeActiveQrCredentials(): Flow<List<QrCredentialEntity>>

    @Query("SELECT * FROM devices WHERE publicId = :publicId LIMIT 1")
    suspend fun findDeviceByPublicId(publicId: String): DeviceEntity?

    @Query("SELECT * FROM checkpoints WHERE id = :id LIMIT 1")
    suspend fun findCheckpoint(id: String): CheckpointEntity?

    @Query("SELECT * FROM qr_credentials WHERE tokenHash = :tokenHash LIMIT 1")
    suspend fun findQrByTokenHash(tokenHash: String): QrCredentialEntity?

    @Query("SELECT * FROM qr_credentials WHERE checkpointId = :checkpointId AND status = 'ACTIVE' AND revokedAtEpochMillis IS NULL ORDER BY version DESC LIMIT 1")
    suspend fun activeQrForCheckpoint(checkpointId: String): QrCredentialEntity?

    @Query("UPDATE qr_credentials SET status = 'REVOKED', revokedAtEpochMillis = :revokedAt WHERE checkpointId = :checkpointId AND status = 'ACTIVE' AND revokedAtEpochMillis IS NULL")
    suspend fun revokeActiveQr(checkpointId: String, revokedAt: Long)

    @Query("SELECT COALESCE(MAX(sequenceHint), 0) FROM checkpoints WHERE locationNodeId = :locationNodeId AND archivedAtEpochMillis IS NULL")
    suspend fun maxSequenceForLocation(locationNodeId: String): Int
}

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM patrol_schedules WHERE active = 1 AND archivedAtEpochMillis IS NULL ORDER BY startMinuteOfDay")
    fun observeActiveSchedules(): Flow<List<PatrolScheduleEntity>>

    @Query("SELECT * FROM patrol_schedules WHERE id IN (:ids) AND active = 1 AND archivedAtEpochMillis IS NULL")
    fun observeSchedulesByIds(ids: List<String>): Flow<List<PatrolScheduleEntity>>

    @Query("SELECT * FROM patrol_schedules WHERE active = 1 AND archivedAtEpochMillis IS NULL ORDER BY startMinuteOfDay")
    suspend fun activeSchedules(): List<PatrolScheduleEntity>

    @Query("SELECT * FROM patrol_schedules WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): PatrolScheduleEntity?

    @Query("SELECT checkpointId FROM schedule_checkpoints WHERE scheduleId = :scheduleId ORDER BY sequence")
    suspend fun checkpointIds(scheduleId: String): List<String>

    @Query("SELECT COALESCE(MAX(sequence), 0) + 1 FROM schedule_checkpoints WHERE scheduleId = :scheduleId")
    suspend fun nextCheckpointSequence(scheduleId: String): Int

    @Query("SELECT userId FROM schedule_assignees WHERE scheduleId = :scheduleId")
    suspend fun assigneeIds(scheduleId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSchedule(schedule: PatrolScheduleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCheckpointLink(link: ScheduleCheckpointEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAssignee(link: ScheduleAssigneeEntity)

    @Query("DELETE FROM schedule_checkpoints WHERE scheduleId = :scheduleId")
    suspend fun deleteCheckpointLinks(scheduleId: String)

    @Query("UPDATE patrol_schedules SET active = 0, archivedAtEpochMillis = :archivedAt, updatedAtEpochMillis = :archivedAt WHERE id = :scheduleId")
    suspend fun deactivateSchedule(scheduleId: String, archivedAt: Long)
}

@Dao
interface PatrolDao {
    @Query("SELECT * FROM shifts WHERE userId = :userId AND endedAtEpochMillis IS NULL ORDER BY startedAtEpochMillis DESC LIMIT 1")
    suspend fun activeShift(userId: String): ShiftEntity?

    @Query("SELECT * FROM shifts WHERE endedAtEpochMillis IS NULL ORDER BY startedAtEpochMillis DESC LIMIT 1")
    suspend fun anyActiveShift(): ShiftEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertShift(shift: ShiftEntity)

    @Update
    suspend fun updateShift(shift: ShiftEntity)

    @Query("SELECT * FROM patrol_executions WHERE userId = :userId AND status = 'IN_PROGRESS' ORDER BY startedAtEpochMillis DESC LIMIT 1")
    suspend fun activeExecution(userId: String): PatrolExecutionEntity?

    @Query("SELECT * FROM patrol_executions WHERE id = :id LIMIT 1")
    suspend fun findExecution(id: String): PatrolExecutionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExecution(execution: PatrolExecutionEntity)

    @Update
    suspend fun updateExecution(execution: PatrolExecutionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertVisit(visit: CheckpointVisitEntity): Long

    @Query("SELECT * FROM checkpoint_visits WHERE executionId = :executionId ORDER BY scannedAtEpochMillis")
    fun observeVisits(executionId: String): Flow<List<CheckpointVisitEntity>>

    @Query("SELECT * FROM checkpoint_visits WHERE executionId = :executionId ORDER BY scannedAtEpochMillis")
    suspend fun visits(executionId: String): List<CheckpointVisitEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOccurrence(occurrence: OccurrenceEntity)

    @Query("SELECT COUNT(*) FROM patrol_executions")
    fun observeExecutionCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM patrol_executions WHERE status IN ('INCOMPLETE','LATE','MISSED','SUSPICIOUS')")
    fun observeProblemExecutionCount(): Flow<Int>
}

@Dao
interface AlertDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alert: AlertEntity)

    @Query("SELECT * FROM alerts ORDER BY createdAtEpochMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<AlertEntity>>

    @Query("SELECT COUNT(*) FROM alerts WHERE resolved = 0")
    fun observeUnresolvedCount(): Flow<Int>

    @Query("UPDATE alerts SET resolved = 1, resolvedAtEpochMillis = :resolvedAt WHERE id = :id")
    suspend fun resolve(id: String, resolvedAt: Long)
}

@Dao
interface AuditDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(log: AuditLogEntity)
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: OutboxEventEntity): Long

    @Query("SELECT * FROM outbox_events WHERE status = 'PENDING' ORDER BY createdAtEpochMillis LIMIT :limit")
    suspend fun pendingBatch(limit: Int): List<OutboxEventEntity>

    @Query("UPDATE outbox_events SET status = 'SYNCED', syncedAtEpochMillis = :at, lastError = NULL WHERE eventId = :eventId")
    suspend fun markSynced(eventId: String, at: Long)

    @Query("UPDATE outbox_events SET attempts = attempts + 1, lastAttemptAtEpochMillis = :at, lastError = :error WHERE eventId = :eventId")
    suspend fun markRetry(eventId: String, at: Long, error: String)

    @Query("UPDATE outbox_events SET status = 'FAILED_PERMANENT', attempts = attempts + 1, lastAttemptAtEpochMillis = :at, lastError = :error WHERE eventId = :eventId")
    suspend fun markPermanentFailure(eventId: String, at: Long, error: String)

    @Query("UPDATE outbox_events SET status = 'PENDING', lastError = NULL WHERE eventId = :eventId")
    suspend fun requeue(eventId: String)

    @Query("SELECT COUNT(*) FROM outbox_events WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox_events WHERE status = 'FAILED_PERMANENT'")
    fun observePermanentFailureCount(): Flow<Int>
}
