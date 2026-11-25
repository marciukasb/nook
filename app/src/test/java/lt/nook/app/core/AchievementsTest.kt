package lt.nook.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AchievementsTest {
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    // The kiosk uses the same format; its tests have the same cases.
    @Test fun splitsTheDateLine() {
        listOf(
            "Foundation first\nDone: 25 Nov 2026" to ("Foundation first" to d(2026, 11, 25)),
            "Done: 5 Jan 2027" to ("" to d(2027, 1, 5)),
            "Done: soon" to ("Done: soon" to null),
            "Done: 31 Feb 2026" to ("Done: 31 Feb 2026" to null),
            "Done: 25 Nov 2026\nmore" to ("Done: 25 Nov 2026\nmore" to null),
            "two\n\nparagraphs\nDone: 1 May 2026\n" to ("two\n\nparagraphs" to d(2026, 5, 1)),
            "" to ("" to null),
        ).forEach { (text, want) -> assertEquals(text, want, splitDescription(text)) }
    }

    @Test fun joinsReplacingAnOldDate() {
        assertEquals("Foundation first\nDone: 5 Nov 2026", joinDescription("Foundation first", d(2026, 11, 5)))
        assertEquals("a\nDone: 5 Nov 2026", joinDescription("a\nDone: 1 Nov 2026", d(2026, 11, 5)))
        assertEquals("a", joinDescription("a\nDone: 1 Nov 2026", null))
        assertEquals("Done: 5 Nov 2026", joinDescription("", d(2026, 11, 5)))
        assertEquals("", joinDescription("", null))
    }

    @Test fun doneSectionIgnoresCaseAndSpaces() {
        listOf("Done", " done ", "DONE").forEach { assertTrue(it, isDoneSection(it)) }
        listOf("Done soon", "").forEach { assertFalse(it, isDoneSection(it)) }
    }

    private fun i(id: String, sec: String?, order: Int, parent: String? = null, desc: String = "") =
        ItemV(id, id, sec, parent, order, null, null, false, desc)
    private val done = SectionV("d1", "done", 2)
    private val home = SectionV("h", "Home", 1)
    private fun shape(l: AchievementLists) =
        l.open.map { g -> g.title to g.rows.map { it.item.id } } to l.done.map { Triple(it.item.id, it.depth, it.date) }

    @Test fun openIsGroupedWithoutDone_doneIsNewestFirstUndatedLast() = assertEquals(
        listOf("Home" to listOf("roof"), "Other" to listOf("weld")) to listOf(
            Triple("photos", 0, d(2026, 11, 20)), Triple("paint", 0, d(2026, 11, 5)), Triple("byhand", 0, null)),
        shape(achievementLists(listOf(
            i("weld", null, 1), i("roof", "h", 2), i("byhand", "d1", 3), i("paint", "d1", 4, desc = "x\nDone: 5 Nov 2026"),
            i("photos", "d1", 5, desc = "Done: 20 Nov 2026")), listOf(done, home))))

    @Test fun subItemsFollowTheirParent() = assertEquals(
        listOf("" to listOf("weld", "course")) to listOf(Triple("paint", 0, null), Triple("brush", 1, null)),
        shape(achievementLists(listOf(i("weld", null, 1), i("course", null, 1, "weld"), i("paint", "d1", 2), i("brush", "d1", 1, "paint")),
            listOf(done))))

    @Test fun severalDoneSectionsAllCount() = assertEquals(
        listOf("a", "b"), achievementLists(listOf(i("a", "d1", 1), i("b", "d2", 2)), listOf(done, SectionV("d2", "Done", 9))).done.map { it.item.id })

    @Test fun nothing() = assertEquals(AchievementLists(emptyList(), emptyList()), achievementLists(emptyList(), listOf(done)))

    @Test fun textShownNeverHasTheDateLine() {
        assertEquals("x", i("a", null, 1, desc = "x\nDone: 5 Nov 2026").text)
        assertEquals("", i("a", null, 1).text)
    }
}
