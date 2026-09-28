package com.plainnotes.android.data

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class DoubleXDayTest {
    @Test fun completionDateUsesLocalInstantNotCreationDate() {
        val zone = ZoneId.of("America/New_York")
        val items = (1..8).map { index ->
            TodoItem(title = "Task $index", addedAt = OffsetDateTime.parse("2026-09-23T10:00:00-04:00"),
                completedAt = OffsetDateTime.parse("2026-09-29T02:00:00Z"))
        }
        assertEquals(8, DoubleXDay.accomplished(items, LocalDate.parse("2026-09-28"), zone).size)
        assertEquals(0, DoubleXDay.accomplished(items, LocalDate.parse("2026-09-29"), zone).size)
        assertEquals(1, DoubleXDay.newlyCompleted(items.mapIndexed { i, item ->
            if (i == 7) item.copy(completedAt = null) else item
        }, items).size)
    }
}
