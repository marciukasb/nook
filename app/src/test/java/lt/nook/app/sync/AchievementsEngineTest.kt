package lt.nook.app.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lt.nook.app.data.Kind
import lt.nook.app.data.NookDb
import lt.nook.app.data.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AchievementsEngineTest {
    private lateinit var db: NookDb
    private val fake = FakeTodoist()

    private fun engine() = SyncEngine(db, fake, { "good" }, { Settings.DEFAULTS }, { 0L })

    @Before fun up() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NookDb::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun down() = db.close()

    private suspend fun SyncEngine.list() = items(Kind.ACHIEVEMENTS).first()

    // ---- task 2: the list arrives, and its absence bothers nothing ----

    @Test fun descriptionIsSynced() = runTest {
        fake.addAchievementsProject()
        fake.item(fake.achievements, "Weld", responsible = fake.partner, description = "Course first")
        val e = engine(); e.flush()
        val i = e.list().single()
        assertEquals(listOf("Weld", "Sam", "Course first"), listOf(i.content, i.assignee, i.description))
    }

    @Test fun missingAchievements_isStillSynced() = runTest {
        fake.item(fake.groceries, "Milk")
        val e = engine(); e.flush()
        assertEquals(Status.Synced, e.state.value.status)
        assertEquals(listOf("Milk"), e.items(Kind.GROCERIES).first().map { it.content })
        assertNull(e.projectId(Kind.ACHIEVEMENTS))
        assertTrue(e.list().isEmpty())
        fake.renameProject(fake.shopping, "Shop")                       // a really missing list still counts
        e.resetSync(); e.flush(force = true)
        assertEquals(Status.ProjectsMissing(listOf("Shopping")), e.state.value.status)
    }

    @Test fun peopleOfEachProject() = runTest {
        fake.addAchievementsProject(members = listOf(fake.me))
        val e = engine(); e.flush()
        assertEquals(listOf("Alex", "Sam"), e.people(Kind.TASKS).first().map { it.name })
        assertEquals(listOf("Alex"), e.people(Kind.ACHIEVEMENTS).first().map { it.name })
    }

    @Test fun ourProjectsChanged_nextSyncIsFull() = runTest {
        val e = engine(); e.flush()
        // A project that existed under another name all along: its items are older than our sync token.
        fake.addAchievementsProject(name = "Goals")
        fake.item(fake.achievements, "Weld")
        e.flush()
        assertTrue(e.list().isEmpty())
        fake.renameProject(fake.achievements, "Achievements")
        e.flush()
        assertEquals(listOf("Weld"), e.list().map { it.content })
        assertEquals(Status.Synced, e.state.value.status)
    }
}
