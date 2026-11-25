package lt.nook.app.ui

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import lt.nook.app.auth.InMemoryTokenStore
import lt.nook.app.auth.Session
import lt.nook.app.auth.testConnection
import lt.nook.app.data.Kind
import lt.nook.app.data.NookDb
import lt.nook.app.data.Settings
import lt.nook.app.sync.FakeTodoist
import lt.nook.app.sync.SyncEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SignInTest {
    @get:Rule val rule = createComposeRule()
    private val fake = FakeTodoist()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private var saved: Pair<String, Map<Kind, String>>? = null

    private fun show() = rule.setContent {
        SignInScreen(Settings.DEFAULTS, onTest = { t, n -> testConnection(fake, t, n) }, onSave = { t, n -> saved = t to n })
    }

    @Test fun test_showsFoundProjectsAndPeople() {
        show()
        rule.onNodeWithTag("token").performTextInput("good")
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Test").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Found Groceries, Shopping, Shared tasks").assertExists()
        rule.onNodeWithText("People: Alex, Sam").assertExists()
        rule.onNodeWithText("Save").assertIsEnabled().performClick()
        assertEquals("good", saved!!.first)
        assertEquals("Shared tasks", saved!!.second[Kind.TASKS])
    }

    @Test fun signIn_savesWithoutAchievements() {
        show()
        rule.onNodeWithTag("token").performTextInput("good")
        rule.onNodeWithText("Test").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("No \"Achievements\" project yet. That tab will tell you how to make one.").assertExists()
        rule.onNodeWithText("Save").assertIsEnabled()
    }

    @Test fun signIn_findsAchievements() {
        fake.addAchievementsProject()
        show()
        rule.onNodeWithTag("token").performTextInput("good")
        rule.onNodeWithText("Test").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Found Groceries, Shopping, Shared tasks, Achievements").assertExists()
    }

    @Test fun badToken_showsRejected_saveDisabled() {
        show()
        rule.onNodeWithTag("token").performTextInput("nope")
        rule.onNodeWithText("Test").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Token rejected — copy it again from Todoist").assertExists()
        rule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun missingProject_namesIt() {
        fake.renameProject(fake.shopping, "Shop")
        show()
        rule.onNodeWithTag("token").performTextInput("good")
        rule.onNodeWithText("Test").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Project \"Shopping\" not found").assertExists()
        rule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun signOut_clearsDatabase() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ctx, NookDb::class.java).allowMainThreadQueries().build()
        val tokens = InMemoryTokenStore("good")
        val settings = Settings(ctx.getSharedPreferences("t", Context.MODE_PRIVATE))
        settings.save(mapOf(Kind.GROCERIES to "Food", Kind.SHOPPING to "Shopping", Kind.TASKS to "Shared tasks"))
        val engine = SyncEngine(db, fake, { tokens.get() }, { settings.projectNames() }, { 0L })
        fake.item(fake.groceries, "Milk")
        settings.save(Settings.DEFAULTS); engine.flush()
        Session(tokens, settings, db).signOut()
        assertNull(tokens.get())
        assertEquals(Settings.DEFAULTS, settings.projectNames())
        assertEquals(0, db.dao().allItems().size)
        assertEquals(0, db.dao().projects().size)
        db.close()
    }
}
