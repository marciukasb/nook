package lt.nook.app.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lt.nook.app.core.isDoneSection
import lt.nook.app.data.Kind
import lt.nook.app.data.NookDb
import lt.nook.app.data.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

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

    // ---- task 3: achieve, undo, reopen, describe ----

    private val day = LocalDate.of(2026, 11, 25)
    private fun sent() = fake.received.flatten().map { it.type }
    private fun doneId() = fake.sectionsOf(fake.achievements).single { isDoneSection(it.name) }.id
    private suspend fun ready(desc: String = "", section: String? = null): Pair<SyncEngine, String> {
        fake.addAchievementsProject()
        val id = fake.item(fake.achievements, "Weld", section, responsible = fake.me, description = desc)
        val e = engine(); e.flush()
        return e to id
    }
    private suspend fun SyncEngine.achieved(id: String, sync: Boolean = true) = (achieve(id, day, sync) as TickResult.Achieved).undo

    @Test fun achieve_movesAndDates_neverCloses() = runTest {
        val (e, id) = ready("Course first")
        e.achieved(id)
        val i = fake.get(id)!!
        assertEquals(listOf(doneId(), "Course first\nDone: 25 Nov 2026", false), listOf(i.sectionId, i.description, i.checked))
        assertEquals(listOf("section_add", "item_move", "item_update"), sent())
        assertEquals(listOf("Weld"), e.achievements().first().done.map { it.item.content })
        assertEquals(day, e.achievements().first().done.single().date)
        assertTrue(e.achievements().first().open.isEmpty())
    }

    @Test fun createsDoneOnce() = runTest {
        val (e, id) = ready()
        val other = fake.item(fake.achievements, "Run")
        e.flush(force = true)
        e.achieve(id, day, sync = false); e.achieve(other, day, sync = false)
        e.flush(force = true)
        assertEquals(1, sent().count { it == "section_add" })
        assertEquals(listOf(doneId(), doneId()), listOf(id, other).map { fake.get(it)!!.sectionId })
    }

    @Test fun usesExistingDoneIgnoringCase() = runTest {
        fake.addAchievementsProject()
        val done = fake.section(fake.achievements, "DONE")
        fake.section(fake.achievements, "done")
        val id = fake.item(fake.achievements, "Weld")
        val e = engine(); e.flush()
        e.achieve(id, day)
        assertEquals(done, fake.get(id)!!.sectionId)
        assertTrue("section_add" !in sent())
    }

    @Test fun offlineAchieve_survivesRestart() = runTest {
        val (e, id) = ready("x")
        fake.failNext(SyncError.Offline())
        e.achieve(id, day)
        assertEquals(listOf("Weld"), e.achievements().first().done.map { it.item.content })
        assertEquals("x", fake.get(id)!!.description)
        engine().flush(force = true)
        assertEquals(listOf(doneId(), "x\nDone: 25 Nov 2026"), fake.get(id)!!.let { listOf(it.sectionId, it.description) })
    }

    @Test fun undoBeforeSent_sendsNothingForItem() = runTest {
        val (e, id) = ready("x", section = null)
        val u = e.achieved(id, sync = false)
        e.undoAchieve(u); e.flush(force = true)
        assertTrue(sent().none { it == "item_move" || it == "item_update" })
        assertEquals("x", e.list().single().description)
        assertEquals(listOf("Weld"), e.achievements().first().open.flatMap { it.rows }.map { it.item.content })
    }

    @Test fun undoAfterSent() = runTest {
        fake.addAchievementsProject()
        val home = fake.section(fake.achievements, "Home")
        val id = fake.item(fake.achievements, "Roof", home, description = "x")
        val e = engine(); e.flush()
        val u = e.achieved(id)
        e.undoAchieve(u)
        assertEquals(listOf(home, "x"), fake.get(id)!!.let { listOf(it.sectionId, it.description) })
        assertEquals("Home", e.achievements().first().open.single().title)
    }

    @Test fun undoAfterSent_backToNoSection() = runTest {
        val (e, id) = ready("x")
        e.undoAchieve(e.achieved(id))
        assertEquals(listOf(null, "x"), fake.get(id)!!.let { listOf(it.sectionId, it.description) })
    }

    @Test fun undoKeepsTextWrittenMeanwhile() = runTest {
        val (e, id) = ready("x")
        val u = e.achieved(id, sync = false)
        e.describe(id, "new text", sync = false)
        e.undoAchieve(u); e.flush(force = true)
        assertEquals(listOf(null, "new text"), fake.get(id)!!.let { listOf(it.sectionId, it.description) })
    }

    @Test fun undoOfJustAdded() = runTest {
        fake.addAchievementsProject()
        val e = engine(); e.flush()
        val id = e.add(Kind.ACHIEVEMENTS, "Run", null, null, sync = false, description = "slowly")!!
        val u = e.achieved(id, sync = false)
        e.undoAchieve(u); e.flush(force = true)
        val i = fake.open(fake.achievements).single()
        assertEquals(listOf("Run", null, "slowly"), listOf(i.content, i.sectionId, i.description))

        // ...and when the add was sent in between, under its temporary id.
        val id2 = e.add(Kind.ACHIEVEMENTS, "Swim", null, null, sync = false)!!
        val u2 = e.achieved(id2)
        assertEquals(doneId(), fake.open(fake.achievements).single { it.content == "Swim" }.sectionId)
        e.undoAchieve(u2)
        assertEquals(listOf(null, ""), fake.open(fake.achievements).single { it.content == "Swim" }.let { listOf(it.sectionId, it.description) })
    }

    @Test fun reopen_removesDateAndSection() = runTest {
        val (e, id) = ready("x")
        e.achieve(id, day)
        e.reopen(id)
        assertEquals(listOf(null, "x"), fake.get(id)!!.let { listOf(it.sectionId, it.description) })
        assertEquals(listOf("Weld"), e.achievements().first().open.flatMap { it.rows }.map { it.item.content })
    }

    @Test fun achievingTwice_changesNothingMore() = runTest {
        val (e, id) = ready("x")
        e.achieve(id, day)
        assertEquals(TickResult.NotFound, e.achieve(id, day.plusDays(1)))
        assertEquals("x\nDone: 25 Nov 2026", fake.get(id)!!.description)
    }

    @Test fun describe_keepsDateLine() = runTest {
        val (e, id) = ready("x")
        assertTrue(e.describe(id, "open text\n"))
        assertEquals("open text", fake.get(id)!!.description)
        e.achieve(id, day)
        e.describe(id, "better text")
        assertEquals("better text\nDone: 25 Nov 2026", fake.get(id)!!.description)
        e.describe(id, "")
        assertEquals("Done: 25 Nov 2026", fake.get(id)!!.description)
        val n = sent().size
        e.describe(id, "")                                       // nothing changed, nothing sent
        assertEquals(n, sent().size)
    }

    @Test fun describe_rejectsOver16000() = runTest {
        val (e, id) = ready("x")
        assertFalse(e.describe(id, "a".repeat(16_001)))
        assertEquals("x", fake.get(id)!!.description)
        assertEquals("Descriptions can have 16000 characters at most", e.state.value.message)
        assertTrue(e.describe(id, "a".repeat(16_000)))
    }

    @Test fun goneElsewhere_message() = runTest {
        val (e, id) = ready("x")
        fake.deleteItem(id)
        e.achieve(id, day)
        assertEquals("Weld was already removed", e.state.value.message)
        assertTrue(e.list().isEmpty())
        assertEquals(TickResult.NotFound, e.achieve(id, day))
    }

    @Test fun subItemsAndRepeatingAreTicked() = runTest {
        val (e, id) = ready()
        val sub = fake.item(fake.achievements, "Course", parent = id)
        val rep = fake.item(fake.achievements, "Run", due = RDue("2026-11-24", "every monday", true))
        e.flush(force = true)
        assertTrue(e.achieve(sub, day) is TickResult.Hidden)
        assertTrue(e.achieve(rep, day) is TickResult.Advanced)
        assertEquals(listOf("item_close", "item_close"), sent())
        assertTrue(fake.get(sub)!!.checked)
    }

    @Test fun projectAppearsLater() = runTest {
        val e = engine(); e.flush()
        assertNull(e.add(Kind.ACHIEVEMENTS, "Run", null, null))
        fake.addAchievementsProject()
        fake.item(fake.achievements, "Weld", responsible = fake.me)
        e.flush(force = true)
        assertEquals(listOf("Weld"), e.myAchievementsFlow().first().tasks.map { it.content })
    }

    @Test fun mineOnly_notDone() = runTest {
        val (e, id) = ready()
        fake.item(fake.achievements, "Run", responsible = fake.partner)
        fake.item(fake.achievements, "Swim", responsible = fake.me)
        e.flush(force = true)
        assertEquals(listOf("Weld", "Swim"), e.myAchievementsFlow().first().tasks.map { it.content })
        e.achieve(id, day)
        assertEquals(listOf("Swim"), e.myAchievementsFlow().first().tasks.map { it.content })
    }

    @Test fun movePickerTargets_excludeDone() = runTest {
        fake.addAchievementsProject()
        fake.section(fake.achievements, "Home"); fake.section(fake.achievements, "done")
        fake.section(fake.groceries, "Done")                    // an ordinary category elsewhere
        val e = engine(); e.flush()
        assertEquals(listOf("Home"), e.moveTargets(Kind.ACHIEVEMENTS).first().map { it.name })
        assertEquals(listOf("Done"), e.moveTargets(Kind.GROCERIES).first().map { it.name })
    }
}
