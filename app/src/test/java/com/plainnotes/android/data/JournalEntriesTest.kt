package com.plainnotes.android.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JournalEntriesTest {
    private val date = LocalDate.parse("2026-10-04")

    @Test fun `new journals advance through Roman numerals and Double X titles`() {
        val titles = mutableListOf<String>()
        for (expected in listOf("2026-10-04", "2026-10-04 II", "2026-10-04 III",
                "2026-10-04 IV", "2026-10-04 V", "2026-10-04 VI", "2026-10-04 VII",
                "2026-10-04 VIII", "2026-10-04 IX", "2026-10-04 X", "2026-10-04 XI")) {
            val next = JournalEntries.nextTitle(date, titles)
            assertEquals(expected, next)
            titles += if (titles.isEmpty()) JournalEntries.doubleXTitle(date, next) else next
        }
    }

    @Test fun `deleting the first entry does not reuse an earlier number`() {
        assertEquals("2026-10-04 IV", JournalEntries.nextTitle(date,
            listOf("2026-10-04 III Double X Day", "2026-10-03 XX", "2026-10-04 IIII")))
    }

    @Test fun `Double X suffix is idempotent and preserves the entry number`() {
        assertEquals("2026-10-04 Double X Day", JournalEntries.doubleXTitle(date, "2026-10-04"))
        assertEquals("2026-10-04 II Double X Day", JournalEntries.doubleXTitle(date, "2026-10-04 II"))
        assertEquals("2026-10-04 II Double X Day", JournalEntries.doubleXTitle(date, "2026-10-04 II Double X Day"))
    }

    @Test fun `moved date titled notes retain the date they describe`() {
        assertEquals(date, JournalEntries.dateFromTitle("2026-10-04 II"))
        assertNull(JournalEntries.dateFromTitle("2026-02-30"))
        assertNull(JournalEntries.dateFromTitle("Grocery list"))
    }
}
