package lt.nook.app.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import lt.nook.app.auth.InMemoryTokenStore
import lt.nook.app.auth.Session
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
import java.time.Instant

/** Pauses a sync inside the transport so tests can act while a request is "in flight". */
class Gate(private val inner: Transport) : Transport {
    var armed = false
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    override suspend fun sync(token: String, syncToken: String, commands: List<Command>): SyncResponse {
        if (armed) { armed = false; entered.complete(Unit); release.await() }
        return inner.sync(token, syncToken, commands)
    }
    override suspend fun completed(token: String, projectId: String, since: Instant, until: Instant, cursor: String?) =
        inner.completed(token, projectId, since, until, cursor)
}

/** Regression tests for the final review's findings. */
@RunWith(RobolectricTestRunner::class)
class SyncFixesTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: NookDb
    private val fake = FakeTodoist()
    private val gate = Gate(fake)
    private lateinit var e: SyncEngine
    private val names = mapOf(Kind.GROCERIES to "Groceries", Kind.SHOPPING to "Shopping", Kind.TASKS to "Shared tasks")

    @Before fun up() {
        db = Room.inMemoryDatabaseBuilder(ctx, NookDb::class.java).build()
        e = SyncEngine(db, gate, { "good" }, { names }, { 0L })
    }
    @After fun down() = db.close()

    private suspend fun names(k: Kind = Kind.GROCERIES) = e.items(k).first().map { it.content }

    @Test fun add_beforeListsLoaded_isRefusedWithMessage() = runBlocking {
        assertNull(e.add(Kind.GROCERIES, "Milk", null, null))
        assertEquals("Lists aren't loaded yet — pull down to refresh", e.state.value.message)
        assertEquals(0, db.dao().pendingCount())
    }

    @Test fun tick_withoutSync_returnsBeforeNetwork() = runBlocking {
        val id = fake.item(fake.groceries, "Milk")
        e.flush()
        val calls = fake.calls
        assertTrue(e.tick(id, sync = false) is TickResult.Hidden)
        assertEquals(calls, fake.calls)
        assertEquals(1, db.dao().pendingCount())
    }

    @Test fun undo_doesNotWaitForInflightFlush() = runBlocking {
        val id = fake.item(fake.groceries, "Milk")
        e.flush()
        val r = e.tick(id, sync = false) as TickResult.Hidden
        gate.armed = true
        val job = CoroutineScope(Dispatchers.Default).launch { e.flush(force = true) }
        gate.entered.await()
        withTimeout(3_000) { e.undo(r.undo, sync = false) }
        assertEquals(listOf("Milk"), names())
        gate.release.complete(Unit); job.join()
        e.flush(force = true)
        assertFalse(fake.get(id)!!.checked)
        assertEquals(listOf("Milk"), names())
    }

    @Test fun recurringTickedTwiceOffline_queuesOnce() = runBlocking {
        val id = fake.item(fake.tasks, "Water plants", due = RDue("2026-11-24", "every monday", true))
        e.flush()
        fake.failNext(SyncError.Offline())
        assertEquals(TickResult.Advanced(null), e.tick(id))
        assertEquals(TickResult.AlreadyQueued, e.tick(id))
        assertEquals(1, db.dao().pendingCount())
    }

    @Test fun undoAfterAddAndCloseSentTogether() = runBlocking {
        e.flush()
        val temp = e.add(Kind.GROCERIES, "Milk", null, null, sync = false)!!
        val r = e.tick(temp, sync = false) as TickResult.Hidden
        e.flush(force = true)                                   // add + close go out in one batch
        e.undo(r.undo)
        assertNull(e.state.value.message)
        assertEquals(listOf("Milk"), fake.open(fake.groceries).map { it.content })
        assertEquals(listOf("Milk"), names())
    }

    @Test fun newSection_flushedInBetween_thenAddUsesRealSection() = runBlocking {
        e.flush()
        val s = e.newSection(Kind.SHOPPING, "DEPO")
        e.flush(force = true)                                   // e.g. the 30 s refresh sends section_add alone
        e.add(Kind.SHOPPING, "Paint", s, null)
        assertNull(e.state.value.message)
        val depo = fake.sectionsOf(fake.shopping).single()
        assertEquals(depo.id, fake.open(fake.shopping).single().sectionId)
    }

    @Test fun badRequestOnce_resyncsAndSucceeds() = runBlocking {
        e.flush()
        fake.failNext(SyncError.BadRequest())
        e.add(Kind.GROCERIES, "Milk", null, null)
        assertEquals(Status.Synced, e.state.value.status)
        assertEquals(listOf("Milk"), fake.open(fake.groceries).map { it.content })
    }

    @Test fun badCommand_isDroppedWithNamedMessage_othersGoThrough() = runBlocking {
        e.flush()
        fake.failNext(SyncError.Offline())
        e.add(Kind.GROCERIES, "Milk", null, null)
        e.add(Kind.GROCERIES, "Eggs", null, null, sync = false)
        fake.reject400 = { cmds -> cmds.any { it.arg("content") == "Milk" } }
        e.flush(force = true)
        assertEquals(listOf("Eggs"), fake.open(fake.groceries).map { it.content })
        assertEquals("Couldn't save Milk", e.state.value.message)
        assertEquals(0, db.dao().pendingCount())
        assertEquals(listOf("Eggs"), names())
    }

    @Test fun addIntoCategoryDeletedElsewhere_saysSo() = runBlocking {
        val dairy = fake.section(fake.groceries, "Dairy")
        e.flush()
        fake.deleteSection(dairy)
        e.add(Kind.GROCERIES, "Milk", dairy, null)
        assertEquals("Couldn't add Milk — its category was deleted", e.state.value.message)
    }

    @Test fun signOut_waitsForInflightFlush_andLeavesNothing() = runBlocking {
        fake.item(fake.groceries, "Milk")
        val tokens = InMemoryTokenStore("good")
        val session = Session(tokens, Settings(ctx.getSharedPreferences("s", Context.MODE_PRIVATE)), db, e)
        gate.armed = true
        val job = CoroutineScope(Dispatchers.Default).launch { e.flush(force = true) }
        gate.entered.await()
        val out = CoroutineScope(Dispatchers.Default).launch { session.signOut() }
        gate.release.complete(Unit)
        job.join(); out.join()
        assertEquals(0, db.dao().allItems().size)
        assertNull(db.dao().meta(SyncEngine.SYNC_TOKEN))
    }
}
