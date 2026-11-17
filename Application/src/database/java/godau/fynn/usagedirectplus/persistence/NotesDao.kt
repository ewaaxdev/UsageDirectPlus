package godau.fynn.usagedirectplus.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class NotesDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract fun insertStored(notes: List<StoredNote>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract fun insertApps(apps: List<StoredApp>)

    @Query("SELECT * FROM apps WHERE applicationId IN (:applicationIds)")
    protected abstract fun getAppsByApplicationIds(applicationIds: List<String>): List<StoredApp>

    @Query(
        "SELECT notes.id AS id, apps.applicationId AS applicationId, notes.day AS day, " +
            "notes.createdAt AS createdAt, notes.updatedAt AS updatedAt, notes.tag AS tag, notes.body AS body " +
            "FROM notes " +
            "LEFT JOIN apps ON apps.id = notes.appId " +
            "ORDER BY notes.updatedAt DESC, notes.id DESC"
    )
    abstract fun getAll(): List<Note>

    @Query(
        "SELECT notes.id AS id, apps.applicationId AS applicationId, notes.day AS day, " +
            "notes.createdAt AS createdAt, notes.updatedAt AS updatedAt, notes.tag AS tag, notes.body AS body " +
            "FROM notes " +
            "LEFT JOIN apps ON apps.id = notes.appId " +
            "WHERE notes.body LIKE '%' || :query || '%' " +
            "OR notes.tag LIKE '%' || :query || '%' " +
            "OR apps.applicationId LIKE '%' || :query || '%' " +
            "ORDER BY notes.updatedAt DESC, notes.id DESC"
    )
    abstract fun search(query: String): List<Note>

    @Query(
        "SELECT notes.id AS id, apps.applicationId AS applicationId, notes.day AS day, " +
            "notes.createdAt AS createdAt, notes.updatedAt AS updatedAt, notes.tag AS tag, notes.body AS body " +
            "FROM notes " +
            "LEFT JOIN apps ON apps.id = notes.appId " +
            "WHERE apps.applicationId = :applicationId " +
            "ORDER BY notes.updatedAt DESC, notes.id DESC"
    )
    abstract fun getByApp(applicationId: String): List<Note>

    @Query(
        "SELECT notes.id AS id, apps.applicationId AS applicationId, notes.day AS day, " +
            "notes.createdAt AS createdAt, notes.updatedAt AS updatedAt, notes.tag AS tag, notes.body AS body " +
            "FROM notes " +
            "LEFT JOIN apps ON apps.id = notes.appId " +
            "WHERE notes.day = :day " +
            "ORDER BY notes.updatedAt DESC, notes.id DESC"
    )
    abstract fun getByDay(day: Long): List<Note>

    @Query(
        "SELECT notes.id AS id, apps.applicationId AS applicationId, notes.day AS day, " +
            "notes.createdAt AS createdAt, notes.updatedAt AS updatedAt, notes.tag AS tag, notes.body AS body " +
            "FROM notes " +
            "LEFT JOIN apps ON apps.id = notes.appId " +
            "WHERE apps.applicationId = :applicationId AND notes.day = :day " +
            "ORDER BY notes.updatedAt DESC, notes.id DESC"
    )
    abstract fun getByAppAndDay(applicationId: String, day: Long): List<Note>

    @Query("SELECT COUNT(*) FROM notes")
    abstract fun getAmount(): Int

    @Query("DELETE FROM notes WHERE id = :id")
    abstract fun deleteById(id: Long)

    /**
     * Inserts or updates a note. A note with [Note.id] == 0 is inserted; otherwise it is
     * replaced in place. [Note.createdAt] is preserved for updates; on insert the current
     * wall clock is stored for both timestamps.
     */
    @Transaction
    open fun upsert(note: Note): Long {
        val now = System.currentTimeMillis()
        val appId = note.applicationId?.let { resolveAppId(it) }
        val stored = StoredNote(
            id = note.id,
            appId = appId,
            day = note.day,
            createdAt = if (note.createdAt > 0) note.createdAt else now,
            updatedAt = if (note.id > 0) now else (if (note.updatedAt > 0) note.updatedAt else now),
            tag = note.tag?.takeIf { it.isNotBlank() },
            body = note.body
        )
        return insertStored(listOf(stored)).first()
    }

    private fun resolveAppId(applicationId: String): Long {
        insertApps(listOf(StoredApp(applicationId = applicationId)))
        val app = getAppsByApplicationIds(listOf(applicationId)).firstOrNull()
        return app?.id ?: throw IllegalStateException("App id could not be resolved for $applicationId")
    }
}
