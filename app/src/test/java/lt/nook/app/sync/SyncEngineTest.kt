package lt.nook.app.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lt.nook.app.data.Kind
import lt.nook.app.data.NookDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncEngineTest {
    private lateinit var db: NookDb
    private val fake = FakeTodoist()
    private var now = 1_000_000L
    private var token: String? = "good"

    private fun engine(d: NookDb = db) = SyncEngine(d, fake, { token },
        { mapOf(Kind.GROCERIES to "Groceries", Kind.SHOPPING to "Shopping", Kind.TASKS to "Shared tasks") }, { now })

    @Before fun up() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NookDb::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun down() = db.close()

    private suspend fun SyncEngine.names(k: Kind = Kind.GROCERIES) = items(k).first().map { it.content }

    @Test fun firstSync_loadsListsSectionsPeople() = runTest {
        val dairy = fake.section(fake.groceries, "Dairy")
        fake.item(fake.groceries, "Milk", dairy)
        fake.item(fake.tasks, "Call plumber", responsible = fake.partner)
        val e = engine(); e.flush()
        assertEquals(listOf("Milk"), e.names())
        assertEquals(listOf("Dairy"), e.sections(Kind.GROCERIES).first().map { it.name })
        assertEquals("Sam", e.items(Kind.TASKS).first().single().assignee)
        assertEquals(setOf("Alex", "Sam"), e.people(Kind.TASKS).first().map { it.name }.toSet())
        assertEquals(Status.Synced, e.state.value.status)
    }

    @Test fun add_showsImmediately_thenGetsRealId() = runTest {
        val e = engine(); e.flush()
        fake.failNext(SyncError.Offline())
        val temp = e.add(Kind.GROCERIES, "Eggs", null, null)
        assertEquals(listOf("Eggs"), e.names())
        assertEquals(1, e.state.value.pending)
        assertEquals(Status.Offline, e.state.value.status)
        e.flush(force = true)
        val ids = e.items(Kind.GROCERIES).first().map { it.id }
        assertFalse(temp in ids)
        assertEquals(listOf("Eggs"), fake.open(fake.groceries).map { it.content })
        assertEquals(fake.open(fake.groceries).single().id, ids.single())
        assertEquals(0, e.state.value.pending)
    }

    @Test fun tick_hidesAndUndoRestores() = runTest {
        val id = fake.item(fake.groceries, "Milk")
        val e = engine(); e.flush()
        val r = e.tick(id) as TickResult.Hidden
        assertEquals(emptyList<String>(), e.names())
        assertTrue(fake.get(id)!!.checked)
        e.undo(r.undo)
        assertEquals(listOf("Milk"), e.names())
        assertFalse(fake.get(id)!!.checked)
    }

    @Test fun undoWhileOffline_cancelsWithoutSending() = runTest {
        val id = fake.item(fake.groceries, "Milk")
        val e = engine(); e.flush()
        fake.failNext(SyncError.Offline())
        val r = e.tick(id) as TickResult.Hidden
        e.undo(r.undo)
        assertEquals(listOf("Milk"), e.names())
        assertEquals(0, e.state.value.pending)
        e.flush(force = true)
        assertFalse(fake.get(id)!!.checked)
        assertTrue(fake.received.flatten().none { it.type == "item_close" })
    }

    @Test fun tickParent_hidesChildren_undoRestoresAll() = runTest {
        val p = fake.item(fake.tasks, "Trip")
        val c = fake.item(fake.tasks, "Tickets", parent = p)
        val e = engine(); e.flush()
        assertEquals(1, e.openChildren(p))
        val r = e.tick(p) as TickResult.Hidden
        assertEquals(emptyList<String>(), e.names(Kind.TASKS))
        assertTrue(fake.get(c)!!.checked)
        e.undo(r.undo)
        assertEquals(listOf("Trip", "Tickets"), e.names(Kind.TASKS))
        assertFalse(fake.get(p)!!.checked); assertFalse(fake.get(c)!!.checked)
    }

    @Test fun tickRecurring_staysWithNextDate_noUndo() = runTest {
        val id = fake.item(fake.tasks, "Water plants", due = RDue("2026-11-24", "every monday", true))
        val e = engine(); e.flush()
        val r = e.tick(id) as TickResult.Advanced
        assertEquals("2026-12-01", r.nextDate)
        assertEquals("2026-12-01", e.items(Kind.TASKS).first().single().due)
    }

    @Test fun move_updatesSection() = runTest {
        val a = fake.section(fake.groceries, "Dairy"); val b = fake.section(fake.groceries, "Bakery")
        val id = fake.item(fake.groceries, "Milk", a)
        val e = engine(); e.flush()
        e.move(id, b)
        assertEquals(b, e.items(Kind.GROCERIES).first().single().sectionId)
        assertEquals(b, fake.get(id)!!.sectionId)
        e.move(id, null)
        assertNull(fake.get(id)!!.sectionId)
    }

    @Test fun newSection_thenAddIntoIt_singleFlush() = runTest {
        val e = engine(); e.flush()
        val before = fake.calls
        val s = e.newSection(Kind.SHOPPING, "DEPO")
        e.add(Kind.SHOPPING, "Paint", s, null)
        assertEquals(before + 1, fake.calls)
        val depo = fake.sectionsOf(fake.shopping).single { it.name == "DEPO" }
        assertEquals(depo.id, fake.open(fake.shopping).single().sectionId)
        assertEquals(listOf(depo.id), e.sections(Kind.SHOPPING).first().map { it.id })
        assertEquals(depo.id, e.items(Kind.SHOPPING).first().single().sectionId)
    }

    @Test fun droppedResponse_retrySameUuid_noDuplicate() = runTest {
        val e = engine(); e.flush()
        fake.dropNextResponseAfterApply()
        e.add(Kind.GROCERIES, "Milk", null, null)
        assertEquals(1, e.state.value.pending)
        e.flush(force = true)
        assertEquals(1, fake.open(fake.groceries).size)
        assertEquals(listOf("Milk"), e.names())
    }

    @Test fun queue_survivesRestart_sentOnce() = runTest {
        val e = engine(); e.flush()
        fake.failNext(SyncError.Offline())
        e.add(Kind.GROCERIES, "Milk", null, null)
        val e2 = engine()          // app restarted, same database
        e2.flush(force = true)
        e2.flush(force = true)
        assertEquals(1, fake.open(fake.groceries).size)
        assertEquals(1, fake.received.flatten().count { it.type == "item_add" })
    }

    @Test fun perCommandError_revertsAndContinues() = runTest {
        val a = fake.section(fake.groceries, "Dairy")
        val milk = fake.item(fake.groceries, "Milk")
        val e = engine(); e.flush()
        fake.deleteItem(milk)                     // the other phone removed it
        fake.failNext(SyncError.Offline())
        e.move(milk, a)
        e.add(Kind.GROCERIES, "Eggs", null, null)
        e.flush(force = true)
        assertEquals(listOf("Eggs"), e.names())
        assertEquals("Milk was already removed", e.state.value.message)
        assertEquals(0, e.state.value.pending)
    }

    @Test fun authError_keepsQueue() = runTest {
        val e = engine(); e.flush()
        fake.revokeToken()
        e.add(Kind.GROCERIES, "Milk", null, null)
        assertEquals(Status.SignInAgain, e.state.value.status)
        assertEquals(1, e.state.value.pending)
        fake.token = "good"
        e.flush(force = true)
        assertEquals(listOf("Milk"), fake.open(fake.groceries).map { it.content })
    }

    @Test fun rateLimited_backsOff() = runTest {
        val e = engine(); e.flush()
        fake.failNext(SyncError.RateLimited(null))
        e.add(Kind.GROCERIES, "Milk", null, null)
        val calls = fake.calls
        now += 1_000; e.flush()
        assertEquals(calls, fake.calls)           // still inside the backoff window
        now += 10_000; e.flush()
        assertEquals(calls + 1, fake.calls)
        assertEquals(0, e.state.value.pending)
    }

    @Test fun backoffCapsAtFiveMinutes() = runTest {
        val e = engine(); e.flush()
        repeat(12) { fake.failNext(SyncError.Server(503)); e.flush(force = true) }
        val calls = fake.calls
        now += 300_001; e.flush()
        assertEquals(calls + 1, fake.calls)
    }

    @Test fun projectsMissing_reportsNames() = runTest {
        fake.renameProject(fake.shopping, "Shop")
        val e = engine(); e.flush()
        assertEquals(Status.ProjectsMissing(listOf("Shopping")), e.state.value.status)
        assertNull(e.projectId(Kind.SHOPPING))
        assertNotNull(e.projectId(Kind.GROCERIES))
    }

    @Test fun remoteChange_mergedButPendingItemKeepsLocalState() = runTest {
        val a = fake.section(fake.groceries, "Dairy"); val b = fake.section(fake.groceries, "Bakery")
        val milk = fake.item(fake.groceries, "Milk")
        val eggs = fake.item(fake.groceries, "Eggs")
        val e = engine(); e.flush()
        fake.failNext(SyncError.Offline())
        e.move(milk, a)                            // pending locally
        fake.moveElsewhere(milk, b)                // someone else moved it too
        fake.closeElsewhere(eggs)                  // and ticked eggs
        fake.failNext(SyncError.Offline()); e.flush(force = true)
        assertEquals(listOf("Milk", "Eggs"), e.names())
        e.flush(force = true)
        assertEquals(listOf("Milk"), e.names())
        assertEquals(a, e.items(Kind.GROCERIES).first().single().sectionId)
    }
}
