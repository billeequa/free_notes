package com.plainnotes.android.data

import java.time.OffsetDateTime
import org.junit.Assert.*
import org.junit.Test

class TodoFileParserTest {
    @Test fun roundTripPreservesTextAndHistory() {
        val created = OffsetDateTime.parse("2026-09-15T09:03:21-04:00")
        val items = listOf(TodoItem(text = "Call Jack\nBring \\ tools\t✓\r", createdAt = created,
            completedAt = created.plusHours(2), flagged = true), TodoItem(text = "Pending"))
        assertEquals(items, TodoFileParser.parse(TodoFileParser.serialize(items)))
        assertEquals(emptyList<TodoItem>(), TodoFileParser.parse(TodoFileParser.serialize(emptyList())))
    }

    @Test fun supportsLongHistory() {
        val items = (1..10000).map { TodoItem(text = "Task $it") }
        assertEquals(items, TodoFileParser.parse(TodoFileParser.serialize(items)))
    }

    @Test fun rejectsMalformedDataInsteadOfDiscardingItems() {
        val item = TodoItem(text = "Keep me")
        val valid = TodoFileParser.serialize(listOf(item))
        listOf("unrelated text", valid + "broken row\n", valid.replace("Keep me", "bad\\x"),
            TodoFileParser.serialize(listOf(item, item))).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { TodoFileParser.parse(invalid) }
        }
    }
}
