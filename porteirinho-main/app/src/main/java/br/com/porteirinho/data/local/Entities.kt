package br.com.porteirinho.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object UserRole {
    const val ADMIN = "ADMIN"
    const val GATEKEEPER = "GATEKEEPER"
}

object SyncStatus {
    const val PENDING = "PENDING"
    const val SYNCED = "SYNCED"
    const val FAILED_PERMANENT = "FAILED_PERMANENT"
}

object PatrolStatus {
    const val IN_PROGRESS = "IN_PROGRESS"
    const val COMPLETED = "COMPLETED"
    const val INCOMPLETE = "INCOMPLETE"
    const val LATE = "LATE"
    const val MISSED = "MISSED"
    const val SUSPICIOUS = "SUSPICIOUS"
    const val CANCELLED = "CANCELLED"
}

object QrStatus {
    const val ACTIVE = "ACTIVE"
    const val REVOKED = "REVOKED"
}

@Entity(tableName = "users", indices = [Index("role"), Index("active")])
data class UserEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val photoUrl: String? = null,
    val role: String,
    val active: Boolean = true,
    val pinSaltBase64: String,
    val pinHashBase64: String,
    val mustChangePin: Boolean = false,
    val pinIssuedAtEpochMillis: Long? = null,
    val pinChangedAtEpochMillis: Long? = null,
    val failedPinAttempts: Int = 0,
    val lockedUntilEpochMillis: Long? = null,
    val archivedAtEpochMillis: Long? = null,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "location_nodes",
    foreignKeys = [
        ForeignKey(
            entity = LocationNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("parentId"), Index("type"), Index("archivedAtEpochMillis")],
)
data class LocationNodeEntity(
    @PrimaryKey val id: String,
    val parentId: String? = null,
    val type: String,
    val name: String,
    val active: Boolean = true,
    val archivedAtEpochMillis: Long? = null,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "checkpoints",
    foreignKeys = [
        ForeignKey(
            entity = LocationNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["locationNodeId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("locationNodeId"), Index("active"), Index("systemKey")],
)
data class CheckpointEntity(
    @PrimaryKey val id: String,
    val locationNodeId: String,
    val name: String,
    val description: String? = null,
    val sequenceHint: Int = 0,
    val minimumTravelSecondsFromPrevious: Int = 0,
    val fixed: Boolean = false,
    val systemKey: String? = null,
    val active: Boolean = true,
    val archivedAtEpochMillis: Long? = null,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "qr_credentials",
    foreignKeys = [
        ForeignKey(
            entity = CheckpointEntity::class,
            parentColumns = ["id"],
            childColumns = ["checkpointId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("checkpointId"), Index(value = ["tokenHash"], unique = true), Index("status")],
)
data class QrCredentialEntity(
    @PrimaryKey val id: String,
    val checkpointId: String,
    val tokenHash: String,
    val version: Int,
    val status: String = QrStatus.ACTIVE,
    val issuedAtEpochMillis: Long,
    val revokedAtEpochMillis: Long? = null,
)

@Entity(tableName = "devices", indices = [Index(value = ["publicId"], unique = true), Index("active")])
data class DeviceEntity(
    @PrimaryKey val id: String,
    val publicId: String,
    val name: String,
    val active: Boolean = true,
    val lastActivityAtEpochMillis: Long? = null,
    val lastSyncAtEpochMillis: Long? = null,
    val archivedAtEpochMillis: Long? = null,
)

@Entity(
    tableName = "patrol_schedules",
    foreignKeys = [
        ForeignKey(
            entity = LocationNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["propertyId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("propertyId"), Index("active"), Index("fixedSlot")],
)
data class PatrolScheduleEntity(
    @PrimaryKey val id: String,
    val propertyId: String,
    val name: String,
    val weekdaysCsv: String,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val toleranceMinutes: Int,
    val fixedSlot: Int = 0,
    val startToleranceMinutes: Int = 10,
    val endToleranceMinutes: Int = 10,
    val targetDurationMinutes: Int = 60,
    val active: Boolean = true,
    val archivedAtEpochMillis: Long? = null,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "schedule_checkpoints",
    primaryKeys = ["scheduleId", "checkpointId"],
    foreignKeys = [
        ForeignKey(
            entity = PatrolScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["scheduleId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CheckpointEntity::class,
            parentColumns = ["id"],
            childColumns = ["checkpointId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("checkpointId")],
)
data class ScheduleCheckpointEntity(
    val scheduleId: String,
    val checkpointId: String,
    val sequence: Int,
)

@Entity(
    tableName = "schedule_assignees",
    primaryKeys = ["scheduleId", "userId"],
    foreignKeys = [
        ForeignKey(
            entity = PatrolScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["scheduleId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("userId")],
)
data class ScheduleAssigneeEntity(
    val scheduleId: String,
    val userId: String,
)

@Entity(
    tableName = "shifts",
    foreignKeys = [
        ForeignKey(entity = UserEntity::class, parentColumns = ["id"], childColumns = ["userId"]),
        ForeignKey(entity = DeviceEntity::class, parentColumns = ["id"], childColumns = ["deviceId"]),
    ],
    indices = [Index("userId"), Index("deviceId"), Index("endedAtEpochMillis")],
)
data class ShiftEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val deviceId: String,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long? = null,
)

@Entity(
    tableName = "patrol_executions",
    foreignKeys = [
        ForeignKey(entity = PatrolScheduleEntity::class, parentColumns = ["id"], childColumns = ["scheduleId"]),
        ForeignKey(entity = ShiftEntity::class, parentColumns = ["id"], childColumns = ["shiftId"]),
        ForeignKey(entity = UserEntity::class, parentColumns = ["id"], childColumns = ["userId"]),
        ForeignKey(entity = DeviceEntity::class, parentColumns = ["id"], childColumns = ["deviceId"]),
    ],
    indices = [Index("scheduleId"), Index("shiftId"), Index("userId"), Index("deviceId"), Index("status")],
)
data class PatrolExecutionEntity(
    @PrimaryKey val id: String,
    val scheduleId: String,
    val shiftId: String,
    val userId: String,
    val deviceId: String,
    val scheduledWindowStartEpochMillis: Long,
    val scheduledWindowEndEpochMillis: Long,
    val startedAtEpochMillis: Long,
    val startedElapsedRealtimeMillis: Long,
    val endedAtEpochMillis: Long? = null,
    val status: String = PatrolStatus.IN_PROGRESS,
    val suspicious: Boolean = false,
    val lastWallClockEpochMillis: Long,
    val lastElapsedRealtimeMillis: Long,
)

@Entity(
    tableName = "checkpoint_visits",
    foreignKeys = [
        ForeignKey(entity = PatrolExecutionEntity::class, parentColumns = ["id"], childColumns = ["executionId"]),
        ForeignKey(entity = CheckpointEntity::class, parentColumns = ["id"], childColumns = ["checkpointId"]),
        ForeignKey(entity = QrCredentialEntity::class, parentColumns = ["id"], childColumns = ["qrCredentialId"]),
    ],
    indices = [
        Index(value = ["executionId", "checkpointId"], unique = true),
        Index("checkpointId"),
        Index("qrCredentialId"),
    ],
)
data class CheckpointVisitEntity(
    @PrimaryKey val id: String,
    val executionId: String,
    val checkpointId: String,
    val qrCredentialId: String,
    val scannedAtEpochMillis: Long,
    val scannedAtElapsedRealtimeMillis: Long,
    val suspicious: Boolean = false,
    val suspicionReason: String? = null,
)

@Entity(
    tableName = "occurrences",
    foreignKeys = [
        ForeignKey(entity = PatrolExecutionEntity::class, parentColumns = ["id"], childColumns = ["executionId"]),
    ],
    indices = [Index("executionId"), Index("checkpointId")],
)
data class OccurrenceEntity(
    @PrimaryKey val id: String,
    val executionId: String,
    val checkpointId: String? = null,
    val category: String,
    val description: String,
    val localAttachmentPath: String? = null,
    val createdAtEpochMillis: Long,
)

@Entity(tableName = "alerts", indices = [Index("type"), Index("resolved"), Index("createdAtEpochMillis")])
data class AlertEntity(
    @PrimaryKey val id: String,
    val type: String,
    val description: String,
    val userId: String? = null,
    val executionId: String? = null,
    val deviceId: String? = null,
    val createdAtEpochMillis: Long,
    val resolved: Boolean = false,
    val resolvedAtEpochMillis: Long? = null,
)

@Entity(tableName = "audit_logs", indices = [Index("actorUserId"), Index("entityType"), Index("createdAtEpochMillis")])
data class AuditLogEntity(
    @PrimaryKey val id: String,
    val actorUserId: String,
    val operation: String,
    val entityType: String,
    val entityId: String,
    val previousValueJson: String? = null,
    val newValueJson: String? = null,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "outbox_events",
    indices = [Index(value = ["eventId"], unique = true), Index("status"), Index("createdAtEpochMillis")],
)
data class OutboxEventEntity(
    @PrimaryKey val eventId: String,
    val aggregateType: String,
    val aggregateId: String,
    val eventType: String,
    val payloadJson: String,
    val createdAtEpochMillis: Long,
    val createdAtElapsedRealtimeMillis: Long,
    val status: String = SyncStatus.PENDING,
    val attempts: Int = 0,
    val lastAttemptAtEpochMillis: Long? = null,
    val lastError: String? = null,
    val syncedAtEpochMillis: Long? = null,
)
