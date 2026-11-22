package lt.nook.app.widget

import kotlinx.coroutines.flow.MutableStateFlow
import lt.nook.app.sync.SyncEngine
import lt.nook.app.sync.TickResult
import lt.nook.app.sync.UndoToken

/**
 * Tick and undo from the home-screen widget. Changes go through the engine's offline queue (sent by the caller's
 * background flush), so a tick without signal is never lost or doubled. Undo is offered for the last tick only,
 * for 10 minutes; repeating tasks just move to their next date (no undo, like in the app).
 */
class WidgetActions(private val engine: SyncEngine, private val clock: () -> Long = System::currentTimeMillis) {
    private var last: UndoToken? = null
    private var lastAt = 0L
    /** Whether the widget shows "Undo" (live; a fresh process starts without one). */
    val undoAvailable = MutableStateFlow(false)

    suspend fun tick(id: String): TickResult {
        val r = engine.tick(id, sync = false)
        if (r is TickResult.Hidden) { last = r.undo; lastAt = clock() } else if (r !is TickResult.NotFound) last = null
        undoAvailable.value = last != null
        return r
    }

    fun canUndo() = last != null && clock() - lastAt < UNDO_MS

    suspend fun undo(): UndoToken? {
        val t = last?.takeIf { canUndo() }
        last = null
        undoAvailable.value = false
        if (t != null) engine.undo(t, sync = false)
        return t
    }

    /** Sign-out: an old account's tick must never be undone into a new one. */
    fun clear() { last = null; undoAvailable.value = false }

    companion object { const val UNDO_MS = 10 * 60_000L }
}
