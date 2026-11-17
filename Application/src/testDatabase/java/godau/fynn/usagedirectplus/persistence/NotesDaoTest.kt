package godau.fynn.usagedirectplus.persistence

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotesDaoTest {

    private lateinit var database: HistoryDatabase
    private lateinit var dao: NotesDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HistoryDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.getNotesDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun upsert_insertsNoteAndResolvesApplicationId() {
        val id = dao.upsert(
            Note(applicationId = "com.example.app", day = 4L, tag = "focus", body = "Deep work")
        )

        assertThat(id).isGreaterThan(0L)
        val notes = dao.getAll()
        assertThat(notes).hasSize(1)
        val note = notes.first()
        assertThat(note.id).isEqualTo(id)
        assertThat(note.applicationId).isEqualTo("com.example.app")
        assertThat(note.day).isEqualTo(4L)
        assertThat(note.tag).isEqualTo("focus")
        assertThat(note.body).isEqualTo("Deep work")
        assertThat(note.createdAt).isGreaterThan(0L)
        assertThat(note.updatedAt).isGreaterThan(0L)
    }

    @Test
    fun upsert_preservesIdAndCreatedAtOnUpdate() {
        val id = dao.upsert(Note(applicationId = "com.example.app", body = "First"))
        val original = dao.getAll().first()

        val sameId = dao.upsert(
            original.copy(tag = "edited", body = "Second")
        )

        assertThat(sameId).isEqualTo(id)
        val updated = dao.getAll().single()
        assertThat(updated.createdAt).isEqualTo(original.createdAt)
        assertThat(updated.updatedAt).isAtLeast(original.updatedAt)
        assertThat(updated.body).isEqualTo("Second")
        assertThat(updated.tag).isEqualTo("edited")
        assertThat(dao.getAmount()).isEqualTo(1)
    }

    @Test
    fun upsert_blankTagIsStoredAsNull() {
        dao.upsert(Note(body = "No tag", tag = "   "))

        assertThat(dao.getAll().single().tag).isNull()
    }

    @Test
    fun upsert_allowsNoteWithoutAppOrDay() {
        val id = dao.upsert(Note(body = "Loose thought"))

        val note = dao.getAll().single()
        assertThat(note.id).isEqualTo(id)
        assertThat(note.applicationId).isNull()
        assertThat(note.day).isNull()
    }

    @Test
    fun search_matchesBodyTagAndPackage() {
        dao.upsert(Note(applicationId = "com.example.app", tag = "focus", body = "Morning planning"))
        dao.upsert(Note(applicationId = "com.other.app", tag = "errand", body = "Buy milk"))

        assertThat(dao.search("planning").map { it.body }).containsExactly("Morning planning")
        assertThat(dao.search("errand").map { it.body }).containsExactly("Buy milk")
        assertThat(dao.search("com.example").map { it.body }).containsExactly("Morning planning")
    }

    @Test
    fun getByApp_returnsOnlyMatchingNotes() {
        dao.upsert(Note(applicationId = "com.example.app", body = "App note"))
        dao.upsert(Note(applicationId = "com.other.app", body = "Other note"))

        val notes = dao.getByApp("com.example.app")

        assertThat(notes).hasSize(1)
        assertThat(notes.first().body).isEqualTo("App note")
    }

    @Test
    fun getByDay_returnsOnlyMatchingNotes() {
        dao.upsert(Note(day = 1L, body = "Day one"))
        dao.upsert(Note(day = 2L, body = "Day two"))

        assertThat(dao.getByDay(1L).map { it.body }).containsExactly("Day one")
    }

    @Test
    fun getByAppAndDay_combinesFilters() {
        dao.upsert(Note(applicationId = "com.example.app", day = 1L, body = "Match"))
        dao.upsert(Note(applicationId = "com.example.app", day = 2L, body = "Wrong day"))
        dao.upsert(Note(applicationId = "com.other.app", day = 1L, body = "Wrong app"))

        assertThat(dao.getByAppAndDay("com.example.app", 1L).map { it.body })
            .containsExactly("Match")
    }

    @Test
    fun getAll_ordersByUpdatedAtDescending() {
        dao.upsert(Note(body = "Older"))
        val older = dao.getAll().single()

        dao.upsert(older.copy(body = "Newer"))
        dao.upsert(Note(body = "Newest"))

        val bodies = dao.getAll().map { it.body }
        assertThat(bodies.first()).isEqualTo("Newest")
        assertThat(bodies).containsExactly("Newest", "Newer")
    }

    @Test
    fun deleteById_removesOnlyTargetNote() {
        val keep = dao.upsert(Note(body = "Keep"))
        val remove = dao.upsert(Note(body = "Remove"))

        dao.deleteById(remove)

        assertThat(dao.getAmount()).isEqualTo(1)
        assertThat(dao.getAll().single().id).isEqualTo(keep)
    }
}
