package lt.nook.app.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import lt.nook.app.OpenRequest
import lt.nook.app.core.isDoneSection
import lt.nook.app.data.CategoryMemory
import lt.nook.app.data.Kind
import lt.nook.app.data.NookDb
import lt.nook.app.data.Settings
import lt.nook.app.sync.FakeTodoist
import lt.nook.app.sync.Status
import lt.nook.app.sync.SyncEngine
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
class AchievementsUiTest {
    // The default test dispatcher continues effects on Room's threads (the real app always uses the main one).
    @get:Rule val rule = createComposeRule(StandardTestDispatcher())
    private lateinit var db: NookDb
    private val fake = FakeTodoist()
    private lateinit var engine: SyncEngine
    private lateinit var memory: CategoryMemory
    private var request by mutableStateOf<OpenRequest?>(null)

    @Before fun up() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NookDb::class.java)
            .allowMainThreadQueries().build()
        memory = CategoryMemory(db) { 1L }
        engine = SyncEngine(db, fake, { "good" }, { Settings.DEFAULTS }, { 1L }, memory)
    }
    @After fun down() { quiet(); db.close() }

    /** Lets a sync started by the screen finish: blocking the main thread while it runs would stop it for good. */
    private fun quiet() = waitFor { engine.state.value.status == Status.Synced }
    private fun syncNow() { quiet(); runBlocking { engine.flush(force = true) } }

    private fun start(project: Boolean = true, open: Boolean = true, fill: () -> Unit = {}) {
        if (project) fake.addAchievementsProject()
        fill()
        runBlocking { engine.flush() }
        rule.setContent {
            NookApp(engine, memory, onSettings = {}, onSignOut = {}, autoRefresh = false,
                openRequest = request, onRequestHandled = { request = null })
        }
        if (open) rule.onNodeWithText("Achievements").performClick()
    }
    private fun waitText(t: String) = rule.waitUntil(5_000) { rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }
    private fun waitGone(t: String) = rule.waitUntil(5_000) { rule.onAllNodesWithText(t).fetchSemanticsNodes().isEmpty() }
    private fun waitFor(cond: () -> Boolean) = rule.waitUntil(5_000) { rule.waitForIdle(); cond() }
    private fun closed() = waitFor { rule.onAllNodesWithTag("detailText").fetchSemanticsNodes().isEmpty() }
    private fun openDetails(name: String) {
        waitText(name)
        rule.onNodeWithText(name).performClick()
        waitFor { rule.onAllNodesWithTag("detailText").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun field() = rule.onNodeWithTag("detailText")
    private fun isDone(id: String) = fake.sectionsOf(fake.achievements).any { it.id == fake.get(id)!!.sectionId && isDoneSection(it.name) }

    @Test fun rowTap_opensDetails_doesNotTick() {
        lateinit var id: String
        start { id = fake.item(fake.achievements, "Weld", responsible = fake.partner, description = "Course first\nthen a gate") }
        waitText("Course first")                                          // its first line is in the list
        rule.onNodeWithText("then a gate", substring = true).assertDoesNotExist()
        openDetails("Weld")
        field().assertTextContains("Course first\nthen a gate")
        rule.onNodeWithTag("detailWho").assertTextContains("Sam")
        rule.onNodeWithContentDescription("Close").performClick()
        closed()
        assertTrue(!isDone(id) && !fake.get(id)!!.checked)
    }

    @Test fun circle_achieves_undoRestores() {
        lateinit var id: String
        start { id = fake.item(fake.achievements, "Weld", description = "x") }
        waitText("Weld")
        rule.onNodeWithContentDescription("Tick Weld").performClick()
        waitText("Achieved: Weld")
        waitFor { isDone(id) }
        assertTrue(fake.get(id)!!.description.startsWith("x\nDone: "))
        rule.onNodeWithText("Undo").performClick()
        waitFor { !isDone(id) && fake.get(id)!!.description == "x" }
    }

    @Test fun addWithDescription() {
        start()
        rule.onNodeWithContentDescription("Add").performClick()
        rule.onNodeWithTag("addField").performTextInput("Run 10 km")
        rule.onNodeWithTag("addDescription").performTextInput("Plan\n\n- slow")
        rule.onNodeWithTag("assignee_Sam").performClick()
        rule.onNodeWithText("Add").performClick()
        waitFor { fake.open(fake.achievements).isNotEmpty() }
        val i = fake.open(fake.achievements).single()
        assertEquals(listOf("Run 10 km", "Plan\n\n- slow", fake.partner), listOf(i.content, i.description, i.responsibleUid))
        rule.onNodeWithTag("addDescription").assertTextContains("")       // ready for the next one
    }

    @Test fun addingTheSameNameTwice_isAllowed() {
        start { fake.item(fake.achievements, "Weld") }
        waitText("Weld")
        rule.onNodeWithContentDescription("Add").performClick()
        rule.onNodeWithTag("addField").performTextInput("Weld")
        rule.onNodeWithText("Add").performClick()
        waitFor { fake.open(fake.achievements).size == 2 }
    }

    @Test fun editDescription_saves() {
        lateinit var id: String
        start { id = fake.item(fake.achievements, "Weld", description = "x") }
        openDetails("Weld")
        field().performTextReplacement("better")
        rule.onNodeWithTag("detailSave").performClick()
        closed()
        waitFor { fake.get(id)!!.description == "better" }
    }

    @Test fun tooLong_cannotBeSaved() {
        start { fake.item(fake.achievements, "Weld") }
        openDetails("Weld")
        field().performTextReplacement("a".repeat(16_001))
        waitText("16001 of 16000 characters")
        rule.onNodeWithTag("detailSave").assertIsNotEnabled()
        field().performTextReplacement("a")
        rule.onNodeWithTag("detailSave").assertIsEnabled()
    }

    @Test fun unsavedText_asks() {
        lateinit var id: String
        start { id = fake.item(fake.achievements, "Weld", description = "x") }
        openDetails("Weld")
        field().performTextReplacement("typed")
        rule.onNodeWithContentDescription("Close").performClick()
        waitText("Save the changes?")
        rule.onNodeWithText("Keep editing").performClick()
        field().assertTextContains("typed")
        rule.onNodeWithContentDescription("Close").performClick()
        rule.onNodeWithText("Discard").performClick()
        closed()
        assertEquals("x", fake.get(id)!!.description)
        openDetails("Weld")
        field().performTextReplacement("typed")
        rule.onNodeWithContentDescription("Close").performClick()
        waitText("Save the changes?")
        rule.onNodeWithTag("askSave").performClick()
        closed()
        waitFor { fake.get(id)!!.description == "typed" }
    }

    @Test fun syncWhileEditing_keepsTypedText() {
        lateinit var id: String
        start { id = fake.item(fake.achievements, "Weld", description = "x") }
        openDetails("Weld")
        fake.describeElsewhere(id, "from the other phone")
        syncNow()
        waitFor { rule.onAllNodesWithText("from the other phone").fetchSemanticsNodes().isNotEmpty() }   // not typed yet: follows
        field().performTextReplacement("typed")
        fake.describeElsewhere(id, "again")
        syncNow()
        rule.waitForIdle()
        field().assertTextContains("typed")
        rule.onNodeWithTag("detailSave").performClick()
        waitFor { fake.get(id)!!.description == "typed" }
    }

    @Test fun removedElsewhere_keepsTextAndSaysSo() {
        lateinit var id: String
        start { id = fake.item(fake.achievements, "Weld", description = "x") }
        openDetails("Weld")
        field().performTextReplacement("typed")
        fake.deleteItem(id)
        syncNow()
        waitText("This was removed")
        field().assertTextContains("typed")
        rule.onNodeWithTag("detailSave").assertIsNotEnabled()
    }

    @Test fun doneUnfolds_newestFirst_reopens() {
        lateinit var old: String
        start {
            val done = fake.section(fake.achievements, "Done")
            old = fake.item(fake.achievements, "Paint", done, description = "Done: 5 Nov 2026")
            fake.item(fake.achievements, "Photos", done, description = "Done: 20 Nov 2026")
            fake.item(fake.achievements, "Weld")
        }
        waitText("Done · 2")
        rule.onNodeWithText("Paint").assertDoesNotExist()
        rule.onNodeWithText("Done · 2").performClick()
        waitText("5 Nov 2026"); waitText("20 Nov 2026")
        val y = { n: String -> rule.onNodeWithText(n).fetchSemanticsNode().positionInRoot.y }
        assertTrue(y("Photos") < y("Paint"))
        rule.onNodeWithContentDescription("Reopen Paint").performClick()
        waitFor { !isDone(old) && fake.get(old)!!.description == "" }
        waitText("Done · 1")
    }

    @Test fun noProject_saysWhatToCreate() {
        start(project = false)
        waitText("Create a shared Todoist project called \"Achievements\"")
        rule.onNodeWithText("Synced").assertExists()
        rule.onNodeWithContentDescription("Add").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Create a shared", substring = true).fetchSemanticsNodes().size == 2 }
    }

    @Test fun otherTabs_rowTapStillTicks() {
        lateinit var id: String
        start(open = false) { id = fake.item(fake.groceries, "Milk") }
        waitText("Milk")
        rule.onNodeWithText("Milk").performClick()
        waitFor { fake.get(id)!!.checked }
    }

    @Test fun longPress_hidesDone() {
        lateinit var id: String
        lateinit var home: String
        start {
            home = fake.section(fake.achievements, "Home"); fake.section(fake.achievements, "Done")
            id = fake.item(fake.achievements, "Weld")
        }
        waitText("Weld")
        rule.onNodeWithText("Weld").performTouchInput { longClick() }
        waitText("Move Weld to")
        rule.onNodeWithTag("move_Done").assertDoesNotExist()
        rule.onNodeWithTag("move_Home").performClick()
        waitFor { fake.get(id)!!.sectionId == home }
    }

    @Test fun subItem_isTicked() {
        lateinit var sub: String
        start { val p = fake.item(fake.achievements, "Weld"); sub = fake.item(fake.achievements, "Course", parent = p) }
        waitText("Course")
        rule.onNodeWithContentDescription("Tick Course").performClick()
        waitText("Course ticked off")
        waitFor { fake.get(sub)!!.checked }
    }

    @Test fun fromWidget_opensTheItem() {
        lateinit var id: String
        start(open = false) { id = fake.item(fake.achievements, "Weld", description = "x") }
        rule.runOnIdle { request = OpenRequest(Kind.ACHIEVEMENTS, add = false, itemId = id) }
        waitFor { rule.onAllNodesWithTag("detailText").fetchSemanticsNodes().isNotEmpty() }
        field().assertTextContains("x")
    }

    @Test fun fromWidget_neverReplacesUnsavedText() {
        lateinit var other: String
        start { fake.item(fake.achievements, "Weld", description = "x"); other = fake.item(fake.achievements, "Run", description = "y") }
        openDetails("Weld")
        rule.runOnIdle { request = OpenRequest(Kind.ACHIEVEMENTS, add = false, itemId = other) }
        waitFor { rule.onAllNodesWithText("y").fetchSemanticsNodes().isNotEmpty() }       // nothing typed: switches
        field().performTextReplacement("typed")
        rule.runOnIdle { request = OpenRequest(Kind.TASKS, add = false) }
        rule.waitForIdle()
        field().assertTextContains("typed")
        rule.onNodeWithTag("detailSave").performClick()
        waitFor { fake.get(other)!!.description == "typed" }
    }
}
