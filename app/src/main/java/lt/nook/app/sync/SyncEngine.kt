package lt.nook.app.sync

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lt.nook.app.core.AchievementLists
import lt.nook.app.core.DONE_SECTION
import lt.nook.app.core.ItemV
import lt.nook.app.core.MAX_DESCRIPTION
import lt.nook.app.core.SectionV
import lt.nook.app.core.achievementLists
import lt.nook.app.core.isDoneSection
import lt.nook.app.core.joinDescription
import lt.nook.app.core.shortNames
import lt.nook.app.core.splitDescription
import lt.nook.app.data.IdMapE
import lt.nook.app.data.ItemE
import lt.nook.app.data.Kind
import lt.nook.app.data.MetaE
import lt.nook.app.data.NookDb
import lt.nook.app.data.PendingE
import lt.nook.app.data.PersonE
import lt.nook.app.data.ProjectE
import lt.nook.app.data.SectionE
import lt.nook.app.data.Settings
import java.time.LocalDate

sealed interface Status {
    data object Synced : Status
    data object Syncing : Status
    data object Offline : Status
    data object SignInAgain : Status
    data class ProjectsMissing(val names: List<String>) : Status
}

data class MyTasks(val me: String?, val tasks: List<ItemV>)

data class SyncState(val status: Status = Status.Syncing, val pending: Int = 0, val message: String? = null)

/** What an undo needs: the rows that were hidden and the close command that hid them. */
data class UndoToken(val closeUuid: String, val rows: List<ItemE>)

/** What undoing "achieved" needs: how the item was, and the commands that changed it. */
data class AchieveUndo(val before: ItemE, val uuids: List<String>)

sealed interface TickResult {
    data class Achieved(val undo: AchieveUndo) : TickResult
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
    /** Queued commands Todoist may have received although no answer came: undoing needs the reverse command. */
    private val sent = HashSet<String>()
    private var failures = 0
    private var nextAttempt = 0L
    private var needResync = false

    // ---- reading ----

    private fun nameOf(kind: Kind) = projectNames()[kind] ?: Settings.DEFAULTS.getValue(kind)

    fun items(kind: Kind): Flow<List<ItemV>> =
        combine(dao.observeItems(nameOf(kind)), dao.observePeople()) { items, people ->
            val names = shortNames(people.associate { it.id to it.name })
            items.map { it.toView(names) }
        }

    /** The signed-in person's Todoist id (known after the first sync). */
    suspend fun meId(): String? = dao.meta(USER_ID)

    /** Open Shared tasks assigned to the signed-in person, in the Tasks tab's order (home-screen widget); live.
     *  me = null until the first sync (or after sign-out). ItemV.assignee holds the person's Todoist id here. */
    fun myTasksFlow(): Flow<MyTasks> = mine(Kind.TASKS) { items, secs -> lt.nook.app.core.group(items, secs) }

    /** The same for achievements (those not in Done). */
    fun myAchievementsFlow(): Flow<MyTasks> = mine(Kind.ACHIEVEMENTS) { items, secs -> achievementLists(items, secs).open }

    private fun mine(kind: Kind, grouped: (List<ItemV>, List<SectionV>) -> List<lt.nook.app.core.Group>): Flow<MyTasks> {
        val name = nameOf(kind)
        return combine(dao.observeMeta(USER_ID), dao.observeItems(name), dao.observeSections(name)) { me, items, secs ->
            if (me == null) return@combine MyTasks(null, emptyList())
            val views = items.map { ItemV(it.id, it.content, it.sectionId, it.parentId, it.order, it.responsibleUid, it.dueDate, it.recurring, it.description) }
            val sections = secs.map { SectionV(it.id, it.name, it.order) }
            MyTasks(me, grouped(views, sections).flatMap { g -> g.rows.map { it.item } }.filter { it.assignee == me })
        }
    }

    suspend fun myTasks(): List<ItemV> = myTasksFlow().first().tasks

    fun sections(kind: Kind): Flow<List<SectionV>> =
        dao.observeSections(nameOf(kind)).map { l -> l.map { SectionV(it.id, it.name, it.order) } }

    /** Achievements, open and done. */
    fun achievements(): Flow<AchievementLists> =
        combine(items(Kind.ACHIEVEMENTS), sections(Kind.ACHIEVEMENTS)) { items, secs -> achievementLists(items, secs) }

    /** Sections an item can be moved to by hand (an achievement gets to Done only by achieving it). */
    fun moveTargets(kind: Kind): Flow<List<SectionV>> =
        sections(kind).map { l -> if (kind == Kind.ACHIEVEMENTS) l.filterNot { isDoneSection(it.name) } else l }

    /** Whether Todoist has this list's project (null until anything was synced); live. */
    fun hasProject(kind: Kind): Flow<Boolean?> = dao.observeProjects().map { l -> if (l.isEmpty()) null else l.any { it.name == nameOf(kind) } }

    fun noProjectText(kind: Kind) = "Create a shared Todoist project called \"${nameOf(kind)}\""

    /** One open item by id (also by the temporary id it had before its first sync); null when it is gone. */
    fun item(kind: Kind, id: String): Flow<ItemV?> = 
        combine(items(kind), dao.observeRealId(id)) { l, real -> l.firstOrNull { it.id == (real ?: id) } }

    /** Members of a list's project (the people something on it can be assigned to). */
    fun people(kind: Kind): Flow<List<PersonE>> = dao.observePeople().map { l ->
        val short = shortNames(l.associate { it.id to it.name })
        l.filter { if (kind == Kind.ACHIEVEMENTS) it.inAchievements else it.inTasks }.map { it.copy(name = short.getValue(it.id)) }
    }

    suspend fun projectId(kind: Kind): String? = dao.projects().firstOrNull { it.name == nameOf(kind) }?.id

    suspend fun openChildren(id: String): Int = descendants(id, dao.allItems().filter { !it.hidden }).size

    private fun ItemE.toView(names: Map<String, String>) =
        ItemV(id, content, sectionId, parentId, order, responsibleUid?.let { names[it] ?: "?" }, dueDate, recurring, description)

    fun clearMessage() = _state.update { it.copy(message = null) }

    // ---- actions ----
    // Every action changes the database at once and queues a command. With sync = true it then waits for a flush
    // (tests, worker); the UI passes sync = false and flushes in the background so nothing waits on the network.

    /** A temporary id Todoist has since replaced (kept forever so late callers still find the item). */
    private suspend fun resolve(id: String): String = dao.realId(id) ?: id

    /** Adds an item; [sectionId] may be a temp id from [newSection]. Returns its id, or null if lists aren't loaded. */
    suspend fun add(kind: Kind, content: String, sectionId: String?, assignee: String?, sync: Boolean = true,
                    description: String = ""): String? {
        val pid = projectId(kind)
        if (pid == null) { _state.update { it.copy(message = NOT_LOADED) }; return null }
        val name = content.trim()
        val sid = sectionId?.let { resolve(it) }
        val c = addItem(name, pid, sid, assignee, description)
        val id = c.tempId!!
        db.withTransaction {
            val order = (dao.itemsOf(pid).maxOfOrNull { it.order } ?: 0) + 1
            dao.upsertItems(listOf(ItemE(id, pid, sid, null, name, order, assignee, null, null, false, description = description)))
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
            val waiting = token.closeUuid !in inflight && token.closeUuid !in sent && dao.allPending().any { it.uuid == token.closeUuid }
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

    /**
     * Achieved: moves the item to the Done section (made if there is none) and adds the date to its description; it is
     * never completed. Repeating items and sub-items are ticked like any task.
     */
    suspend fun achieve(id0: String, today: LocalDate, sync: Boolean = true): TickResult {
        val id = resolve(id0)
        val first = dao.item(id) ?: return TickResult.NotFound
        if (first.recurring || first.parentId?.let { dao.item(it) } != null) return tick(id, sync)
        val undo = stateLock.withLock {
            db.withTransaction {
                val item = dao.item(resolve(id))?.takeIf { !it.hidden } ?: return@withTransaction null
                if (isDone(item)) return@withTransaction null
                val done = doneSection(item.projectId)
                val text = joinDescription(item.description, today)
                val cs = listOf(moveItem(item.id, done, item.projectId), dateChange(item.id))
                dao.upsertItems(listOf(item.copy(sectionId = done, parentId = null, description = text)))
                cs.forEach { queue(it, item.id, item.content) }
                AchieveUndo(item, cs.map { it.uuid })
            }
        } ?: return TickResult.NotFound
        after(sync)
        return TickResult.Achieved(undo)
    }

    /** Back to its old section and without the date (text written meanwhile stays). Waits only for the database. */
    suspend fun undoAchieve(u: AchieveUndo, sync: Boolean = true) {
        val cancelled = stateLock.withLock {
            db.withTransaction {
                val item = dao.item(resolve(u.before.id)) ?: return@withTransaction true
                val own = dao.allPending().filter { it.targetId == item.id }.map { it.uuid }
                // Nothing sent and nothing done since: just forget both changes.
                val waiting = u.uuids.none { it in inflight || it in sent } && own.takeLast(u.uuids.size) == u.uuids
                if (waiting) dao.deletePending(u.uuids)
                restore(item, u.before.sectionId?.let { resolve(it) }, splitDescription(u.before.description).second, send = !waiting)
                waiting
            }
        }
        if (cancelled) refreshPending() else after(sync)
    }

    /** A finished achievement is open again: out of Done, without its date. */
    suspend fun reopen(id0: String, sync: Boolean = true) {
        stateLock.withLock {
            db.withTransaction {
                val item = dao.item(resolve(id0))?.takeIf { isDone(it) } ?: return@withTransaction
                restore(item, null, null, send = true)
            }
        }
        after(sync)
    }

    private suspend fun restore(item: ItemE, sectionId: String?, day: LocalDate?, send: Boolean) {
        val text = joinDescription(item.description, day)
        dao.upsertItems(listOf(item.copy(sectionId = sectionId, description = text)))
        if (!send) return
        queue(moveItem(item.id, sectionId, item.projectId), item.id, item.content)
        queue(dateChange(item.id), item.id, item.content)
    }

    /** Replaces the text of a description; its date line stays. False (with a message) when it is too long. */
    suspend fun describe(id0: String, text: String, sync: Boolean = true): Boolean {
        if (text.trimEnd().length > MAX_DESCRIPTION) {
            _state.update { it.copy(message = "Descriptions can have $MAX_DESCRIPTION characters at most") }
            return false
        }
        val changed = stateLock.withLock {
            db.withTransaction {
                val item = dao.item(resolve(id0)) ?: return@withTransaction false
                val whole = joinDescription(text, splitDescription(item.description).second)
                if (whole == item.description) return@withTransaction false
                dao.upsertItems(listOf(item.copy(description = whole)))
                queue(updateDescription(item.id, whole), item.id, item.content)
                true
            }
        }
        if (changed) after(sync)
        return true
    }

    private suspend fun isDone(item: ItemE) = item.sectionId?.let { dao.section(it) }?.let { isDoneSection(it.name) } ?: false

    /** The project's Done section; queues creating it the first time. Inside a transaction. */
    private suspend fun doneSection(pid: String): String {
        val all = dao.sectionsOf(pid)
        all.firstOrNull { isDoneSection(it.name) }?.let { return it.id }
        val c = addSection(DONE_SECTION, pid)
        dao.upsertSections(listOf(SectionE(c.tempId!!, pid, DONE_SECTION, (all.maxOfOrNull { it.order } ?: 0) + 1)))
        queue(c, c.tempId, DONE_SECTION)
        return c.tempId
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
        return Kind.entries.firstOrNull { nameOf(it) == name }
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
                failures = 0; nextAttempt = 0; needResync = false; sent.clear()
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
        var fresh = false           // something was fetched during this flush
        val messages = mutableListOf<String>()
        while (rounds++ < 30) {
            var added = emptyList<String>()
            val (batch, commands) = stateLock.withLock {
                val b = nextBatch(fresh, if (single) 1 else MAX_COMMANDS)
                val cs = b.map { p -> decodeCommand(p.json).let { if (it.type == DATE_CHANGE) withNewestText(it) else withKnownIds(it) } }
                inflight += cs.map { it.uuid }
                added = cs.map { it.uuid }.filter { sent.add(it) }
                b to cs
            }
            val syncToken = dao.meta(SYNC_TOKEN) ?: "*"
            val resp = try {
                transport.sync(tok, syncToken, commands)
            } catch (e: SyncError.BadRequest) {
                stateLock.withLock { inflight.clear(); sent -= added.toSet() }
                when {
                    syncToken != "*" -> { dao.setMeta(MetaE(SYNC_TOKEN, "*")); continue }
                    commands.size > 1 -> { single = true; continue }
                    commands.size == 1 -> {
                        val p = batch.single()
                        db.withTransaction {
                            dao.deletePending(listOf(p.uuid))
                            p.targetId?.let { dropOptimistic(it, commands.single()); if (commands.single().type == "item_move") dropDates(it) }
                        }
                        messages += "Couldn't save ${p.label ?: "a change"}"
                        continue
                    }
                    else -> { fail(SyncError.Server(400)); report(messages); return }
                }
            } catch (e: SyncError) {
                val arrived = e is SyncError.Server || e is SyncError.Offline && e.mayHaveArrived
                stateLock.withLock { inflight.clear(); if (!arrived) sent -= added.toSet() }
                fail(e); report(messages); return
            }
            val failed = stateLock.withLock {
                try { apply(resp, batch, commands) } finally { inflight.clear() }
            }
            fresh = true
            failures = 0; nextAttempt = 0
            messages += failed
            if (needResync) { needResync = false; continue }
            if (failed.isNotEmpty()) continue   // full resync (token reset in apply) to get the server's truth
            if (dao.pendingCount() == 0) break
        }
        report(messages)
        _state.update { it.copy(status = missingProjects()?.let { m -> Status.ProjectsMissing(m) } ?: Status.Synced) }
    }

    /** Date changes go last, and only after a fetch: by then every queued move was answered and the texts are the newest. */
    private suspend fun nextBatch(fresh: Boolean, max: Int): List<PendingE> {
        val (dates, rest) = dao.allPending().partition { decodeCommand(it.json).type == DATE_CHANGE }
        val (live, gone) = dates.partition { p -> p.targetId?.let { dao.item(it) } != null }
        dao.deletePending(gone.map { it.uuid })
        return rest.ifEmpty { if (fresh) live else emptyList() }.take(max)
    }

    private suspend fun withNewestText(c: Command): Command {
        val id = resolve(c.arg("id")!!)
        return updateDescription(id, dao.item(id)!!.description).copy(uuid = c.uuid)
    }

    /** The move failed, so the date that went with it must not be written. */
    private suspend fun dropDates(target: String) =
        dao.deletePending(dao.allPending().filter { it.targetId == target && decodeCommand(it.json).type == DATE_CHANGE }.map { it.uuid })

    private fun report(messages: List<String>) {
        if (messages.isNotEmpty()) _state.update { it.copy(message = messages.distinct().joinToString("\n")) }
    }

    private suspend fun dropOptimistic(target: String, c: Command) {
        if (c.type == "item_add" || c.type == "item_close" || c.type == "item_move" || c.type == "item_update" || c.type == "item_uncomplete") dao.deleteItems(listOf(target))
        if (c.type == "section_add") dao.deleteSections(listOf(target))
    }

    private fun failureText(c: Command, label: String?, r: CommandResult.Failed): String {
        val name = label ?: "an item"
        return when (c.type) {
            "item_add" -> if (c.arg("section_id") != null) "Couldn't add $name — its category was deleted" else "Couldn't add $name"
            "section_add" -> "Couldn't create category $name"
            "item_move", "item_update" -> when {
                r.message.contains("item not found", ignoreCase = true) -> "$name was already removed"
                c.type == "item_move" -> "Couldn't move $name"
                else -> "Couldn't save the description of $name"
            }
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
        // Achievements are optional: without that project only its own tab says so.
        return (Kind.entries - Kind.ACHIEVEMENTS).map(::nameOf).filter { it !in have }.takeIf { it.isNotEmpty() }
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
                when (val r = resp.result(c.uuid)) {
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
                        failed += failureText(c, p.label, r)
                        // Drop the optimistic row; the full resync brings back whatever really exists.
                        (map[p.targetId] ?: p.targetId)?.let { dropOptimistic(it, c); if (c.type == "item_move") dropDates(it) }
                    }
                    null -> {}
                }
            }
            dao.deletePending(done)
            sent -= done.toSet()
            val protected = dao.allPending().mapNotNull { it.targetId }.toSet()
            if (merge(resp, protected)) resync = true
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

    /** True when the set of our projects changed: what was in a new one may be older than our sync token. */
    private suspend fun merge(resp: SyncResponse, protected: Set<String>): Boolean {
        // Projects
        val liveProjects = resp.projects.filter { !it.isDeleted && !it.isArchived }
        val goneProjects = resp.projects.filter { it.isDeleted || it.isArchived }.map { it.id }
        if (resp.fullSync) dao.deleteProjects(dao.projects().map { it.id } - liveProjects.map { it.id }.toSet())
        dao.deleteProjects(goneProjects)
        dao.upsertProjects(liveProjects.map { ProjectE(it.id, it.name) })
        val wanted = Kind.entries.map(::nameOf).toSet()
        val ours = dao.projects().filter { it.name in wanted }.map { it.id }.toSet()
        val oursText = ours.sorted().joinToString(",")
        val changed = !resp.fullSync && dao.meta(OURS) != oursText
        dao.setMeta(MetaE(OURS, oursText))

        // People: everyone known; which lists' projects they are active members of (+ me).
        resp.user?.let { dao.setMeta(MetaE(USER_ID, it.id)) }
        val known = dao.people()
        val names = (known.associate { it.id to it.name } + resp.collaborators.associate { it.id to it.fullName } +
            listOfNotNull(resp.user).associate { it.id to it.fullName })
        fun members(pid: String?, before: (PersonE) -> Boolean): Map<String, Boolean> {
            val m = known.associate { it.id to before(it) }.toMutableMap()
            resp.collaboratorStates.filter { it.projectId == pid }.forEach { m[it.userId] = it.state == "active" }
            resp.user?.let { if (pid != null) m[it.id] = true }
            return m
        }
        val inTasks = members(projectId(Kind.TASKS)) { it.inTasks }
        val inAch = members(projectId(Kind.ACHIEVEMENTS)) { it.inAchievements }
        dao.upsertPeople(names.map { (id, n) -> PersonE(id, n, inTasks[id] ?: false, inAch[id] ?: false) })

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
        val kinds = Kind.entries.associateBy(::nameOf)
        val projectName = dao.projects().associate { it.id to it.name }
        val queued = dao.allPending().groupBy({ it.targetId }, { decodeCommand(it.json).type })
        resp.items.forEach { i ->
            if (i.id in protected) {
                // Unsent changes stay, but a text written elsewhere is taken over unless ours is waiting too.
                val types = queued[i.id].orEmpty()
                val mine = dao.item(i.id)
                if (mine != null && !i.checked && !i.isDeleted && "item_update" !in types) dao.upsertItems(listOf(mine.copy(description =
                    if (DATE_CHANGE in types) joinDescription(i.description, splitDescription(mine.description).second) else i.description)))
                return@forEach
            }
            if (i.checked || i.isDeleted || i.projectId !in ours) { dao.deleteItems(listOf(i.id)); return@forEach }
            dao.upsertItems(listOf(ItemE(i.id, i.projectId, i.sectionId, i.parentId, i.content, i.childOrder,
                i.responsibleUid, i.due?.date, i.due?.string, i.due?.isRecurring == true, description = i.description)))
            kinds[projectName[i.projectId]]?.let { k -> learner?.observed(k, i.content, i.sectionId?.let { sectionNames[it] }) }
        }
        return changed
    }

    companion object {
        const val SYNC_TOKEN = "sync_token"
        const val USER_ID = "user_id"
        private const val OURS = "our_projects"
        const val NOT_LOADED = "Lists aren't loaded yet — pull down to refresh"
    }
}
