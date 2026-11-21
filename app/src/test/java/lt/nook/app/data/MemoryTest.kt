package lt.nook.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lt.nook.app.sync.FakeTodoist
import lt.nook.app.sync.SyncEngine
import lt.nook.app.sync.SyncError
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class MemoryTest {
    private lateinit var db: NookDb
    private val fake = FakeTodoist()
    private var now = Instant.parse("2026-11-21T12:00:00Z").toEpochMilli()
    private lateinit var memory: CategoryMemory
    private lateinit var engine: SyncEngine

    @Before fun up() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NookDb::class.java)
            .allowMainThreadQueries().build()
        memory = CategoryMemory(db) { now }
        engine = SyncEngine(db, fake, { "good" },
            { mapOf(Kind.GROCERIES to "Groceries", Kind.SHOPPING to "Shopping", Kind.TASKS to "Shared tasks") }, { now }, memory)
    }
    @After fun down() = db.close()

    private suspend fun sections(k: Kind = Kind.GROCERIES) = engine.sections(k).first()
    private suspend fun remembered(name: String, k: Kind = Kind.GROCERIES) = memory.sectionFor(k, name, sections(k))?.name

    @Test fun syncObservation_learnsSection() = runTest {
        fake.item(fake.groceries, "Pienas", fake.section(fake.groceries, "Dairy"))
        engine.flush()
        assertEquals("Dairy", remembered("pienas"))
        assertEquals("Dairy", memory.suggestions(Kind.GROCERIES, "pie").single().section)
    }

    @Test fun moveRelearns() = runTest {
        val dairy = fake.section(fake.groceries, "Dairy"); val bakery = fake.section(fake.groceries, "Bakery")
        val milk = fake.item(fake.groceries, "Milk", dairy)
        engine.flush()
        engine.move(milk, bakery)
        assertEquals("Bakery", remembered("Milk"))
    }

    @Test fun add_bumpsUses_rankingChanges() = runTest {
        engine.flush()
        engine.add(Kind.GROCERIES, "Mint", null, null)
        now += 1000
        engine.add(Kind.GROCERIES, "Milk", null, null)
        assertEquals(listOf("Milk", "Mint"), memory.suggestions(Kind.GROCERIES, "mi").map { it.name })
        now += 1000
        engine.add(Kind.GROCERIES, "Mint", null, null)
        engine.add(Kind.GROCERIES, "Mint", null, null)
        assertEquals(listOf("Mint", "Milk"), memory.suggestions(Kind.GROCERIES, "mi").map { it.name })
    }

    @Test fun memory_sectionGone_asksAgain() = runTest {
        val dairy = fake.section(fake.groceries, "Dairy")
        val milk = fake.item(fake.groceries, "Milk", dairy)
        engine.flush()
        fake.closeElsewhere(milk); fake.deleteSection(dairy)
        engine.flush()
        assertNull(remembered("Milk"))
        assertEquals(listOf("Milk"), memory.suggestions(Kind.GROCERIES, "mil").map { it.name })
    }

    @Test fun seed_readsWindowsUntilEmpty() = runTest {
        val dairy = fake.section(fake.groceries, "Dairy")
        engine.flush()
        val t = Instant.ofEpochMilli(now)
        fake.history(fake.groceries, "Kefyras", dairy, t.minusSeconds(10L * 86400))
        fake.history(fake.groceries, "Sviestas", dairy, t.minusSeconds(100L * 86400))
        fake.history(fake.groceries, "Grietinė", dairy, t.minusSeconds(101L * 86400))
        fake.history(fake.groceries, "Ancient", dairy, t.minusSeconds(400L * 86400))   // after an empty window: not read
        memory.seedIfDue(Kind.GROCERIES, fake, "good", fake.groceries)
        assertEquals("Dairy", remembered("kefyras"))
        assertEquals("Dairy", remembered("sviestas"))
        assertEquals("Dairy", remembered("grietine"))
        assertNull(remembered("ancient"))
        val calls = fake.completedCalls
        memory.seedIfDue(Kind.GROCERIES, fake, "good", fake.groceries)
        assertEquals(calls, fake.completedCalls)        // at most weekly
        now += 8L * 86400_000
        memory.seedIfDue(Kind.GROCERIES, fake, "good", fake.groceries)
        assertEquals(true, fake.completedCalls > calls)
    }

    @Test fun seed_doesNotOverrideCurrentKnowledge() = runTest {
        val dairy = fake.section(fake.groceries, "Dairy"); val veg = fake.section(fake.groceries, "Veg")
        fake.item(fake.groceries, "Milk", veg)
        engine.flush()
        fake.history(fake.groceries, "Milk", dairy, Instant.ofEpochMilli(now).minusSeconds(86400))
        memory.seedIfDue(Kind.GROCERIES, fake, "good", fake.groceries)
        assertEquals("Veg", remembered("Milk"))
    }

    @Test fun seed_errorIsQuiet() = runTest {
        engine.flush()
        fake.completedError = SyncError.Offline()
        memory.seedIfDue(Kind.GROCERIES, fake, "good", fake.groceries)
    }

    @Test fun sameNameOtherKind_isSeparate() = runTest {
        val ikea = fake.section(fake.shopping, "IKEA")
        fake.item(fake.shopping, "Candles", ikea)
        engine.flush()
        assertEquals("IKEA", remembered("Candles", Kind.SHOPPING))
        assertEquals(emptyList<Any>(), memory.suggestions(Kind.GROCERIES, "cand"))
    }
}
