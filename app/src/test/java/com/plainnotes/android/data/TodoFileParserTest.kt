package com.plainnotes.android.data

import org.junit.Assert.*
import org.junit.Test
import java.time.OffsetDateTime

class TodoFileParserTest {
    @Test fun roundTripPreservesHistoryAndMultilineText() {
        val items = listOf(
            TodoItem(title = "Calibrate cavity — α", description = "Line one\nadded: literal text\nC:\\notes\r\n", flagged = true,
                addedAt = OffsetDateTime.parse("2026-09-15T12:10:00-04:00"),
                completedAt = OffsetDateTime.parse("2026-09-16T13:20:00-04:00")),
            TodoItem(title = "Next task"),
        )
        assertEquals(items, TodoFileParser.parse(TodoFileParser.serialize(items)))
        assertEquals(items, TodoFileParser.parse(TodoFileParser.serialize(items).replace("\n", "\r\n")))
    }
    @Test fun emptyListRoundTrips() {
        assertEquals(emptyList<TodoItem>(), TodoFileParser.parse(TodoFileParser.serialize(emptyList())))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsTruncatedRecord() {
        TodoFileParser.parse("Jnotes To-do: 1\n\nid: missing-fields\n")
    }
    @Test(expected = IllegalArgumentException::class) fun refusesUnknownFormat() {
        TodoFileParser.parse("Jnotes To-do: 2\n")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsDuplicateIds() {
        val item = TodoItem(title = "Duplicate")
        TodoFileParser.parse(TodoFileParser.serialize(listOf(item, item)))
    }
    @Test fun largeListRoundTrips() {
        val items = (1..10000).map { TodoItem(title = "Task $it") }
        assertEquals(items, TodoFileParser.parse(TodoFileParser.serialize(items)))
    }
}
