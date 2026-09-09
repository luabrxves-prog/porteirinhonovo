package br.com.porteirinho.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        UserEntity::class,
        LocationNodeEntity::class,
        CheckpointEntity::class,
        QrCredentialEntity::class,
        DeviceEntity::class,
        PatrolScheduleEntity::class,
        ScheduleCheckpointEntity::class,
        ScheduleAssigneeEntity::class,
        ShiftEntity::class,
        PatrolExecutionEntity::class,
        CheckpointVisitEntity::class,
        OccurrenceEntity::class,
        AlertEntity::class,
        AuditLogEntity::class,
        OutboxEventEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun directoryDao(): DirectoryDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun patrolDao(): PatrolDao
    abstract fun alertDao(): AlertDao
    abstract fun auditDao(): AuditDao
    abstract fun outboxDao(): OutboxDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "porteirinho.db")
                .build()
    }
}
