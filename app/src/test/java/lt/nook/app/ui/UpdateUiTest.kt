package lt.nook.app.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.StandardTestDispatcher
import lt.nook.app.update.Check
import lt.nook.app.update.Release
import lt.nook.app.update.Updater
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class UpdateUiTest {
    @get:Rule val rule = createComposeRule(StandardTestDispatcher())
    private val r = Release(12, "1.2", "u")

    @Test fun comingBackToNook_checksAgain() {
        var now = 0L
        var calls = 0
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("uu", Context.MODE_PRIVATE)
        val updater = Updater({ calls++; null }, prefs, { now }, 5, "o/nook")
        val owner = object : LifecycleOwner {
            val reg = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = reg
        }
        rule.runOnUiThread { owner.reg.currentState = Lifecycle.State.RESUMED }
        rule.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { rememberUpdate(updater) } }
        rule.waitUntil(5_000) { rule.waitForIdle(); calls == 1 }
        now += 24 * 3_600_000
        rule.runOnUiThread { owner.reg.currentState = Lifecycle.State.CREATED }      // another app in front
        rule.waitForIdle()
        rule.runOnUiThread { owner.reg.currentState = Lifecycle.State.RESUMED }
        rule.waitUntil(5_000) { rule.waitForIdle(); calls == 2 }
    }

    private fun section(result: Check, onInstall: (Release) -> Unit = {}) {
        rule.setContent { UpdateSection("1.4", { result }, onInstall) }
        rule.onNodeWithText("Nook 1.4").assertExists()
        rule.onNodeWithText("Check for updates").performClick()
        rule.waitForIdle()
    }

    @Test fun settings_upToDate() { section(Check.UpToDate); rule.onNodeWithText("You have the newest version").assertExists() }
    @Test fun settings_failed() { section(Check.Failed); rule.onNodeWithText("Couldn't check — try again").assertExists() }
    @Test fun settings_available() {
        var got: Release? = null
        section(Check.Available(r)) { got = it }
        rule.onNodeWithText("Update available (1.2)").assertExists()
        assertEquals(0, rule.onAllNodesWithText("Later").fetchSemanticsNodes().size)
        rule.onNodeWithText("Install").performClick()
        assertEquals(r, got)
    }
}
