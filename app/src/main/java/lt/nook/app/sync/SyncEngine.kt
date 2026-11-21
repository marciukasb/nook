package lt.nook.app.sync

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lt.nook.app.core.ItemV
import lt.nook.app.core.SectionV
import lt.nook.app.data.IdMapE
import lt.nook.app.data.ItemE
import lt.nook.app.data.Kind
import lt.nook.app.data.MetaE
import lt.nook.app.data.NookDb
import lt.nook.app.data.PendingE
import lt.nook.app.data.PersonE
import lt.nook.app.data.ProjectE
import lt.nook.app.data.SectionE

sealed interface Status {
    data object Synced : Status
    data object Syncing : Status
    data object Offline : Status
    data object SignInAgain : Status
    data class ProjectsMissing(val names: List<String>) : Status
}

data class SyncState(val status: Status = Status.Syncing, val pending: Int = 0, val message: String? = null)

/** What an undo needs: the rows that were hidden and the close command that hid them. */
data class UndoToken(val closeUuid: String, val rows: List<ItemE>)

sealed interface TickResult {
    data class Hidden(val undo: UndoToken) : TickResult
    /** A repeating task moved to its next date ([nextDate] null if not known yet, e.g. offline). */
    data class Advanced(val nextDate: String?) : TickResult
    data object NotFound : TickResult
    /** A repeating task already waiting to move to its next date (ticked again while offline). */
    data object AlreadyQueued : TickResult
}

/** Called by the engine so the category memory can learn (Task 6). */
interface Learner {
    suspend fun observed(kind: Kind, name: String, sectionName: String?)
    suspend fun used(kind: Kind, name: String, sectionName: String?)
}

/**
 * Local-first sync with Todoist. The UI reads only the database; actions change it at once and queue a command
 * (stable uuid) that [flush] sends with `/sync`. Server data never overwrites an item with an unacknowledged
 * command. Network errors back off (5 s doubling, max 5 min); a rejected token keeps the queue.
 */
class SyncEngine(
    private val db: NookDb,
    private val transport: Transport,
    private val token: () -> String?,
    private val projectNames: () -> Map<Kind, String>,
    private val clock: () -> Long,
    var learner: Learner? = null,
) {
    /** Runs after each successful sync, outside the sync lock (used to seed the category memory). */
    var onSynced: (suspend () -> Unit)? = null
    /** Called when a sync failed for network reasons with changes still queued (schedules a background flush). */
    var onStillPending: (() -> Unit)? = null
    private val dao = db.dao()
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val flushLock = Mutex()
    /** Guards the queue against an in-flight batch (short holds only, never across a network call). */
    private val stateLock = Mutex()
    private val inflight = HashSet<String>()
    private var failures = 0
    private var nextAttempt = 0L
    private var needResync = false

    // ---- reading ----

    private fun nameOf(kind: Kind) = projectNames().getValue(kind)

    fun items(kind: Kind): Flow<List<ItemV>> =
        combine(dao.observeItems(nameOf(kind)), dao.observePeople()) { items, people ->
            val names = people.associate { it.id to it.name }
            items.map { it.toView(names) }
        }

    fun sections(kind: Kind): Flow<List<SectionV>> =
        dao.observeSections(nameOf(kind)).map { l -> l.map { SectionV(it.id, it.name, it.order) } }

    /** Collaborators of Shared tasks (the people a task can be assigned to). */
    fun people(): Flow<List<PersonE>> = dao.observePeople().map { l -> l.filter { it.inTasks } }

    suspend fun projectId(kind: Kind): String? = dao.projects().firstOrNull { it.name == nameOf(kind) }?.id

    suspend fun openChildren(id: String): Int = descendants(id, dao.allItems().filter { !it.hidden }).size

    private fun ItemE.toView(names: Map<String, String>) =
        ItemV(id, content, sectionId, parentId, order, responsibleUid?.let { names[it] ?: "?" }, dueDate, recurring)

    fun clearMessage() = _state.update { it.copy(message = null) }

    // ---- actions ----
    // Every action changes the database at once and queues a command. With sync = true it then waits for a flush
    // (tests, worker); the UI passes sync = false and flushes in the background so nothing waits on the network.

    /** A temporary id Todoist has since replaced (kept forever so late callers still find the item). */
    private suspend fun resolve(id: String): String = dao.realId(id) ?: id

    /** Adds an item; [sectionId] may be a temp id from [newSection]. Returns its id, or null if lists aren't loaded. */
    suspend fun add(kind: Kind, content: String, sectionId: String?, assignee: String?, sync: Boolean = true): String? {
        val pid = projectId(kind)
        if (pid == null) { _state.update { it.copy(message = NOT_LOADED) }; return null }
        val name = content.trim()
        val sid = sectionId?.let { resolve(it) }
        val c = addItem(name, pid, sid, assignee)
        val id = c.tempId!!
        db.withTransaction {
            val order = (dao.itemsOf(pid).maxOfOrNull { it.order } ?: 0) + 1
            dao.upsertItems(listOf(ItemE(id, pid, sid, null, name, order, assignee, null, null, false)))
            queue(c, id, name)
        }
        learner?.used(kind, name, sid?.let { dao.section(it)?.name })
        after(sync)
        return id
    }

    /** Creates a section (category/shop) locally and queues it; sent with the next flush. Returns its temp id. */
    suspend fun newSection(kind: Kind, name: String): String? {
        val pid = projectId(kind)
        if (pid == null) { _state.update { it.copy(message = NOT_LOADED) }; return null }
        val c = addSection(name.trim(), pid)
        db.withTransaction {
            val order = (dao.sectionsOf(pid).maxOfOrNull { it.order } ?: 0) + 1
            dao.upsertSections(listOf(SectionE(c.tempId!!, pid, name.trim(), order)))
            queue(c, c.tempId, name.trim())
        }
        refreshPending()
        return c.tempId!!
    }

    suspend fun tick(id0: String, sync: Boolean = true): TickResult {
        val id = resolve(id0)
        val item = dao.item(id) ?: return TickResult.NotFound
        if (item.recurring) {
            val c = closeItem(id)
            val queued = stateLock.withLock {
                val already = dao.allPending().any { p -> p.targetId == id && decodeCommand(p.json).type == "item_close" }
                if (!already) queue(c, id, item.content)
                !already
            }
            if (!queued) return TickResult.AlreadyQueued
            if (!sync) { refreshPending(); return TickResult.Advanced(null) }
            flush(force = true)
            val after = dao.item(id)
            val done = dao.allPending().none { it.uuid == c.uuid }
            return TickResult.Advanced(if (done && after != null && after.dueDate != item.dueDate) after.dueDate else null)
        }
        val c = closeItem(id)
        val rows = listOf(item) + descendants(id, dao.allItems().filter { !it.hidden })
        stateLock.withLock {
            db.withTransaction {
                dao.setHidden(rows.map { it.id }, true)
                queue(c, id, item.content)
            }
        }
        after(sync)
        return TickResult.Hidden(UndoToken(c.uuid, rows))
    }

    /** Waits only for the database, never for a request in flight. */
    suspend fun undo(token: UndoToken, sync: Boolean = true) {
        val cancelled = stateLock.withLock {
            // Not sent yet (offline): just forget the close.
            val waiting = token.closeUuid !in inflight && dao.allPending().any { it.uuid == token.closeUuid }
            val rows = token.rows.map { r -> val real = resolve(r.id); r.copy(id = real, parentId = r.parentId?.let { resolve(it) }, hidden = false) }
            db.withTransaction {
                if (waiting) dao.deletePending(listOf(token.closeUuid))
                dao.deleteItems(token.rows.map { it.id })
                dao.upsertItems(rows)
                if (!waiting) rows.forEach { queue(uncompleteItem(it.id), it.id, it.content) }
            }
            waiting
        }
        refreshPending()
        if (!cancelled) after(sync)
    }

    /** Moves an item to [sectionId] (null = no section) within its project. */
    suspend fun move(id0: String, sectionId0: String?, sync: Boolean = true) {
        val id = resolve(id0)
        val sectionId = sectionId0?.let { resolve(it) }
        val item = dao.item(id) ?: return
        stateLock.withLock {
            db.withTransaction {
                dao.upsertItems(listOf(item.copy(sectionId = sectionId)))
                queue(moveItem(id, sectionId, item.projectId), id, item.content)
            }
        }
        kindOf(item.projectId)?.let { learner?.used(it, item.content, sectionId?.let { s -> dao.section(s)?.name }) }
        after(sync)
    }

    private suspend fun after(sync: Boolean) { if (sync) flush(force = true) else refreshPending() }

    private suspend fun queue(c: Command, target: String?, label: String?) {
        val mapped = withKnownIds(c)
        dao.insertPending(PendingE(uuid = c.uuid, json = encodeCommand(mapped), targetId = target?.let { resolve(it) }, label = label))
    }

    /** Replaces any temp ids in the command's args that Todoist has already given real ids. */
    private suspend fun withKnownIds(c: Command): Command {
        val ids = c.args.values.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        val map = ids.mapNotNull { id -> dao.realId(id)?.let { id to it } }.toMap()
        return if (map.isEmpty()) c else c.mapIds(map)
    }

    private fun descendants(id: String, items: List<ItemE>): List<ItemE> {
        val kids = items.filter { it.parentId == id }
        return kids.flatMap { listOf(it) + descendants(it.id, items) }
    }

    private suspend fun kindOf(projectId: String): Kind? {
        val name = dao.projects().firstOrNull { it.id == projectId }?.name ?: return null
        return projectNames().entries.firstOrNull { it.value == name }?.key
    }

    private suspend fun refreshPending() {
        val n = dao.pendingCount()
        _state.update { it.copy(pending = n) }
    }

    // ---- syncing ----

    /** Sends queued commands and fetches changes. Never throws. Respects backoff unless [force]d by the user. */
    suspend fun flush(force: Boolean = false) {
        flushLock.withLock { flushLocked(force) }
        refreshPending()
        val st = _state.value
        if (st.status == Status.Synced) onSynced?.invoke()
        if (st.status == Status.Offline && st.pending > 0) onStillPending?.invoke()
    }

    /** Next sync fetches everything again (e.g. after the project names changed). */
    suspend fun resetSync() = dao.setMeta(MetaE(SYNC_TOKEN, "*"))

    /** Runs [block] (e.g. clearing the database on sign out) with no sync in flight, then forgets sync state. */
    suspend fun wipe(block: suspend () -> Unit) {
        flushLock.withLock {
            stateLock.withLock {
                block()
                failures = 0; nextAttempt = 0; needResync = false
            }
        }
        _state.value = SyncState(status = Status.SignInAgain)
    }

    private suspend fun flushLocked(force: Boolean) {
        val tok = token()
        if (tok == null) { _state.update { it.copy(status = Status.SignInAgain) }; return }
        if (!force && clock() < nextAttempt) return
        _state.update { it.copy(status = Status.Syncing) }
        var rounds = 0
        var single = false          // after a 400 with a full sync token: send one command at a time
        val messages = mutableListOf<String>()
        while (rounds++ < 30) {
            val (batch, commands) = stateLock.withLock {
                val b = dao.pending(if (single) 1 else MAX_COMMANDS)
                val cs = b.map { withKnownIds(decodeCommand(it.json)) }
                inflight += cs.map { it.uuid }
                b to cs
            }
            val syncToken = dao.meta(SYNC_TOKEN) ?: "*"
            val resp = try {
                transport.sync(tok, syncToken, commands)
            } catch (e: SyncError.BadRequest) {
                stateLock.withLock { inflight.clear() }
                when {
                    syncToken != "*" -> { dao.setMeta(MetaE(SYNC_TOKEN, "*")); continue }
                    commands.size > 1 -> { single = true; continue }
                    commands.size == 1 -> {
                        val p = batch.single()
                        db.withTransaction {
                            dao.deletePending(listOf(p.uuid))
                            p.targetId?.let { dropOptimistic(it, commands.single()) }
                        }
                        messages += "Couldn't save ${p.label ?: "a change"}"
                        continue
                    }
                    else -> { fail(SyncError.Server(400)); report(messages); return }
                }
            } catch (e: SyncError) {
                stateLock.withLock { inflight.clear() }
                fail(e); report(messages); return
            }
            val failed = stateLock.withLock {
                try { apply(resp, batch, commands) } finally { inflight.clear() }
            }
            failures = 0; nextAttempt = 0
            messages += failed
            if (needResync) { needResync = false; continue }
            if (failed.isNotEmpty()) continue   // full resync (token reset in apply) to get the server's truth
            if (dao.pendingCount() == 0) break
        }
        report(messages)
        _state.update { it.copy(status = missingProjects()?.let { m -> Status.ProjectsMissing(m) } ?: Status.Synced) }
    }

    private fun report(messages: List<String>) {
        if (messages.isNotEmpty()) _state.update { it.copy(message = messages.distinct().joinToString("\n")) }
    }

    private suspend fun dropOptimistic(target: String, c: Command) {
        if (c.type == "item_add" || c.type == "item_close" || c.type == "item_move" || c.type == "item_uncomplete") dao.deleteItems(listOf(target))
        if (c.type == "section_add") dao.deleteSections(listOf(target))
    }

    private fun failureText(c: Command, label: String?): String {
        val name = label ?: "an item"
        return when (c.type) {
            "item_add" -> if (c.arg("section_id") != null) "Couldn't add $name — its category was deleted" else "Couldn't add $name"
            "section_add" -> "Couldn't create category $name"
            else -> "$name was already removed"
        }
    }

    private fun fail(e: SyncError) {
        if (e is SyncError.Auth) { _state.update { it.copy(status = Status.SignInAgain) }; return }
        failures++
        var delay = minOf(5_000L shl minOf(failures - 1, 10), 300_000L)
        if (e is SyncError.RateLimited && e.retryAfterS != null) delay = maxOf(delay, e.retryAfterS * 1000)
        nextAttempt = clock() + delay
        _state.update { it.copy(status = Status.Offline) }
    }

    private suspend fun missingProjects(): List<String>? {
        val have = dao.projects().map { it.name }.toSet()
        return projectNames().values.filter { it !in have }.takeIf { it.isNotEmpty() }
    }

    /** Applies one sync response. Returns messages for commands Todoist refused. */
    private suspend fun apply(resp: SyncResponse, batch: List<PendingE>, commands: List<Command>): List<String> =
        db.withTransaction {
            val map = resp.tempIdMapping
            if (map.isNotEmpty()) remap(map)
            val failed = mutableListOf<String>()
            val done = mutableListOf<String>()
            var resync = false
            batch.zip(commands).forEach { (p, c) ->
                when (resp.result(c.uuid)) {
                    CommandResult.Ok -> {
                        done += c.uuid
                        // A replayed add (its first response was lost) comes back "ok" without a temp id mapping:
                        // drop the temporary row and let a full resync bring the real one, so nothing doubles.
                        if (c.tempId != null && c.tempId !in map && dao.realId(c.tempId) == null) {
                            dao.deleteItems(listOf(c.tempId)); dao.deleteSections(listOf(c.tempId))
                            resync = true
                        }
                    }
                    is CommandResult.Failed -> {
                        done += c.uuid
                        failed += failureText(c, p.label)
                        // Drop the optimistic row; the full resync brings back whatever really exists.
                        (map[p.targetId] ?: p.targetId)?.let { dropOptimistic(it, c) }
                    }
                    null -> {}
                }
            }
            dao.deletePending(done)
            val protected = dao.allPending().mapNotNull { it.targetId }.toSet()
            merge(resp, protected)
            dao.setMeta(MetaE(SYNC_TOKEN, if (failed.isEmpty() && !resync) resp.syncToken else "*"))
            if (resync && failed.isEmpty()) needResync = true
            failed
        }

    private suspend fun remap(map: Map<String, String>) {
        dao.saveIds(map.map { (t, r) -> IdMapE(t, r) })
        val items = dao.allItems()
        val changed = items.filter { it.id in map || it.parentId in map || it.sectionId in map }
        if (changed.isNotEmpty()) {
            dao.deleteItems(changed.map { it.id })
            dao.upsertItems(changed.map {
                it.copy(id = map[it.id] ?: it.id, parentId = map[it.parentId] ?: it.parentId, sectionId = map[it.sectionId] ?: it.sectionId)
            })
        }
        map.keys.mapNotNull { dao.section(it) }.forEach { s ->
            dao.deleteSections(listOf(s.id)); dao.upsertSections(listOf(s.copy(id = map.getValue(s.id))))
        }
        val pend = dao.allPending()
        val rewritten = pend.filter { p -> p.targetId in map || map.keys.any { it in p.json } }
        if (rewritten.isNotEmpty()) dao.upsertPending(rewritten.map { p ->
            p.copy(json = encodeCommand(decodeCommand(p.json).mapIds(map)), targetId = map[p.targetId] ?: p.targetId)
        })
    }

    private suspend fun merge(resp: SyncResponse, protected: Set<String>) {
        // Projects
        val liveProjects = resp.projects.filter { !it.isDeleted && !it.isArchived }
        val goneProjects = resp.projects.filter { it.isDeleted || it.isArchived }.map { it.id }
        if (resp.fullSync) dao.deleteProjects(dao.projects().map { it.id } - liveProjects.map { it.id }.toSet())
        dao.deleteProjects(goneProjects)
        dao.upsertProjects(liveProjects.map { ProjectE(it.id, it.name) })
        val wanted = projectNames().values.toSet()
        val ours = dao.projects().filter { it.name in wanted }.map { it.id }.toSet()

        // People: everyone known; "inTasks" from the Shared tasks project's active members (+ me).
        resp.user?.let { dao.setMeta(MetaE(USER_ID, it.id)) }
        val tasksId = dao.projects().firstOrNull { it.name == projectNames()[Kind.TASKS] }?.id
        val names = (dao.people().associate { it.id to it.name } + resp.collaborators.associate { it.id to it.fullName } +
            listOfNotNull(resp.user).associate { it.id to it.fullName })
        val states = resp.collaboratorStates.filter { it.projectId == tasksId }
        val membership = dao.people().associate { it.id to it.inTasks }.toMutableMap()
        states.forEach { membership[it.userId] = it.state == "active" }
        resp.user?.let { if (tasksId != null) membership[it.id] = true }
        dao.upsertPeople(names.map { (id, n) -> PersonE(id, n, membership[id] ?: false) })

        // Sections
        if (resp.fullSync) {
            val keep = resp.sections.map { it.id }.toSet() + protected
            dao.deleteSections(ours.flatMap { dao.sectionsOf(it) }.map { it.id }.filter { it !in keep })
        }
        resp.sections.forEach { s ->
            if (s.id in protected) return@forEach
            if (s.isDeleted || s.isArchived || s.projectId !in ours) dao.deleteSections(listOf(s.id))
            else dao.upsertSections(listOf(SectionE(s.id, s.projectId, s.name, s.sectionOrder)))
        }

        // Items
        if (resp.fullSync) {
            val keep = resp.items.map { it.id }.toSet() + protected
            dao.deleteItems(dao.allItems().map { it.id }.filter { it !in keep })
        }
        val sectionNames = ours.flatMap { dao.sectionsOf(it) }.associate { it.id to it.name }
        val kinds = projectNames().entries.associate { (k, n) -> n to k }
        val projectName = dao.projects().associate { it.id to it.name }
        resp.items.forEach { i ->
            if (i.id in protected) return@forEach
            if (i.checked || i.isDeleted || i.projectId !in ours) { dao.deleteItems(listOf(i.id)); return@forEach }
            dao.upsertItems(listOf(ItemE(i.id, i.projectId, i.sectionId, i.parentId, i.content, i.childOrder,
                i.responsibleUid, i.due?.date, i.due?.string, i.due?.isRecurring == true)))
            kinds[projectName[i.projectId]]?.let { k -> learner?.observed(k, i.content, i.sectionId?.let { sectionNames[it] }) }
        }
    }

    companion object {
        const val SYNC_TOKEN = "sync_token"
        const val USER_ID = "user_id"
        const val NOT_LOADED = "Lists aren't loaded yet — pull down to refresh"
    }
}
