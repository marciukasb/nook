package lt.nook.app.sync

import java.time.Instant
import java.time.LocalDate

/**
 * In-memory Todoist for tests: the six commands Nook uses, uuid de-duplication, incremental sync tokens and
 * switches to simulate trouble. All data is fake.
 */
class FakeTodoist : Transport {
    val groceries = "p1"; val shopping = "p2"; val tasks = "p3"; val achievements = "p4"
    val me = "u1"; val partner = "u2"
    var token = "good"
    var calls = 0
    val received = mutableListOf<List<Command>>()

    private var rev = 0
    private var nextId = 100
    private val applied = HashSet<String>()
    private val projects = mutableListOf(
        RProject(groceries, "Groceries"), RProject(shopping, "Shopping"), RProject(tasks, "Shared tasks"), RProject("p9", "Inbox"))
    private val people = listOf(RCollaborator(me, "Alex"), RCollaborator(partner, "Sam"))
    private val items = LinkedHashMap<String, Pair<RItem, Int>>()      // id -> (item, rev)
    private val sections = LinkedHashMap<String, Pair<RSection, Int>>()
    val completedHistory = mutableListOf<Pair<RItem, Instant>>()

    private var failNext: SyncError? = null
    private var dropAfterApply = false
    /** The sync call (counted in [calls]) that gets no connection. */
    var failAtCall = -1
    var refuseUpdate: String? = null
    var completedError: SyncError? = null
    /** Makes Todoist answer 400 for any request whose commands match. */
    var reject400: ((List<Command>) -> Boolean)? = null

    fun failNext(e: SyncError) { failNext = e }
    fun dropNextResponseAfterApply() { dropAfterApply = true }
    fun revokeToken() { token = "revoked" }
    fun renameProject(id: String, name: String) { projects.replaceAll { if (it.id == id) it.copy(name = name) else it }; rev++ }

    private val states = mutableListOf(RCollabState(tasks, me), RCollabState(tasks, partner))
    /** The optional fourth list; not there unless a test creates it. */
    fun addAchievementsProject(name: String = "Achievements", members: List<String> = listOf(me, partner)) {
        projects += RProject(achievements, name); members.forEach { states += RCollabState(achievements, it) }; rev++
    }
    /** Someone edits a description elsewhere. */
    fun describeElsewhere(id: String, text: String) { val (i, _) = items.getValue(id); items[id] = i.copy(description = text) to ++rev }

    fun section(project: String, name: String, order: Int = sections.size + 1): String {
        val id = "s${nextId++}"; sections[id] = RSection(id, project, name, order) to ++rev; return id
    }
    fun item(project: String, content: String, section: String? = null, parent: String? = null,
             responsible: String? = null, due: RDue? = null, description: String = ""): String {
        val id = "i${nextId++}"
        val order = items.values.count { it.first.projectId == project } + 1
        items[id] = RItem(id, project, section, parent, content, childOrder = order, responsibleUid = responsible, due = due, description = description) to ++rev
        return id
    }
    /** Another phone or the kiosk deletes/ticks an item. */
    fun deleteItem(id: String) { val (i, _) = items.getValue(id); items[id] = i.copy(isDeleted = true) to ++rev }
    fun closeElsewhere(id: String) { val (i, _) = items.getValue(id); items[id] = i.copy(checked = true) to ++rev }
    fun moveElsewhere(id: String, section: String?) { val (i, _) = items.getValue(id); items[id] = i.copy(sectionId = section) to ++rev }
    fun history(project: String, content: String, section: String?, at: Instant) {
        completedHistory += RItem("h${nextId++}", project, section, null, content, checked = true) to at
    }
    fun deleteSection(id: String) { val (s, _) = sections.getValue(id); sections[id] = s.copy(isDeleted = true) to ++rev }

    fun open(project: String) = items.values.map { it.first }.filter { it.projectId == project && !it.checked && !it.isDeleted }
    fun get(id: String) = items[id]?.first
    fun sectionsOf(project: String) = sections.values.map { it.first }.filter { it.projectId == project && !it.isDeleted }

    override suspend fun sync(token: String, syncToken: String, commands: List<Command>): SyncResponse {
        calls++
        if (token != this.token) throw SyncError.Auth()
        failNext?.let { failNext = null; throw it }
        if (calls == failAtCall) throw SyncError.Offline()
        if (reject400?.invoke(commands) == true) throw SyncError.BadRequest()
        require(commands.size <= MAX_COMMANDS)
        received += commands
        val status = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        val mapping = LinkedHashMap<String, String>()
        for (raw in commands) {
            if (raw.uuid in applied) { status[raw.uuid] = kotlinx.serialization.json.JsonPrimitive("ok"); continue }
            val c = raw.mapIds(mapping + tempIds)
            val err = apply(c, mapping)
            status[raw.uuid] = if (err == null) kotlinx.serialization.json.JsonPrimitive("ok")
                else kotlinx.serialization.json.buildJsonObject { put("error_code", kotlinx.serialization.json.JsonPrimitive(22)); put("error", kotlinx.serialization.json.JsonPrimitive(err)) }
            applied += raw.uuid
        }
        tempIds.putAll(mapping)
        if (dropAfterApply) { dropAfterApply = false; throw SyncError.Offline(mayHaveArrived = true) }
        val since = syncToken.removePrefix("v").toIntOrNull()
        val full = since == null
        return SyncResponse(
            syncToken = "v$rev", fullSync = full,
            user = RUser(me, "Alex"),
            projects = projects,
            sections = sections.values.filter { full || it.second > since!! }.map { it.first },
            items = items.values.filter { full && !it.first.checked && !it.first.isDeleted || !full && it.second > since!! }.map { it.first },
            collaborators = people,
            collaboratorStates = states.toList(),
            syncStatus = status, tempIdMapping = mapping,
        )
    }

    private val tempIds = HashMap<String, String>()

    private fun apply(c: Command, mapping: MutableMap<String, String>): String? {
        when (c.type) {
            "item_add" -> {
                c.arg("section_id")?.let { sid -> if (sections[sid]?.first?.isDeleted != false) return "Section not found" }
                val id = item(c.arg("project_id")!!, c.arg("content")!!, c.arg("section_id"), null, c.arg("responsible_uid"), description = c.arg("description") ?: "")
                mapping[c.tempId!!] = id
            }
            "section_add" -> mapping[c.tempId!!] = section(c.arg("project_id")!!, c.arg("name")!!)
            "item_close" -> {
                val i = live(c.arg("id")) ?: return "Item not found"
                if (i.due?.isRecurring == true) {
                    items[i.id] = i.copy(due = i.due.copy(date = LocalDate.parse(i.due.date).plusDays(7).toString())) to ++rev
                } else {
                    items[i.id] = i.copy(checked = true) to ++rev
                    items.values.map { it.first }.filter { it.parentId == i.id && !it.checked }.forEach { items[it.id] = it.copy(checked = true) to ++rev }
                }
            }
            "item_uncomplete" -> {
                val i = items[c.arg("id")]?.first?.takeIf { !it.isDeleted } ?: return "Item not found"
                items[i.id] = i.copy(checked = false) to ++rev
            }
            "item_move" -> {
                val i = live(c.arg("id")) ?: return "Item not found"
                val sec = c.arg("section_id")
                if (sec != null && sections[sec]?.first?.isDeleted != false) return "Section not found"
                items[i.id] = (if (sec != null) i.copy(sectionId = sec) else i.copy(sectionId = null, projectId = c.arg("project_id")!!)) to ++rev
            }
            "item_update" -> {
                if (c.args.keys != setOf("id", "description")) return "Only descriptions may be updated"
                val i = live(c.arg("id")) ?: return "Item not found"
                refuseUpdate?.let { return it }
                items[i.id] = i.copy(description = c.arg("description")!!) to ++rev
            }
            else -> return "Unknown command"
        }
        return null
    }

    private fun live(id: String?) = items[id]?.first?.takeIf { !it.isDeleted && !it.checked }

    var completedCalls = 0
    override suspend fun completed(token: String, projectId: String, since: Instant, until: Instant, cursor: String?): CompletedPage {
        completedCalls++
        completedError?.let { throw it }
        if (token != this.token) throw SyncError.Auth()
        val all = completedHistory.filter { it.first.projectId == projectId && it.second >= since && it.second < until }.map { it.first }
        val start = cursor?.toInt() ?: 0
        val page = all.drop(start).take(2)
        return CompletedPage(page, if (start + 2 < all.size) "${start + 2}" else null)
    }
}
