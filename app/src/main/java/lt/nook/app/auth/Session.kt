package lt.nook.app.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lt.nook.app.data.Kind
import lt.nook.app.data.NookDb
import lt.nook.app.data.Settings
import lt.nook.app.sync.SyncEngine
import lt.nook.app.sync.SyncError
import lt.nook.app.sync.Transport

sealed interface TestResult {
    /** [noAchievements]: the name of the optional Achievements project when Todoist has none (yet). */
    data class Ok(val projects: List<String>, val people: List<String>, val noAchievements: String? = null) : TestResult
    data object Rejected : TestResult
    data class Missing(val names: List<String>) : TestResult
    data object NoConnection : TestResult
}

/** Checks a token and the three project names against Todoist without saving anything. */
suspend fun testConnection(transport: Transport, token: String, names: Map<Kind, String>): TestResult = try {
    val r = transport.sync(token.trim(), "*", emptyList())
    val live = r.projects.filter { !it.isDeleted && !it.isArchived }
    val have = live.map { it.name }.toSet()
    val wanted = listOf(Kind.GROCERIES, Kind.SHOPPING, Kind.TASKS).map { names.getValue(it) }
    val missing = wanted.filter { it !in have }
    if (missing.isNotEmpty()) TestResult.Missing(missing)
    else {
        val tasksId = live.first { it.name == names.getValue(Kind.TASKS) }.id
        val members = r.collaboratorStates.filter { it.projectId == tasksId && it.state == "active" }.map { it.userId }.toSet() +
            listOfNotNull(r.user?.id)
        val people = (r.collaborators.associate { it.id to it.fullName } + listOfNotNull(r.user).associate { it.id to it.fullName })
            .filterKeys { it in members }.let { lt.nook.app.core.shortNames(it) }.values.sorted()
        val ach = names[Kind.ACHIEVEMENTS].orEmpty()
        TestResult.Ok(if (ach in have) wanted + ach else wanted, people, ach.takeIf { it !in have })
    }
} catch (e: SyncError.Auth) {
    TestResult.Rejected
} catch (e: SyncError) {
    TestResult.NoConnection
}

class Session(private val tokens: TokenStore, private val settings: Settings, private val db: NookDb,
              private val engine: SyncEngine? = null) {
    fun signIn(token: String, names: Map<Kind, String>) { tokens.set(token.trim()); settings.save(names) }

    /** Forgets the token, the project names and everything stored locally. */
    suspend fun signOut() {
        tokens.clear()
        settings.save(Settings.DEFAULTS)
        val clear: suspend () -> Unit = { withContext(Dispatchers.IO) { db.clearAllTables() } }
        if (engine != null) engine.wipe(clear) else clear()
    }
}
