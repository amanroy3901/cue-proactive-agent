package com.cue.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.cue.domain.Album
import com.cue.domain.CallSession
import com.cue.domain.ChecklistEntry
import com.cue.domain.CueDao
import com.cue.domain.Habit
import com.cue.domain.HabitLog
import com.cue.domain.Item
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
        CallSession::class, Person::class, PendingItem::class, Habit::class, HabitLog::class],
    version = 1,
    exportSchema = false,
)
abstract class CueDatabase : RoomDatabase() {
    abstract fun dao(): CueDao

    companion object {
        @Volatile private var instance: CueDatabase? = null

        fun get(context: Context): CueDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, CueDatabase::class.java, "cue.db")
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
