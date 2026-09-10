package br.com.porteirinho.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 3,
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
        private val Migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE users ADD COLUMN mustChangePin INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE users ADD COLUMN pinIssuedAtEpochMillis INTEGER")
                db.execSQL("ALTER TABLE users ADD COLUMN pinChangedAtEpochMillis INTEGER")
                db.execSQL("ALTER TABLE checkpoints ADD COLUMN fixed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE checkpoints ADD COLUMN systemKey TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_checkpoints_systemKey ON checkpoints(systemKey)")
                db.execSQL("ALTER TABLE patrol_schedules ADD COLUMN fixedSlot INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE patrol_schedules ADD COLUMN startToleranceMinutes INTEGER NOT NULL DEFAULT 10")
                db.execSQL("ALTER TABLE patrol_schedules ADD COLUMN endToleranceMinutes INTEGER NOT NULL DEFAULT 10")
                db.execSQL("ALTER TABLE patrol_schedules ADD COLUMN targetDurationMinutes INTEGER NOT NULL DEFAULT 60")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_patrol_schedules_fixedSlot ON patrol_schedules(fixedSlot)")
                db.execSQL("ALTER TABLE occurrences ADD COLUMN checkpointId TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_occurrences_checkpointId ON occurrences(checkpointId)")
            }
        }

        private val Migration2To3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE users ADD COLUMN pinEncoding TEXT NOT NULL DEFAULT 'BASE64'")
                db.execSQL("ALTER TABLE users ADD COLUMN pinIterations INTEGER NOT NULL DEFAULT 120000")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "porteirinho.db")
                .addMigrations(Migration1To2, Migration2To3)
                .build()
    }
}
