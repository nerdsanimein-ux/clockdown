package com.rishabh.clockdown

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

const val MANUAL = "manual"
const val AMIZONE = "amizone"

@Entity(tableName = "events", indices = [Index(value = ["amizoneId"], unique = true)])
data class Event(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val startMillis: Long,
    /** 0 = no alarm, only the notification at start. Ignored for amizone events (see class alarm settings). */
    val alarmMinutes: Int = 15,
    val source: String = MANUAL,
    val amizoneId: Long? = null,
    val courseCode: String? = null,
    val faculty: String? = null,
    val room: String? = null,
    val endMillis: Long? = null,
    /** Look chosen in the editor; null means derive a stable default (see Style.kt). */
    val emoji: String? = null,
    val colorIdx: Int? = null,
    val progressStyle: Int? = null,
    /** A [WidgetStyle] id, or null to follow the default (see resolveStyle). */
    val widgetStyle: String? = null,
)

@Dao
abstract class EventDao {
    @Query("SELECT * FROM events ORDER BY startMillis")
    abstract fun all(): Flow<List<Event>>

    @Query("SELECT * FROM events WHERE startMillis > :now ORDER BY startMillis")
    abstract fun upcoming(now: Long): List<Event>

    @Query("SELECT * FROM events WHERE id = :id")
    abstract fun byId(id: Int): Event?

    /** Returns the new row id for an insert, or -1 for an update of an existing row. */
    @Upsert
    abstract fun upsert(event: Event): Long

    @Delete
    abstract fun delete(event: Event)

    @Query("SELECT * FROM events WHERE source = 'amizone'")
    abstract fun amizone(): List<Event>

    @Query("DELETE FROM events WHERE id IN (:ids)")
    abstract fun deleteIds(ids: List<Int>)

    @Upsert
    abstract fun upsertAll(events: List<Event>)

    /**
     * Replaces future amizone events starting in [now, end) with [fresh]. Manual events are never touched.
     * Existing rows keep their id (matched by amizoneId) so their alarms/notifications stay stable.
     * Returns the ids that were removed, so the caller can cancel their alarms.
     */
    @Transaction
    open fun replaceAmizone(now: Long, end: Long, fresh: List<Event>): List<Int> {
        val existing = amizone()
        val idFor = existing.associate { it.amizoneId to it.id }
        val freshIds = fresh.map { it.amizoneId }.toSet()
        val gone = existing.filter { it.startMillis in (now + 1) until end && it.amizoneId !in freshIds }.map { it.id }
        deleteIds(gone)
        upsertAll(fresh.map { it.copy(id = idFor[it.amizoneId] ?: 0) })
        return gone
    }

    /** Disconnect: drops every class event, returning their ids. */
    @Transaction
    open fun deleteAmizone(): List<Int> {
        val ids = amizone().map { it.id }
        deleteIds(ids)
        return ids
    }
}

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE events ADD COLUMN source TEXT NOT NULL DEFAULT 'manual'")
        db.execSQL("ALTER TABLE events ADD COLUMN amizoneId INTEGER")
        db.execSQL("ALTER TABLE events ADD COLUMN courseCode TEXT")
        db.execSQL("ALTER TABLE events ADD COLUMN faculty TEXT")
        db.execSQL("ALTER TABLE events ADD COLUMN room TEXT")
        db.execSQL("ALTER TABLE events ADD COLUMN endMillis INTEGER")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_events_amizoneId ON events (amizoneId)")
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE events ADD COLUMN emoji TEXT")
        db.execSQL("ALTER TABLE events ADD COLUMN colorIdx INTEGER")
        db.execSQL("ALTER TABLE events ADD COLUMN progressStyle INTEGER")
    }
}

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE events ADD COLUMN widgetStyle TEXT")
    }
}

@Database(entities = [Event::class], version = 4, exportSchema = true)
abstract class AppDb : RoomDatabase() {
    abstract fun events(): EventDao

    companion object {
        @Volatile private var db: AppDb? = null

        fun get(ctx: Context): EventDao = (db ?: synchronized(this) {
            db ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "clockdown.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build().also { db = it }
        }).events()
    }
}
