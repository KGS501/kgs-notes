package com.kgs.notes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteSearchTest {
    private fun matches(query: String, vararg fields: String) =
        matchesSearch(searchTerms(query), fields.map(::normalizeForSearch))

    @Test
    fun findsTextBeyondThePreviewLine() {
        val body = "# Shopping\n\nMilk\nEggs\n\n## Later\n\nA paragraph far below the preview."
        assertTrue(matches("Eggs", "Shopping", "", body))
        assertTrue(matches("far below", "Shopping", "", body))
    }

    @Test
    fun isCaseAccentAndCompatibilityInsensitive() {
        assertTrue(matches("CAFE", "Café au lait"))
        assertTrue(matches("muller", "Müller"))
        assertTrue(matches("ﬁle", "File list"))
    }

    @Test
    fun everyTermMustMatchSomeField() {
        assertTrue(matches("trip forest", "Trip notes", "Trips/Forest", ""))
        assertFalse(matches("trip desert", "Trip notes", "Trips/Forest", "Snow"))
        assertTrue(searchTerms("   ").isEmpty())
    }
}
