package lt.nook.app.widget

import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import lt.nook.app.core.ItemV
import lt.nook.app.data.Kind
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WidgetContentTest {
    private fun t(id: String, text: String) = ItemV(id, text, null, null, 1, "u1", null, false)

    @Test fun showsMyTasksWithCount() = runGlanceAppWidgetUnitTest {
        setContext(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        provideComposable { WidgetContent(WidgetState(signedIn = true, tasks = listOf(t("1", "Call plumber"), t("2", "Fix shelf")))) }
        onNode(hasText("Tasks 2")).assertExists()
        onNode(hasText("Call plumber")).assertExists()
        onNode(hasText("Fix shelf")).assertExists()
        onNode(androidx.glance.testing.unit.hasTextEqualTo("Undo")).assertDoesNotExist()
    }

    @Test fun undoAfterATick() = runGlanceAppWidgetUnitTest {
        setContext(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        provideComposable { WidgetContent(WidgetState(signedIn = true, tasks = emptyList(), undoLabel = "Done: Call plumber")) }
        onNode(hasText("Done: Call plumber · Undo")).assertExists()
        onNode(hasText("Nothing assigned to you")).assertExists()
    }

    @Test fun signedInBeforeFirstSync() = runGlanceAppWidgetUnitTest {
        setContext(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        provideComposable { WidgetContent(WidgetState(signedIn = true, tasks = emptyList(), synced = false)) }
        onNode(hasText("Syncing…")).assertExists()
    }

    @Test fun repeatingTaskShowsItsDate() = runGlanceAppWidgetUnitTest {
        setContext(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        provideComposable { WidgetContent(WidgetState(signedIn = true, tasks = listOf(ItemV("1", "Water plants", null, null, 1, "u1", "2026-11-24", true)))) }
        onNode(hasText("Water plants · Tue 24 Nov")).assertExists()
    }

    @Test fun signedOut() = runGlanceAppWidgetUnitTest {
        setContext(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        provideComposable { WidgetContent(WidgetState(signedIn = false, tasks = emptyList())) }
        onNode(hasText("Open Nook to sign in")).assertExists()
        onNode(androidx.glance.testing.unit.hasTextEqualTo("Achievements 0")).assertDoesNotExist()
    }

    private fun show(s: WidgetState, check: androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest.() -> Unit) = runGlanceAppWidgetUnitTest {
        setContext(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        provideComposable { WidgetContent(s) }
        check()
    }
    private val both = WidgetState(signedIn = true, tasks = listOf(t("1", "Call plumber"), t("2", "Fix shelf")),
        achievements = listOf(t("a", "Learn to weld")))

    @Test fun toggleShowsBothCounts() = show(both) {
        onNode(hasText("Tasks 2")).assertExists()
        onNode(hasText("Achievements 1")).assertExists()
        onNode(hasText("Learn to weld")).assertDoesNotExist()
        // The other list's name switches to it; the selected one opens Nook.
        onNode(hasText("Achievements 1")).assert(androidx.glance.appwidget.testing.unit.hasRunCallbackClickAction<SelectAction>(
            androidx.glance.action.actionParametersOf(KIND to "ACHIEVEMENTS")))
        onNode(hasText("Tasks 2")).assert(androidx.glance.testing.unit.hasClickAction())
    }

    @Test fun achievementsSide_listsMine_notTasks() = show(both.copy(selected = Kind.ACHIEVEMENTS)) {
        onNode(hasText("Learn to weld")).assertExists()
        onNode(hasText("Call plumber")).assertDoesNotExist()
        onNode(hasText("Tasks 2")).assert(androidx.glance.appwidget.testing.unit.hasRunCallbackClickAction<SelectAction>(
            androidx.glance.action.actionParametersOf(KIND to "TASKS")))
    }

    @Test fun undoIsALineUnderTheList() = show(both.copy(selected = Kind.ACHIEVEMENTS, undoLabel = "Achieved: Run 10 km")) {
        onNode(androidx.glance.testing.unit.hasTextEqualTo("Undo")).assertDoesNotExist()
        onNode(hasText("Achieved: Run 10 km · Undo")).assertExists()
        onNode(hasText("Achievements 1")).assertExists()
    }

    @Test fun achievementRow_hasSeparateCircleAndName() = show(both.copy(selected = Kind.ACHIEVEMENTS)) {
        onNode(hasText("Learn to weld")).assert(androidx.glance.testing.unit.hasClickAction())             // opens its details...
        onAllNodes(androidx.glance.appwidget.testing.unit.isNotChecked()).assertCountEquals(1)   // ...the box beside it ticks
    }

    @Test fun nothingAssigned() = show(both.copy(selected = Kind.ACHIEVEMENTS, achievements = emptyList())) {
        onNode(hasText("Achievements 0")).assertExists()
        onNode(hasText("Nothing assigned to you")).assertExists()
    }

    @Test fun noProject_saysWhatToCreate() = show(both.copy(selected = Kind.ACHIEVEMENTS, achievements = emptyList(), noProject = "Create it")) {
        onNode(hasText("Create it")).assertExists()
        onNode(hasText("Tasks 2")).assertExists()
    }
}
