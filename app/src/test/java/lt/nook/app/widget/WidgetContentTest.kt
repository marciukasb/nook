package lt.nook.app.widget

import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import lt.nook.app.core.ItemV
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WidgetContentTest {
    private fun t(id: String, text: String) = ItemV(id, text, null, null, 1, "u1", null, false)

    @Test fun showsMyTasksWithCount() = runGlanceAppWidgetUnitTest {
        setContext(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        provideComposable { WidgetContent(WidgetState(signedIn = true, tasks = listOf(t("1", "Call plumber"), t("2", "Fix shelf")), canUndo = false)) }
        onNode(hasText("My tasks · 2")).assertExists()
        onNode(hasText("Call plumber")).assertExists()
        onNode(hasText("Fix shelf")).assertExists()
        onNode(hasText("Undo")).assertDoesNotExist()
    }

    @Test fun undoAfterATick() = runGlanceAppWidgetUnitTest {
        setContext(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        provideComposable { WidgetContent(WidgetState(signedIn = true, tasks = emptyList(), canUndo = true)) }
        onNode(hasText("Undo")).assertExists()
        onNode(hasText("Nothing assigned to you")).assertExists()
    }

    @Test fun signedInBeforeFirstSync() = runGlanceAppWidgetUnitTest {
        setContext(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        provideComposable { WidgetContent(WidgetState(signedIn = true, tasks = emptyList(), canUndo = false, synced = false)) }
        onNode(hasText("Syncing…")).assertExists()
    }

    @Test fun repeatingTaskShowsItsDate() = runGlanceAppWidgetUnitTest {
        setContext(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        provideComposable { WidgetContent(WidgetState(signedIn = true, tasks = listOf(ItemV("1", "Water plants", null, null, 1, "u1", "2026-11-24", true)), canUndo = false)) }
        onNode(hasText("Water plants · Tue 24 Nov")).assertExists()
    }

    @Test fun signedOut() = runGlanceAppWidgetUnitTest {
        setContext(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        provideComposable { WidgetContent(WidgetState(signedIn = false, tasks = emptyList(), canUndo = false)) }
        onNode(hasText("Open Nook to sign in")).assertExists()
    }
}
