package com.cue.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cue.domain.Album
import com.cue.domain.CallSession
import com.cue.domain.ChecklistEntry
import com.cue.domain.CueDao
import com.cue.domain.Habit
import com.cue.domain.HabitLog
import com.cue.domain.Item
import com.cue.domain.Measurement
import com.cue.domain.ProfileFact
import com.cue.domain.StandingRule
import com.cue.domain.SpaceBlock
import com.cue.domain.BlockRecord
import com.cue.domain.PendingItem
import com.cue.domain.Person
import com.cue.domain.Place
import com.cue.domain.Reminder
import com.cue.domain.TriggerEvent
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** The only place the database is opened. Everything else uses [CueDao]. */
@Database(
    entities = [Item::class, ChecklistEntry::class, Album::class, Place::class, Reminder::class, TriggerEvent::class,
        CallSession::class, Person::class, PendingItem::class, Habit::class, HabitLog::class,
        ProfileFact::class, Measurement::class, StandingRule::class, SpaceBlock::class, BlockRecord::class],
    version = 4,
    exportSchema = false,
)
abstract class CueDatabase : RoomDatabase() {
    abstract fun dao(): CueDao

    companion object {
        @Volatile private var instance: CueDatabase? = null

        fun get(context: Context): CueDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, CueDatabase::class.java, "cue.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration(true)
                .build().also { instance = it }
        }
    }
}

/** App-private storage for copied documents and thumbnails. */
class FileStore(context: Context) {
    private val dir = File(context.filesDir, "media").apply { mkdirs() }

    fun save(input: InputStream, ext: String): Pair<File, String> {
        val tmp = File(dir, "incoming-${System.nanoTime()}.$ext")
        val md = MessageDigest.getInstance("SHA-256")
        input.use { ins ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
        }
        val hash = md.digest().joinToString("") { "%02x".format(it) }.take(32)
        val dest = File(dir, "$hash.$ext")
        if (dest.exists()) tmp.delete() else tmp.renameTo(dest)
        return dest to hash
    }

    fun wipe() { dir.deleteRecursively(); dir.mkdirs() }
}

/** Version 2 adds the profile, health readings and standing rules; everything saved before is kept. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `profile_fact` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `key` TEXT NOT NULL, `category` TEXT NOT NULL, `text` TEXT NOT NULL, `source` TEXT NOT NULL, `confirmed` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_profile_fact_key` ON `profile_fact` (`key`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `measurement` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `value` REAL NOT NULL, `value2` REAL, `unit` TEXT NOT NULL, `context` TEXT NOT NULL, `takenAt` INTEGER NOT NULL, `source` TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_measurement_kind` ON `measurement` (`kind`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_measurement_takenAt` ON `measurement` (`takenAt`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `standing_rule` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `trigger` TEXT NOT NULL, `match` TEXT NOT NULL, `text` TEXT NOT NULL, `fires` INTEGER NOT NULL, `maxFires` INTEGER NOT NULL, `lastFiredAt` INTEGER, `expiresAt` INTEGER, `active` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
    }
}

/** Version 3: habits can be paused and get a daily check-in time; habit days can be marked paused. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `habit` ADD COLUMN `pausedFrom` INTEGER")
        db.execSQL("ALTER TABLE `habit` ADD COLUMN `pausedUntil` INTEGER")
        db.execSQL("ALTER TABLE `habit` ADD COLUMN `checkMinutes` INTEGER")
        db.execSQL("ALTER TABLE `habit_log` ADD COLUMN `paused` INTEGER NOT NULL DEFAULT 0")
    }
}

/** Version 4: Spaces. Albums get blocks (fixed mini-apps) and block records (expenses, itinerary stops). */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `space_block` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `albumId` INTEGER NOT NULL, `type` TEXT NOT NULL, `title` TEXT NOT NULL, `position` INTEGER NOT NULL, `config` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_space_block_albumId` ON `space_block` (`albumId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `block_record` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `blockId` INTEGER NOT NULL, `albumId` INTEGER NOT NULL, `data` TEXT NOT NULL, `at` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_block_record_blockId` ON `block_record` (`blockId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_block_record_albumId` ON `block_record` (`albumId`)")
    }
}
