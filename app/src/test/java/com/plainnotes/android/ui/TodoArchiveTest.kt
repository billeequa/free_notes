package com.plainnotes.android.ui

import com.plainnotes.android.data.TodoFlag
import com.plainnotes.android.data.TodoFileParser
import com.plainnotes.android.data.TodoItem
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

class TodoArchiveTest {
    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 10, 9)
    private fun done(date: String) = TodoItem(title = date, description = "Keep details",
        addedAt = OffsetDateTime.parse("2024-01-01T12:00:00-05:00"),
        completedAt = OffsetDateTime.parse(date), flagged = true,
        flags = TodoFlag.entries.toSet())

    @Test fun weekOverlapsMonthWithoutCopyingOrChangingTasks() {
        val recent = done("2026-10-03T00:00:00-04:00")
        val older = done("2026-10-02T23:59:59-04:00")
        val current = done("2026-10-09T23:59:59-04:00")
        val open = TodoItem(title = "Open")
        val original = listOf(open, current, older, recent)
        val serialized = TodoFileParser.serialize(original)
        val folders = todoArchiveFolders(original, today, zone).associateBy { it.id }
        assertEquals(setOf("week", "month"), folders.keys)
        assertEquals(listOf(recent, current), folders.getValue("week").items)
        assertEquals(listOf(older, recent, current), folders.getValue("month").items)
        assertSame(recent, folders.getValue("week").items.first())
        assertSame(recent, folders.getValue("month").items[1])
        assertEquals(serialized, TodoFileParser.serialize(original))
    }

    @Test fun monthBoundaryStillShowsLastWeekAcrossBothMonths() {
        val september = done("2026-09-29T12:00:00-04:00")
        val october = done("2026-10-01T12:00:00-04:00")
        val folders = todoArchiveFolders(listOf(october, september), LocalDate.of(2026, 10, 2), zone)
        assertEquals(listOf("week", "month", "month:2026-09"), folders.map { it.id })
        assertEquals(listOf(september, october), folders[0].items)
        assertEquals(listOf(october), folders[1].items)
        assertEquals(listOf(september), folders[2].items)
    }

    @Test fun yearBoundaryAndOlderMonthsKeepAllCompletedRecords() {
        val previousYear = done("2025-12-31T23:00:00-05:00")
        val january = done("2026-01-01T01:00:00-05:00")
        val rollover = todoArchiveFolders(listOf(previousYear, january), LocalDate.of(2026, 1, 2), zone)
        assertEquals(listOf("week", "month", "years"), rollover.map { it.id })
        assertEquals(listOf(previousYear, january), rollover[0].items)
        assertEquals(listOf(previousYear), rollover[2].items)
        val items = listOf(previousYear, january, done("2026-08-10T12:00:00-04:00"),
            done("2026-09-10T12:00:00-04:00"), done("2026-10-12T12:00:00-04:00"))
        val folders = todoArchiveFolders(items, today, zone)
        assertEquals(listOf("month:2026-09", "month:2026-08", "month:2026-01", "years", "later"), folders.map { it.id })
        assertEquals(items.toSet(), folders.flatMap { it.items }.toSet())
    }

    @Test fun completionDateUsesDeviceZoneRatherThanStoredOffsetOrCreationDate() {
        val item = done("2026-10-01T01:00:00Z")
        val folders = todoArchiveFolders(listOf(item), today, zone)
        assertEquals("month:2026-09", folders.single().id)
        val utc = todoArchiveFolders(listOf(item), today, ZoneId.of("UTC"))
        assertEquals("month", utc.single().id)
    }

    @Test fun springDstWeekIsSevenLocalCalendarDaysAndRollsForward() {
        val edge = done("2026-03-02T00:00:00-05:00")
        val spring = done("2026-03-08T03:00:00-04:00")
        val folders = todoArchiveFolders(listOf(edge, spring), LocalDate.of(2026, 3, 8), zone)
        assertEquals(listOf(edge, spring), folders.first().items)
        val nextDay = todoArchiveFolders(listOf(edge, spring), LocalDate.of(2026, 3, 9), zone)
        assertEquals(listOf(spring), nextDay.first().items)
        assertEquals(listOf(edge, spring), nextDay[1].items)
    }

    @Test fun duplicatesHaveUniqueRowKeysAndEditsAndReopeningUpdateEveryFolder() {
        val item = done("2026-10-08T12:00:00-04:00")
        val folders = todoArchiveFolders(listOf(item), today, zone)
        val collapsed = groupedTodoRows(emptyList(), folders, emptyList())
        assertTrue(collapsed.none { it is TodoListRow.Task })
        val rows = groupedTodoRows(emptyList(), folders, folders.map { it.id })
        assertEquals(rows.size, rows.map { it.key }.distinct().size)
        assertEquals(2, rows.filterIsInstance<TodoListRow.Task>().size)
        val edited = item.copy(title = "Edited", flags = setOf(TodoFlag.IMPORTANT))
        assertTrue(todoArchiveFolders(listOf(edited), today, zone).all { it.items.single() == edited })
        val reopened = edited.copy(completedAt = null)
        val reopenedFolders = todoArchiveFolders(listOf(reopened), today, zone)
        assertTrue(reopenedFolders.isEmpty())
        assertEquals(listOf(TodoListRow.Task(reopened)), groupedTodoRows(listOf(reopened), reopenedFolders, folders.map { it.id }))
        assertTrue(todoArchiveFolders(emptyList(), today, zone).isEmpty())
    }

    @Test fun openItemsStayFirstAndCollapsedHistoryDoesNotEmitTaskRows() {
        val open = TodoItem(title = "Still active", flags = setOf(TodoFlag.LONG_TERM))
        val history = (1..1000).map { done("2025-01-01T12:00:00-05:00").copy(title = "Task $it") }
        val folders = todoArchiveFolders(history + open, today, zone)
        val rows = groupedTodoRows(listOf(open), folders, emptyList())
        assertEquals(3, rows.size)
        assertEquals(TodoListRow.Task(open), rows.first())
        assertEquals(1000, folders.single().items.size)
    }
}
