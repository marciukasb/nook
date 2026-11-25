package lt.nook.app.widget

import kotlinx.coroutines.flow.MutableStateFlow
import lt.nook.app.sync.SyncEngine
import lt.nook.app.sync.TickResult
import java.time.LocalDate

/**
 * Tick and undo from the home-screen widget. Changes go through the engine's offline queue (sent by the caller's
 * background flush), so a tick without signal is never lost or doubled. Undo is offered for the last tick only,
 * for 10 minutes; repeating tasks just move to their next date (no undo, like in the app).
 */
class WidgetActions(private val engine: SyncEngine, private val clock: () -> Long = System::currentTimeMillis) {
    private var last: (suspend () -> Unit)? = null
    private var lastAt = 0L
    /** What "Undo" would undo, e.g. "Done: Call plumber" (live; a fresh process starts without one). */
    val undoLabel = MutableStateFlow<String?>(null)

    suspend fun tick(id: String): TickResult = engine.tick(id, sync = false).also(::remember)

    suspend fun achieve(id: String, today: LocalDate = LocalDate.now()): TickResult = engine.achieve(id, today, sync = false).also(::remember)

    private fun remember(r: TickResult) {
        when (r) {
            is TickResult.Hidden -> set("Done: ${r.undo.rows.first().content}") { engine.undo(r.undo, sync = false) }
            is TickResult.Achieved -> set("Achieved: ${r.undo.before.content}") { engine.undoAchieve(r.undo, sync = false) }
            is TickResult.Advanced, TickResult.AlreadyQueued -> clear()
            TickResult.NotFound -> {}                      // nothing happened (e.g. tapped twice): the last one can still be undone
        }
    }

    private fun set(label: String, undo: suspend () -> Unit) { last = undo; lastAt = clock(); undoLabel.value = label }

    fun canUndo() = last != null && clock() - lastAt < UNDO_MS

    /** False when there was nothing (any more) to undo. */
    suspend fun undo(): Boolean {
        val u = last?.takeIf { canUndo() }
        clear()
        u?.invoke()
        return u != null
    }

    /** Sign-out: an old account's tick must never be undone into a new one. */
    fun clear() { last = null; undoLabel.value = null }

    companion object { const val UNDO_MS = 10 * 60_000L }
}
