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
    @Test fun loadsExistingVersion12FileWithoutChangingTaskIdentityOrHistory() {
        // Fixed fixture from the 1.2 file format, not produced by the current serializer.
        val file = """Jnotes To-do: 1

id: 12345678-1234-1234-1234-123456789abc
title: Existing unfinished task
description: First line\nSecond line
added: 2026-09-15T12:10:00-04:00
completed: 
flagged: true

id: 12345678-1234-1234-1234-123456789abd
title: Existing completed task
description: Saved description
added: 2026-09-14T12:10:00-04:00
completed: 2026-09-16T13:20:00-04:00
flagged: false
"""
        val items = TodoFileParser.parse(file)
        assertEquals("to_do_jnotes.txt", TodoFileParser.FILE_NAME)
        assertEquals(2, items.size)
        assertEquals("12345678-1234-1234-1234-123456789abc", items[0].id)
        assertEquals("First line\nSecond line", items[0].description)
        assertTrue(items[0].flagged)
        assertNull(items[0].completedAt)
        assertEquals(OffsetDateTime.parse("2026-09-16T13:20:00-04:00"), items[1].completedAt)
        assertEquals(items, TodoFileParser.parse(TodoFileParser.serialize(items)))
    }
}

