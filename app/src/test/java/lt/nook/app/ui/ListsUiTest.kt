package lt.nook.app.ui

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import lt.nook.app.data.CategoryMemory
import lt.nook.app.data.Kind
import lt.nook.app.data.NookDb
import lt.nook.app.sync.FakeTodoist
import lt.nook.app.sync.RDue
import lt.nook.app.sync.SyncEngine
import lt.nook.app.sync.SyncError
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class ListsUiTest {
    // Effects run on the main thread, as in the real app (the default continues them on Room's threads).
    @get:Rule val rule = createComposeRule(kotlinx.coroutines.test.StandardTestDispatcher())
    private lateinit var db: NookDb
    private val fake = FakeTodoist()
    private lateinit var engine: SyncEngine
    private lateinit var memory: CategoryMemory
    private var now = 1_000_000L

    @Before fun up() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NookDb::class.java)
            .allowMainThreadQueries().build()
        memory = CategoryMemory(db) { now }
        engine = SyncEngine(db, fake, { "good" },
            { mapOf(Kind.GROCERIES to "Groceries", Kind.SHOPPING to "Shopping", Kind.TASKS to "Shared tasks") }, { now }, memory)
    }
    @After fun down() {
        // Let any sync the screen started finish before closing the database.
        runCatching { rule.waitUntil(2_000) { rule.waitForIdle(); engine.state.value.status != lt.nook.app.sync.Status.Syncing } }
        db.close()
    }

    private fun start() {
        runBlocking { engine.flush() }
        rule.setContent { NookApp(engine, memory, onSettings = {}, onSignOut = {}, autoRefresh = false) }
    }

    private fun waitText(t: String, n: Int = 1) =
        rule.waitUntil(5_000) { rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().size >= n }
    private fun waitGone(t: String) =
        rule.waitUntil(5_000) { rule.onAllNodesWithText(t).fetchSemanticsNodes().isEmpty() }
    private fun waitFor(cond: () -> Boolean) = rule.waitUntil(5_000) { rule.waitForIdle(); cond() }

    private fun openAdd() = rule.onNodeWithContentDescription("Add").performClick()
    private fun type(t: String) = rule.onNodeWithTag("addField").performTextInput(t)

    @Test fun fourTabs_achievementsLast() {
        start()
        listOf("Groceries", "Shopping", "Tasks", "Achievements").forEach { rule.onNode(hasText(it) and isTab()).assertExists() }
        assertEquals(4, rule.onAllNodes(isTab()).fetchSemanticsNodes().size)
    }

    @Test fun groupsShowUnderSectionsWithOtherLast() {
        val dairy = fake.section(fake.groceries, "Dairy")
        fake.item(fake.groceries, "Milk", dairy); fake.item(fake.groceries, "Batteries")
        start()
        waitText("Milk"); rule.onNodeWithText("DAIRY").assertExists(); rule.onNodeWithText("OTHER").assertExists()
    }

    @Test fun fab_addsToCurrentTab() {
        start()
        rule.onNodeWithText("Shopping").performClick()
        openAdd(); type("Lamp")
        rule.onNodeWithText("Other").performClick()
        waitFor { fake.open(fake.shopping).any { it.content == "Lamp" } }
        assertTrue(fake.open(fake.groceries).isEmpty())
    }

    @Test fun typing_showsSuggestion_tapAddsToRememberedSection() {
        val dairy = fake.section(fake.groceries, "Dairy")
        val milk = fake.item(fake.groceries, "Milk", dairy)
        runBlocking { engine.flush() }
        fake.closeElsewhere(milk)
        start()
        openAdd(); type("mi")
        waitText("Milk · Dairy")
        rule.onNodeWithText("Milk · Dairy").performClick()
        waitFor { fake.open(fake.groceries).singleOrNull()?.sectionId == dairy }
        rule.onNodeWithTag("addField").assertExists()   // sheet stays open for the next item
    }

    @Test fun newItem_requiresCategoryChip() {
        fake.section(fake.groceries, "Dairy")
        start()
        openAdd(); type("Kefyras")
        rule.onNodeWithText("Add").performClick()
        waitText("Pick a category")
        assertTrue(fake.open(fake.groceries).isEmpty())
        rule.onNodeWithText("Dairy").performClick()
        waitFor { fake.open(fake.groceries).any { it.content == "Kefyras" } }
    }

    @Test fun newCategory_createsSectionAndAdds() {
        start()
        rule.onNodeWithText("Shopping").performClick()
        openAdd(); type("Paint")
        rule.onNodeWithText("+ New category").performClick()
        rule.onNodeWithTag("newCategory").performTextInput("DEPO")
        rule.onNodeWithText("Create").performClick()
        waitFor { fake.open(fake.shopping).any { it.content == "Paint" } }
        assertEquals("DEPO", fake.sectionsOf(fake.shopping).single { it.id == fake.open(fake.shopping).single().sectionId }.name)
    }

    @Test fun duplicate_isRefused() {
        fake.item(fake.groceries, "Milk")
        start()
        waitText("Milk")
        openAdd(); type("milk")
        rule.onNodeWithText("Add").performClick()
        waitText("Milk is already on the list")
        assertEquals(1, fake.open(fake.groceries).size)
    }

    @Test fun tick_thenUndo() {
        val id = fake.item(fake.groceries, "Milk")
        start()
        waitText("Milk")
        rule.onNodeWithContentDescription("Tick Milk").performClick()
        waitText("Milk ticked off")
        waitFor { fake.get(id)!!.checked }
        rule.onNodeWithText("Undo").performClick()
        waitFor { !fake.get(id)!!.checked }
        waitText("Milk")
    }

    @Test fun recurring_showsNextDate_noUndo() {
        fake.item(fake.tasks, "Water plants", due = RDue("2026-11-24", "every monday", true))
        start()
        rule.onNodeWithText("Tasks").performClick()
        waitText("Water plants")
        rule.onNodeWithContentDescription("Tick Water plants").performClick()
        waitText("Next: Tue 1 Dec")
        rule.onNodeWithText("Undo").assertDoesNotExist()
        rule.onNodeWithText("Water plants").assertExists()
    }

    @Test fun parentTick_confirms() {
        val p = fake.item(fake.tasks, "Trip")
        fake.item(fake.tasks, "Tickets", parent = p)
        start()
        rule.onNodeWithText("Tasks").performClick()
        waitText("Tickets")
        rule.onNodeWithContentDescription("Tick Trip").performClick()
        waitText("Also ticks 1 sub-items?")
        rule.onNodeWithText("Tick all").performClick()
        waitFor { fake.get(p)!!.checked }
        waitGone("Tickets")
    }

    @Test fun longPress_movesToChosenSection() {
        val dairy = fake.section(fake.groceries, "Dairy"); val bakery = fake.section(fake.groceries, "Bakery")
        val id = fake.item(fake.groceries, "Milk", dairy)
        start()
        waitText("Milk")
        rule.onNodeWithText("Milk").performTouchInput { longClick() }
        waitText("Move Milk to")
        rule.onNodeWithTag("move_Bakery").performClick()
        waitFor { fake.get(id)!!.sectionId == bakery }
    }

    @Test fun tasks_assigneeChips_defaultAnyone_noDuplicateCheck() {
        fake.item(fake.tasks, "Call plumber")
        start()
        rule.onNodeWithText("Tasks").performClick()
        waitText("Call plumber")
        openAdd(); type("Call plumber")
        rule.onNodeWithTag("assignee_Sam").performClick()
        rule.onNodeWithText("Add").performClick()
        waitFor { fake.open(fake.tasks).size == 2 }
        assertEquals(fake.partner, fake.open(fake.tasks).last().responsibleUid)
        rule.onNodeWithTag("assignee_Anyone").assertExists()
        waitText("Sam")
    }

    @Test fun addBeforeListsLoaded_saysSo() {
        rule.setContent { NookApp(engine, memory, onSettings = {}, onSignOut = {}, autoRefresh = false) }
        openAdd()
        waitText("Lists aren't loaded yet")
    }

    @Test fun signOutWithUnsyncedChanges_warnsFirst() {
        var signedOut = false
        fake.item(fake.groceries, "Milk")
        runBlocking { engine.flush() }
        rule.setContent { NookApp(engine, memory, onSettings = {}, onSignOut = { signedOut = true }, autoRefresh = false) }
        waitText("Milk")
        fake.failNext(SyncError.Offline())
        rule.onNodeWithContentDescription("Tick Milk").performClick()
        waitText("Not synced yet (1 changes)")
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Sign out").performClick()
        waitText("1 changes not synced yet will be lost.")
        assertTrue(!signedOut)
    }

    @Test fun offline_showsPendingCount() {
        fake.item(fake.groceries, "Milk")
        start()
        waitText("Milk")
        fake.failNext(SyncError.Offline())
        rule.onNodeWithContentDescription("Tick Milk").performClick()
        waitText("Not synced yet (1 changes)")
    }
}

private fun isTab() = androidx.compose.ui.test.SemanticsMatcher.expectValue(
    androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)

@Suppress("unused") private fun SemanticsNodeInteractionsProvider.unused() = Unit
