package com.cue.domain

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * The memory contract. Room generates the implementation inside :memory, which is the only module that opens the
 * database; every other module talks to memory through this interface.
 */
@Dao
interface CueDao {
    // Items
    @Insert suspend fun insertItem(item: Item): Long
    @Update suspend fun updateItem(item: Item)
    @Query("SELECT * FROM item WHERE id = :id") suspend fun item(id: Long): Item?
    @Query("SELECT * FROM item WHERE contentHash = :hash LIMIT 1") suspend fun itemByHash(hash: String): Item?
    @Query("SELECT * FROM item WHERE albumId = :albumId ORDER BY createdAt") suspend fun itemsInAlbum(albumId: Long): List<Item>
    @Query("SELECT * FROM item WHERE albumId = :albumId ORDER BY createdAt") fun itemsInAlbumFlow(albumId: Long): Flow<List<Item>>
    @Query("SELECT * FROM item ORDER BY createdAt DESC") fun allItemsFlow(): Flow<List<Item>>
    @Query("SELECT * FROM item ORDER BY createdAt DESC") suspend fun allItems(): List<Item>
    @Query("SELECT * FROM item WHERE type = :type ORDER BY createdAt DESC") fun itemsOfTypeFlow(type: String): Flow<List<Item>>
    @Query("SELECT * FROM item WHERE type = :type ORDER BY createdAt DESC") suspend fun itemsOfType(type: String): List<Item>
    @Query("SELECT * FROM item WHERE kind = :kind ORDER BY createdAt DESC") suspend fun itemsOfKind(kind: String): List<Item>
    @Query("SELECT * FROM item WHERE expiresAt IS NOT NULL ORDER BY expiresAt") fun expiringFlow(): Flow<List<Item>>
    @Query("SELECT * FROM item WHERE expiresAt IS NOT NULL ORDER BY expiresAt") suspend fun expiring(): List<Item>
    @Query("SELECT * FROM item WHERE title LIKE :q OR body LIKE :q OR fields LIKE :q OR kind LIKE :q ORDER BY createdAt DESC LIMIT 20")
    suspend fun searchItems(q: String): List<Item>
    @Query("DELETE FROM item WHERE id = :id") suspend fun deleteItem(id: Long)

    // Checklists
    @Insert suspend fun insertEntry(entry: ChecklistEntry): Long
    @Update suspend fun updateEntry(entry: ChecklistEntry)
    @Query("SELECT * FROM checklist_entry WHERE itemId = :itemId ORDER BY checked, position") suspend fun entries(itemId: Long): List<ChecklistEntry>
    @Query("SELECT * FROM checklist_entry ORDER BY checked, position") fun allEntriesFlow(): Flow<List<ChecklistEntry>>
    @Query("SELECT * FROM checklist_entry WHERE checked = 0 ORDER BY position") suspend fun openEntries(): List<ChecklistEntry>
    @Query("SELECT * FROM checklist_entry WHERE id = :id") suspend fun entry(id: Long): ChecklistEntry?
    @Query("DELETE FROM checklist_entry WHERE id = :id") suspend fun deleteEntry(id: Long)

    // Albums
    @Insert suspend fun insertAlbum(album: Album): Long
    @Update suspend fun updateAlbum(album: Album)
    @Query("SELECT * FROM album WHERE id = :id") suspend fun album(id: Long): Album?
    @Query("SELECT * FROM album ORDER BY COALESCE(startAt, createdAt) DESC") suspend fun albums(): List<Album>
    @Query("SELECT * FROM album ORDER BY COALESCE(startAt, createdAt) DESC") fun albumsFlow(): Flow<List<Album>>
    @Query("SELECT * FROM album WHERE id = :id") fun albumFlow(id: Long): Flow<Album?>

    // Places
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertPlace(place: Place): Long
    @Query("SELECT * FROM place") suspend fun places(): List<Place>
    @Query("SELECT * FROM place") fun placesFlow(): Flow<List<Place>>
    @Query("SELECT * FROM place WHERE id = :id") suspend fun place(id: Long): Place?
    @Query("SELECT * FROM place WHERE kind = :kind LIMIT 1") suspend fun placeOfKind(kind: String): Place?
    @Query("SELECT * FROM place WHERE code = :code LIMIT 1") suspend fun placeByCode(code: String): Place?

    // Reminders
    @Insert suspend fun insertReminder(reminder: Reminder): Long
    @Update suspend fun updateReminder(reminder: Reminder)
    @Query("SELECT * FROM reminder WHERE id = :id") suspend fun reminder(id: Long): Reminder?
    @Query("SELECT * FROM reminder WHERE `key` = :key LIMIT 1") suspend fun reminderByKey(key: String): Reminder?
    @Query("SELECT * FROM reminder WHERE `key` IS NOT NULL") suspend fun derivedReminders(): List<Reminder>
    @Query("SELECT * FROM reminder WHERE status = 'SCHEDULED' ORDER BY fireAt") suspend fun scheduledReminders(): List<Reminder>
    @Query("SELECT * FROM reminder WHERE status = 'SCHEDULED' ORDER BY fireAt") fun scheduledRemindersFlow(): Flow<List<Reminder>>
    @Query("SELECT * FROM reminder WHERE albumId = :albumId") suspend fun remindersForAlbum(albumId: Long): List<Reminder>
    @Query("SELECT * FROM reminder WHERE kind = :kind ORDER BY fireAt DESC") suspend fun remindersOfKind(kind: String): List<Reminder>
    @Query("SELECT * FROM reminder ORDER BY fireAt DESC LIMIT 60") fun recentRemindersFlow(): Flow<List<Reminder>>

    // Events
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertEvent(event: TriggerEvent): Long
    @Update suspend fun updateEvent(event: TriggerEvent)
    @Query("SELECT * FROM trigger_event ORDER BY at DESC LIMIT 80") fun recentEventsFlow(): Flow<List<TriggerEvent>>
    @Query("SELECT COUNT(*) FROM trigger_event WHERE kind = :kind AND refId = :refId AND at > :since") suspend fun countEvents(kind: String, refId: Long, since: Long): Int

    // Calls
    @Insert suspend fun insertCall(call: CallSession): Long
    @Update suspend fun updateCall(call: CallSession)
    @Query("SELECT * FROM call_session WHERE id = :id") suspend fun call(id: Long): CallSession?
    @Query("SELECT * FROM call_session ORDER BY ringAt DESC LIMIT 30") fun recentCallsFlow(): Flow<List<CallSession>>
    @Query("SELECT * FROM call_session WHERE filed = 0 AND transcript != '' AND endedAt IS NOT NULL") suspend fun unfiledCalls(): List<CallSession>

    // People
    @Insert suspend fun insertPerson(person: Person): Long
    @Query("SELECT * FROM person WHERE normalized = :normalized LIMIT 1") suspend fun personByName(normalized: String): Person?
    @Query("SELECT * FROM person WHERE id = :id") suspend fun person(id: Long): Person?
    @Query("SELECT * FROM person ORDER BY name") fun peopleFlow(): Flow<List<Person>>
    @Insert suspend fun insertPending(item: PendingItem): Long
    @Update suspend fun updatePending(item: PendingItem)
    @Query("SELECT * FROM pending_item WHERE personId = :personId AND status = 'OPEN' ORDER BY createdAt DESC") suspend fun openPending(personId: Long): List<PendingItem>
    @Query("SELECT * FROM pending_item WHERE status = 'OPEN' ORDER BY createdAt DESC") fun openPendingFlow(): Flow<List<PendingItem>>
    @Query("SELECT * FROM pending_item WHERE status = 'OPEN' ORDER BY createdAt DESC") suspend fun allOpenPending(): List<PendingItem>

    // Habits
    @Insert suspend fun insertHabit(habit: Habit): Long
    @Query("SELECT * FROM habit ORDER BY createdAt") suspend fun habits(): List<Habit>
    @Query("SELECT * FROM habit ORDER BY createdAt") fun habitsFlow(): Flow<List<Habit>>
    @Query("SELECT * FROM habit WHERE name LIKE :name LIMIT 1") suspend fun habitByName(name: String): Habit?
    @Query("SELECT * FROM habit WHERE id = :id") suspend fun habit(id: Long): Habit?
    @Update suspend fun updateHabit(habit: Habit)
    @Query("DELETE FROM habit WHERE id = :id") suspend fun deleteHabit(id: Long)
    @Query("DELETE FROM habit_log WHERE habitId = :habitId") suspend fun deleteHabitLogs(habitId: Long)
    @Query("SELECT * FROM pending_item WHERE id = :id") suspend fun pending(id: Long): PendingItem?
    @Query("DELETE FROM pending_item WHERE id = :id") suspend fun deletePending(id: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertHabitLog(log: HabitLog): Long
    @Query("SELECT * FROM habit_log WHERE habitId = :habitId ORDER BY day DESC LIMIT 60") suspend fun habitLogs(habitId: Long): List<HabitLog>
    @Query("SELECT * FROM habit_log ORDER BY day DESC") fun habitLogsFlow(): Flow<List<HabitLog>>
}
