package lt.nook.app.core

/** An open item as the screens see it. [assignee] is a display name, [due] a Todoist date string. */
data class ItemV(
    val id: String, val content: String, val sectionId: String?, val parentId: String?,
    val order: Int, val assignee: String?, val due: String?, val recurring: Boolean,
    val description: String = "",
)

data class SectionV(val id: String, val name: String, val order: Int)
data class Row(val item: ItemV, val depth: Int)
data class Group(val title: String, val sectionId: String?, val rows: List<Row>)

const val OTHER = "Other"

/**
 * Items grouped under their sections (in section order), sub-items right after their parent with depth + 1.
 * Items without a known section go to "Other", last. Empty sections are left out. A project without sections
 * gives one group with an empty title.
 */
fun group(items: List<ItemV>, sections: List<SectionV>): List<Group> {
    if (items.isEmpty()) return emptyList()
    val ids = items.mapTo(HashSet()) { it.id }
    val children = items.filter { it.parentId != null && it.parentId in ids }.groupBy { it.parentId!! }
    val roots = items.filter { it.parentId == null || it.parentId !in ids }

    fun rows(item: ItemV, depth: Int): List<Row> =
        listOf(Row(item, depth)) + children[item.id].orEmpty().sortedBy { it.order }.flatMap { rows(it, depth + 1) }

    fun rowsOf(list: List<ItemV>) = list.sortedBy { it.order }.flatMap { rows(it, 0) }

    if (sections.isEmpty()) return listOf(Group("", null, rowsOf(roots)))

    val known = sections.associateBy { it.id }
    val bySection = roots.groupBy { r -> r.sectionId?.takeIf { it in known } }
    val groups = sections.sortedBy { it.order }.mapNotNull { s ->
        bySection[s.id]?.let { Group(s.name, s.id, rowsOf(it)) }
    }
    val other = bySection[null]?.let { Group(OTHER, null, rowsOf(it)) }
    return groups + listOfNotNull(other)
}
