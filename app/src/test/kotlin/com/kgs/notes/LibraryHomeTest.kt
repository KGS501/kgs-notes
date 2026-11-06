package com.kgs.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryHomeTest {
    @Test fun everyDefaultViewSurvivesBeingStored() {
        val homes = LibraryFilter.entries.map { LibraryHome(it) } + LibraryHome(category = "") +
            LibraryHome(category = "Work/Projects: 2026")
        homes.forEach { assertEquals(it, LibraryHome.decode(it.encoded)) }
    }

    @Test fun missingOrUnknownValuesOpenAllNotes() {
        assertEquals(LibraryHome(), LibraryHome.decode(null))
        assertEquals(LibraryHome(), LibraryHome.decode("filter:RETIRED"))
    }
}
