package lt.nook.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupingTest {
    private fun i(id: String, sec: String?, order: Int, parent: String? = null) =
        ItemV(id, id, sec, parent, order, null, null, false)
    private val dairy = SectionV("s1", "Dairy", 2)
    private val veg = SectionV("s2", "Fruit & veg", 1)
    private val empty = SectionV("s3", "Bakery", 3)

    private fun shape(g: List<Group>) = g.map { grp -> grp.title to grp.rows.map { it.item.id to it.depth } }

    @Test fun sectionOrderThenItemOrder_emptyHidden_otherLast() = assertEquals(
        listOf("Fruit & veg" to listOf("banana" to 0),
               "Dairy" to listOf("milk" to 0, "yogurt" to 0),
               "Other" to listOf("batteries" to 0, "ghost" to 0)),
        shape(group(listOf(i("yogurt", "s1", 2), i("batteries", null, 1), i("milk", "s1", 1),
                           i("banana", "s2", 1), i("ghost", "gone", 2)), listOf(dairy, veg, empty))))

    @Test fun childrenFollowParentIndented() = assertEquals(
        listOf("" to listOf("trip" to 0, "tickets" to 1, "print" to 2, "bags" to 1, "other" to 0)),
        shape(group(listOf(i("other", null, 2), i("trip", null, 1), i("bags", null, 2, "trip"),
                           i("tickets", null, 1, "trip"), i("print", null, 1, "tickets")), emptyList())))

    @Test fun orphanChildIsTopLevel() = assertEquals(
        listOf("" to listOf("lonely" to 0)),
        shape(group(listOf(i("lonely", null, 1, parent = "closed")), emptyList())))

    @Test fun noSectionsIsOneUntitledGroup() =
        assertEquals(listOf(""), group(listOf(i("a", null, 1)), emptyList()).map { it.title })

    @Test fun nothingIsNoGroups() = assertEquals(emptyList<Group>(), group(emptyList(), listOf(dairy)))
}
