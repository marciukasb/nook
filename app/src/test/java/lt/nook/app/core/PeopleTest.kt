package lt.nook.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PeopleTest {
    @Test fun firstNamesOnly() = assertEquals(
        mapOf("u1" to "Alex", "u2" to "Sam"),
        shortNames(mapOf("u1" to "Alex Example", "u2" to "Sam  Other-Name")))

    @Test fun sameFirstName_keepsFullNames() = assertEquals(
        mapOf("u1" to "Alex Example", "u2" to "Alex Other", "u3" to "Sam"),
        shortNames(mapOf("u1" to "Alex Example", "u2" to "Alex Other", "u3" to "Sam Third")))

    @Test fun singleWordOrBlank() = assertEquals(
        mapOf("u1" to "Alex", "u2" to "?"),
        shortNames(mapOf("u1" to " Alex ", "u2" to "  ")))
}
