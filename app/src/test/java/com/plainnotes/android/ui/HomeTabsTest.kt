package com.plainnotes.android.ui

import org.junit.Assert.*
import org.junit.Test

class HomeTabsTest {
    @Test fun workHasOnlyNotesAndTasksRegardlessOfReaderPreference() {
        assertEquals(listOf(HomePage.NOTES, HomePage.TODOS), homePages(true, true))
        assertEquals(homePages(true, true), homePages(false, true))
        assertTrue(homePages(true).contains(HomePage.JOURNAL))
        assertFalse(homePages(false).contains(HomePage.EBOOKS))
    }
}
