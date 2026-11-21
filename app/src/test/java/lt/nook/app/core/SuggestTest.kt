package lt.nook.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SuggestTest {
    private fun k(name: String, uses: Int = 1, last: Long = 0, section: String? = null) = Known(name, section, uses, last)

    @Test fun diacriticAndCaseInsensitive() =
        assertEquals(listOf("Pienas"), suggest("PIEN", listOf(k("Pienas"), k("Duona"))).map { it.name })

    @Test fun matchesTypedDiacritics() =
        assertEquals(listOf("Šokoladas"), suggest("šok", listOf(k("Šokoladas"))).map { it.name })

    @Test fun startBeatsWordStartBeatsContains() =
        assertEquals(listOf("Milk", "Oat milk", "Cumin"),
            suggest("mi", listOf(k("Cumin", uses = 9), k("Oat milk", uses = 5), k("Milk"))).map { it.name })

    @Test fun moreUsesThenNewerWithinSameClass() =
        assertEquals(listOf("Mango", "Milk", "Mint"),
            suggest("m", listOf(k("Mint", uses = 2, last = 1), k("Milk", uses = 2, last = 5), k("Mango", uses = 3))).map { it.name })

    @Test fun blankQueryGivesNothing() = assertEquals(emptyList<Known>(), suggest("  ", listOf(k("Milk"))))

    @Test fun limited() = assertEquals(2, suggest("a", (1..5).map { k("a$it") }, limit = 2).size)
}
