package lt.nook.app.widget

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lt.nook.app.data.Kind
import lt.nook.app.data.NookDb
import lt.nook.app.sync.FakeTodoist
import lt.nook.app.sync.RDue
import lt.nook.app.sync.SyncEngine
import lt.nook.app.sync.SyncError
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
class MyTasksTest {
    private lateinit var db: NookDb
    private val fake = FakeTodoist()
    private lateinit var engine: SyncEngine

    @Before fun up() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NookDb::class.java)
            .allowMainThreadQueries().build()
        engine = SyncEngine(db, fake, { "good" },
            { mapOf(Kind.GROCERIES to "Groceries", Kind.SHOPPING to "Shopping", Kind.TASKS to "Shared tasks") }, { 0L })
    }
    @After fun down() = db.close()

    @Test fun onlyMine_inTheTasksTabOrder() = runTest {
        val sec = fake.section(fake.tasks, "House", 1)
        fake.item(fake.tasks, "Call plumber", responsible = fake.me)
        fake.item(fake.tasks, "Her task", responsible = fake.partner)
        fake.item(fake.tasks, "Anyone's task")
        fake.item(fake.tasks, "Fix shelf", section = sec, responsible = fake.me)
        fake.item(fake.groceries, "Milk")
        engine.flush()
        assertEquals(listOf("Fix shelf", "Call plumber"), engine.myTasks().map { it.content })   // section first, like the tab
    }

    @Test fun beforeFirstSync_nothing() = runTest {
        assertEquals(emptyList<Any>(), engine.myTasks())
    }

    @Test fun tick_hides_and_undo_restores() = runTest {
        val id = fake.item(fake.tasks, "Call plumber", responsible = fake.me)
        engine.flush()
        val w = WidgetActions(engine)
        fake.failNext(SyncError.Offline())                     // works offline too
        w.tick(id)
        assertEquals(emptyList<Any>(), engine.myTasks().map { it.content })
        assertTrue(w.canUndo())
        w.undo()
        assertEquals(listOf("Call plumber"), engine.myTasks().map { it.content })
        assertFalse(w.canUndo())
        engine.flush(force = true)
        assertFalse(fake.get(id)!!.checked)
    }

    @Test fun recurring_tick_keeps_it_and_offers_no_undo() = runTest {
        val id = fake.item(fake.tasks, "Water plants", responsible = fake.me, due = RDue("2026-11-24", "every monday", true))
        engine.flush()
        val w = WidgetActions(engine)
        w.tick(id)
        assertEquals(listOf("Water plants"), engine.myTasks().map { it.content })
        assertFalse(w.canUndo())
    }

    @Test fun flow_follows_ticks_and_undo() = runTest {
        val id = fake.item(fake.tasks, "Call plumber", responsible = fake.me)
        fake.item(fake.tasks, "Fix shelf", responsible = fake.me)
        engine.flush()
        val w = WidgetActions(engine)
        assertEquals(listOf("Call plumber", "Fix shelf"), engine.myTasksFlow().first().tasks.map { it.content })
        w.tick(id)
        assertEquals(listOf("Fix shelf"), engine.myTasksFlow().first().tasks.map { it.content })
        assertTrue(w.undoLabel.value != null)
        w.undo()
        assertEquals(listOf("Call plumber", "Fix shelf"), engine.myTasksFlow().first().tasks.map { it.content })
        assertFalse(w.undoLabel.value != null)
    }

    @Test fun flow_knows_when_the_first_sync_hasnt_happened() = runTest {
        assertNull(engine.myTasksFlow().first().me)
        engine.flush()
        assertEquals(fake.me, engine.myTasksFlow().first().me)
    }

    @Test fun clear_forgets_undo() = runTest {
        val id = fake.item(fake.tasks, "Call plumber", responsible = fake.me)
        engine.flush()
        val w = WidgetActions(engine)
        w.tick(id)
        w.clear()
        assertFalse(w.canUndo())
    }

    @Test fun undo_expires() = runTest {
        var now = 0L
        val id = fake.item(fake.tasks, "Call plumber", responsible = fake.me)
        engine.flush()
        val w = WidgetActions(engine) { now }
        w.tick(id)
        now += 11 * 60_000
        assertFalse(w.canUndo())
        assertFalse(w.undo())
    }

    @Test fun achieveFromWidget_undoRestores() = runTest {
        fake.addAchievementsProject()
        val id = fake.item(fake.achievements, "Weld", responsible = fake.me, description = "x")
        engine.flush()
        val w = WidgetActions(engine)
        w.achieve(id, LocalDate.of(2026, 11, 25))
        assertEquals(emptyList<Any>(), engine.myAchievementsFlow().first().tasks)
        assertEquals("Achieved: Weld", w.undoLabel.value)
        engine.flush(force = true)
        assertTrue(fake.get(id)!!.description.endsWith("Done: 25 Nov 2026"))
        w.achieve(id, LocalDate.of(2026, 11, 26))                  // a second tap changes nothing and keeps Undo
        assertEquals("Achieved: Weld", w.undoLabel.value)
        w.undo(); engine.flush(force = true)
        assertNull(w.undoLabel.value)
        assertEquals(listOf(null, "x"), fake.get(id)!!.let { listOf(it.sectionId, it.description) })
    }

    @Test fun undoLabelFollowsTheLastTick() = runTest {
        fake.addAchievementsProject()
        val task = fake.item(fake.tasks, "Call plumber", responsible = fake.me)
        val ach = fake.item(fake.achievements, "Weld", responsible = fake.me)
        engine.flush()
        val w = WidgetActions(engine)
        w.achieve(ach, LocalDate.of(2026, 11, 25))
        w.tick(task)
        assertEquals("Done: Call plumber", w.undoLabel.value)
        w.undo(); engine.flush(force = true)
        assertEquals(listOf("Call plumber"), engine.myTasks().map { it.content })
        assertTrue(fake.get(ach)!!.description.startsWith("Done: "))
    }

    @Test fun clear_forgetsBothUndos() = runTest {
        fake.addAchievementsProject()
        val id = fake.item(fake.achievements, "Weld", responsible = fake.me)
        engine.flush()
        val w = WidgetActions(engine)
        w.achieve(id, LocalDate.of(2026, 11, 25))
        w.clear()
        assertFalse(w.canUndo())
        assertNull(w.undoLabel.value)
        w.undo(); engine.flush(force = true)
        assertTrue(fake.get(id)!!.description.startsWith("Done: "))
    }
}
