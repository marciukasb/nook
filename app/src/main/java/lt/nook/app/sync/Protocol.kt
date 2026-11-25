package lt.nook.app.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/** Subset of Todoist API v1 `/sync` that Nook uses. Unknown fields are ignored. */
val TodoistJson = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

@Serializable
data class SyncResponse(
    @SerialName("sync_token") val syncToken: String,
    @SerialName("full_sync") val fullSync: Boolean = false,
    val user: RUser? = null,
    val projects: List<RProject> = emptyList(),
    val sections: List<RSection> = emptyList(),
    val items: List<RItem> = emptyList(),
    val collaborators: List<RCollaborator> = emptyList(),
    @SerialName("collaborator_states") val collaboratorStates: List<RCollabState> = emptyList(),
    @SerialName("sync_status") val syncStatus: Map<String, JsonElement> = emptyMap(),
    @SerialName("temp_id_mapping") val tempIdMapping: Map<String, String> = emptyMap(),
)

@Serializable
data class RItem(
    val id: String,
    @SerialName("project_id") val projectId: String,
    @SerialName("section_id") val sectionId: String? = null,
    @SerialName("parent_id") val parentId: String? = null,
    val content: String = "",
    val description: String = "",
    val checked: Boolean = false,
    @SerialName("is_deleted") val isDeleted: Boolean = false,
    @SerialName("child_order") val childOrder: Int = 0,
    @SerialName("responsible_uid") val responsibleUid: String? = null,
    val due: RDue? = null,
)

@Serializable
data class RDue(
    val date: String,
    val string: String? = null,
    @SerialName("is_recurring") val isRecurring: Boolean = false,
)

@Serializable
data class RSection(
    val id: String,
    @SerialName("project_id") val projectId: String,
    val name: String,
    @SerialName("section_order") val sectionOrder: Int = 0,
    @SerialName("is_deleted") val isDeleted: Boolean = false,
    @SerialName("is_archived") val isArchived: Boolean = false,
)

@Serializable
data class RProject(
    val id: String,
    val name: String,
    @SerialName("is_deleted") val isDeleted: Boolean = false,
    @SerialName("is_archived") val isArchived: Boolean = false,
)

@Serializable
data class RCollaborator(val id: String, @SerialName("full_name") val fullName: String = "")

@Serializable
data class RCollabState(
    @SerialName("project_id") val projectId: String,
    @SerialName("user_id") val userId: String,
    val state: String = "active",
)

@Serializable
data class RUser(val id: String, @SerialName("full_name") val fullName: String = "")

/** One sync command. [uuid] stays the same across retries, so Todoist applies it once. */
@Serializable
data class Command(
    val type: String,
    val uuid: String,
    @SerialName("temp_id") val tempId: String? = null,
    val args: JsonObject,
)

sealed interface CommandResult {
    data object Ok : CommandResult
    data class Failed(val code: Int, val message: String) : CommandResult
}

fun SyncResponse.result(uuid: String): CommandResult? {
    val s = syncStatus[uuid] ?: return null
    if (s is JsonPrimitive && s.content == "ok") return CommandResult.Ok
    val o = (s as? JsonObject) ?: return CommandResult.Failed(0, s.toString())
    return CommandResult.Failed(o["error_code"]?.jsonPrimitive?.intOrNull ?: 0, o["error"]?.jsonPrimitive?.content ?: "Failed")
}

fun parseSync(body: String): SyncResponse = TodoistJson.decodeFromString(SyncResponse.serializer(), body)
fun encodeCommands(commands: List<Command>): String = TodoistJson.encodeToString(commands)
fun encodeCommand(c: Command): String = TodoistJson.encodeToString(Command.serializer(), c)
fun decodeCommand(s: String): Command = TodoistJson.decodeFromString(Command.serializer(), s)

private fun newId() = UUID.randomUUID().toString()

fun addItem(content: String, projectId: String, sectionId: String?, responsibleUid: String?, description: String = "") = Command(
    "item_add", newId(), newId(),
    buildJsonObject {
        put("content", content); put("project_id", projectId)
        if (sectionId != null) put("section_id", sectionId)
        if (responsibleUid != null) put("responsible_uid", responsibleUid)
        if (description.isNotEmpty()) put("description", description)
    })

fun closeItem(id: String) = Command("item_close", newId(), null, buildJsonObject { put("id", id) })
fun uncompleteItem(id: String) = Command("item_uncomplete", newId(), null, buildJsonObject { put("id", id) })

/** The only edit Nook makes to an item: its description. */
fun updateDescription(id: String, text: String) = Command("item_update", newId(), null, buildJsonObject { put("id", id); put("description", text) })

fun moveItem(id: String, sectionId: String?, projectId: String) = Command(
    "item_move", newId(), null,
    buildJsonObject { put("id", id); if (sectionId != null) put("section_id", sectionId) else put("project_id", projectId) })

fun addSection(name: String, projectId: String) = Command(
    "section_add", newId(), newId(), buildJsonObject { put("name", name); put("project_id", projectId) })

/** Returns a copy of the command with temp ids in its args replaced by real ids. */
fun Command.mapIds(mapping: Map<String, String>): Command {
    if (mapping.isEmpty()) return this
    val a = JsonObject(args.mapValues { (_, v) ->
        if (v is JsonPrimitive && v.isString && v.content in mapping) JsonPrimitive(mapping.getValue(v.content)) else v
    })
    return copy(args = a)
}

fun Command.arg(name: String): String? = args[name]?.jsonPrimitive?.content
