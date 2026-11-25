package lt.nook.app.core

import java.time.DateTimeException
import java.time.LocalDate

// Achievements: finished ones are in a section named "Done" (not completed, so they never expire) and the last line of
// their description says when, exactly "Done: 25 Nov 2026". The kiosk uses the same format.

/** Todoist allows 16,383 characters; the rest is room for the date line. */
const val MAX_DESCRIPTION = 16_000
const val DONE_SECTION = "Done"

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val LINE = Regex("""Done: (\d{1,2}) (${MONTHS.joinToString("|")}) (\d{4})""")

fun dateText(day: LocalDate) = "${day.dayOfMonth} ${MONTHS[day.monthValue - 1]} ${day.year}"

/** The text a person wrote, and the day it was finished (if the last line says so). */
fun splitDescription(text: String): Pair<String, LocalDate?> {
    val whole = text.trimEnd()
    val m = LINE.matchEntire(whole.substringAfterLast('\n').trim()) ?: return whole to null
    val (d, mon, y) = m.destructured
    val day = try { LocalDate.of(y.toInt(), MONTHS.indexOf(mon) + 1, d.toInt()) } catch (e: DateTimeException) { return whole to null }
    return whole.substringBeforeLast('\n', "").trimEnd() to day
}

/** The description to store: the person's text, then the date line (an old one is replaced, never doubled). */
fun joinDescription(text: String, day: LocalDate?): String {
    val own = splitDescription(text).first
    if (day == null) return own
    val line = "Done: ${dateText(day)}"
    return if (own.isEmpty()) line else "$own\n$line"
}

fun isDoneSection(name: String) = name.trim().equals(DONE_SECTION, ignoreCase = true)

/** The description without the date line. */
val ItemV.text: String get() = splitDescription(description).first

data class DoneRow(val item: ItemV, val depth: Int, val date: LocalDate?)
data class AchievementLists(val open: List<Group>, val done: List<DoneRow>)

/** Open ones grouped like any list; finished ones newest first (undated last), sub-items right after their parent. */
fun achievementLists(items: List<ItemV>, sections: List<SectionV>): AchievementLists {
    val doneIds = sections.filter { isDoneSection(it.name) }.mapTo(HashSet()) { it.id }
    val byId = items.associateBy { it.id }
    fun root(i: ItemV): ItemV {
        var r = i
        val seen = HashSet<String>()
        while (seen.add(r.id)) r = byId[r.parentId ?: break] ?: break
        return r
    }
    val (finished, open) = items.partition { root(it).sectionId in doneIds }
    val children = finished.filter { it.parentId in byId }.groupBy { it.parentId }
    fun rows(i: ItemV, depth: Int): List<DoneRow> =
        listOf(DoneRow(i, depth, splitDescription(i.description).second)) +
            children[i.id].orEmpty().sortedBy { it.order }.flatMap { rows(it, depth + 1) }
    val done = finished.filter { it.parentId !in byId }
        .sortedWith(compareByDescending<ItemV> { splitDescription(it.description).second ?: LocalDate.MIN }.thenBy { it.order })
        .flatMap { rows(it, 0) }
    return AchievementLists(group(open, sections.filter { it.id !in doneIds }), done)
}
