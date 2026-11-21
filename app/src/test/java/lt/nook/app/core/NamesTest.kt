package lt.nook.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class NamesTest {
    @Test fun trimsAndLowercases() = assertEquals("pienas", nameKey("  Pienas "))
    @Test fun stripsLithuanianDiacritics() {
        assertEquals("saldytos uogos", nameKey("Šaldytos uogos"))
        assertEquals("azuolas", nameKey("Ąžuolas"))
        assertEquals("ciuzeliai eeiuu", nameKey("Čiuželiai ĖĘĮŲŪ"))
    }
    @Test fun caseInsensitive() = assertEquals(nameKey("milk"), nameKey("MILK"))
    @Test fun collapsesWhitespace() = assertEquals("oat milk", nameKey("oat \t  milk"))
}
