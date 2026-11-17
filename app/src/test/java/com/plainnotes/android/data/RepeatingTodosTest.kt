package com.plainnotes.android.data

import java.time.OffsetDateTime
import org.junit.Assert.*
import org.junit.Test

class RepeatingTodosTest {
    private val task = TodoItem(id = "12345678-1234-1234-1234-123456789abc", title = "Read",
        description = "Two chapters\nTake notes", flags = setOf(TodoFlag.IMPORTANT), repeatOnComplete = true)
    private val now = OffsetDateTime.parse("2026-11-17T08:00:00-05:00")

    @Test fun completionKeepsHistoryAndCreatesExactlyOneFreshCopy() {
        val completed = RepeatingTodos.complete(listOf(task), task.id, now)
        assertEquals(2, completed.size)
        assertEquals(now, completed.first().completedAt)
        assertFalse(completed.first().repeatOnComplete)
        val copy = completed.last()
        assertEquals("ea26d56b-8002-8797-9aa3-ec35e7076607", copy.id) // Shared web/phone fixture.
        assertEquals(task.copy(id = copy.id, addedAt = now), copy)
        assertEquals(completed, RepeatingTodos.complete(completed, task.id, now.plusSeconds(1)))
        assertEquals(completed, TodoFileParser.parse(TodoFileParser.serialize(completed)))
        val next = RepeatingTodos.complete(completed, copy.id, now.plusDays(1))
        assertEquals(3, next.size)
        assertEquals(1, next.count { it.completedAt == null })
    }

    @Test fun stoppingAndReopeningHistoryNeverSpawnsAnExtraCopy() {
        val pair = RepeatingTodos.complete(listOf(task), task.id, now)
        val live = pair.last()
        val disabled = pair.map { if (it.id == live.id) it.copy(repeatOnComplete = false) else it }
        assertEquals(2, RepeatingTodos.complete(disabled, live.id, now.plusSeconds(1)).size)
        val reopened = pair.map { if (it.id == task.id) it.copy(completedAt = null) else it }
        assertEquals(2, RepeatingTodos.complete(reopened, task.id, now.plusSeconds(2)).size)
        assertEquals(2, RepeatingTodos.complete(listOf(task, live), task.id, now).size)
    }

    @Test fun ordinaryFilesRemainFormatTwoAndRepeatFilesAreStrictFormatThree() {
        val ordinary = TodoFileParser.serialize(listOf(task.copy(repeatOnComplete = false)))
        assertTrue(ordinary.startsWith("Jnotes To-do: 2\n"))
        assertFalse(TodoFileParser.parse(ordinary).single().repeatOnComplete)
        val repeating = TodoFileParser.serialize(listOf(task))
        assertTrue(repeating.startsWith("Jnotes To-do: 3\n"))
        assertThrows(IllegalArgumentException::class.java) { TodoFileParser.parse(repeating.replace("repeat: true", "repeat: yes")) }
    }
}
