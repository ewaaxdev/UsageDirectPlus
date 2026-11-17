package godau.fynn.usagedirectplus.persistence

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.runner.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import godau.fynn.usagedirectplus.SimpleUsageStat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sourceName = "import-source-db"
    private val targetName = HistoryDatabase.DATABASE_NAME

    @After
    fun tearDown() {
        context.deleteDatabase(sourceName)
        context.deleteDatabase(targetName)
    }

    @Test
    fun merge_addsNewRowsAndNeverDeletesExistingData() {
        val target = Room.databaseBuilder(context, HistoryDatabase::class.java, targetName)
            .allowMainThreadQueries()
            .build()
        target.getUsageStatsDao().insert(listOf(SimpleUsageStat(1L, 111L, "com.existing.app")))
        target.getNotesDao().upsert(
            Note(applicationId = "com.existing.app", body = "Existing note")
        )
        val existingNoteId = target.getNotesDao().getAll().single().id
        target.close()

        val source = Room.databaseBuilder(context, HistoryDatabase::class.java, sourceName)
            .allowMainThreadQueries()
            .build()
        source.getUsageStatsDao().insert(
            listOf(
                SimpleUsageStat(2L, 222L, "com.source.app"),
                SimpleUsageStat(1L, 111L, "com.existing.app")
            )
        )
        source.getNotesDao().upsert(
            Note(applicationId = "com.source.app", day = 3L, tag = "imported", body = "Imported note")
        )
        source.getNotesDao().upsert(Note(body = "Loose imported note"))
        source.close()

        SQLiteDatabase.openDatabase(
            context.getDatabasePath(sourceName).path,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use { database ->
            database.query("PRAGMA wal_checkpoint(FULL)").use { }
        }

        val result = Import.merge(context, Uri.fromFile(context.getDatabasePath(sourceName)))

        assertThat(result.usageStats).isEqualTo(1)
        assertThat(result.notes).isEqualTo(2)
        assertThat(result.total).isEqualTo(3)

        val merged = Room.databaseBuilder(context, HistoryDatabase::class.java, targetName)
            .allowMainThreadQueries()
            .build()
        try {
            assertThat(merged.getUsageStatsDao().getTotalTimeUsed()).isEqualTo(333L)

            val notes = merged.getNotesDao().getAll()
            assertThat(notes).hasSize(3)
            assertThat(notes.map { it.body }).contains("Existing note")
            assertThat(notes.map { it.body }).contains("Imported note")
            assertThat(notes.map { it.body }).contains("Loose imported note")
            assertThat(notes.map { it.id }).contains(existingNoteId)
        } finally {
            merged.close()
        }
    }

    @Test
    fun merge_rejectsFileThatIsNotAUsageDatabase() {
        val bogus = java.io.File(context.cacheDir, "bogus-import.sqlite3")
        bogus.writeText("not a database")

        try {
            Import.merge(context, Uri.fromFile(bogus))
            org.junit.Assert.fail("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertThat(expected).hasMessageThat().contains("UsageDirectPlus database")
        } finally {
            bogus.delete()
        }
    }
}
