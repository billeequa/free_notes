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
        addedAt = OffsetDateTime.parse("2023-01-01T12:00:00-05:00"),
        completedAt = OffsetDateTime.parse(date), flagged = true,
        flags = TodoFlag.entries.toSet())

    @Test fun yearsThenMonthsAreOldestFirstWithNoCurrentYearFolder() {
        val items = listOf(done("2026-10-09T12:00:00-04:00"), done("2025-07-01T12:00:00-04:00"),
            done("2026-09-01T12:00:00-04:00"), done("2024-07-01T12:00:00-04:00"),
            done("2026-01-01T12:00:00-05:00"))
        val archive = todoArchive(items, today, zone)
        assertEquals(listOf("year:2024", "year:2025", "month:2026-01", "month:2026-09", "month:2026-10"), archive.folders.map { it.id })
        assertTrue(archive.folders.all { it.title.startsWith("✅ ") })
        assertEquals("✅ October (this month)", archive.folders.last().title)
        assertEquals(items.toSet(), archive.folders.flatMap { it.items }.toSet())
    }

    @Test fun recentCardsFollowFoldersAndActiveTasksKeepTheirOrder() {
        val old = done("2025-10-01T12:00:00-04:00")
        val recent = done("2026-10-03T00:00:00-04:00")
        val older = done("2026-10-02T23:59:59-04:00")
        val current = done("2026-10-09T23:59:59-04:00")
        val open = listOf(TodoItem(title = "First"), TodoItem(title = "Second"))
        val original = listOf(old, current, older, recent) + open
        val serialized = TodoFileParser.serialize(original)
        val archive = todoArchive(original, today, zone)
        assertEquals(listOf(recent, current), archive.recent)
        val rows = groupedTodoRows(open, archive, emptyList())
        assertTrue(rows.take(2).all { it is TodoListRow.Folder })
        assertEquals(listOf(recent, current) + open, rows.filterIsInstance<TodoListRow.Task>().map { it.item })
        assertEquals(5, todoOpenListIndex(rows))
        assertSame(recent, archive.recent.first())
        assertSame(recent, archive.folders.last().items[1])
        assertEquals(serialized, TodoFileParser.serialize(original))
    }

    @Test fun monthBoundaryRetainsInlineWeekAcrossBothMonths() {
        val september = done("2026-09-29T12:00:00-04:00")
        val october = done("2026-10-01T12:00:00-04:00")
        val archive = todoArchive(listOf(october, september), LocalDate.of(2026, 10, 2), zone)
        assertEquals(listOf("month:2026-09", "month:2026-10"), archive.folders.map { it.id })
        assertEquals(listOf(september, october), archive.recent)
        assertEquals(listOf(september), archive.folders[0].items)
        assertEquals(listOf(october), archive.folders[1].items)
    }

    @Test fun yearBoundaryDuplicatesRecentIntoItsYearOrMonthAndKeepsFutureMetadata() {
        val previousYear = done("2025-12-31T23:00:00-05:00")
        val january = done("2026-01-01T01:00:00-05:00")
        val rollover = todoArchive(listOf(previousYear, january), LocalDate.of(2026, 1, 2), zone)
        assertEquals(listOf("year:2025", "month:2026-01"), rollover.folders.map { it.id })
        assertEquals(listOf(previousYear, january), rollover.recent)
        val future = done("2026-10-12T12:00:00-04:00")
        val archive = todoArchive(listOf(future), today, zone)
        assertEquals(listOf(future), archive.folders.single().items)
        assertTrue(archive.recent.isEmpty())
    }

    @Test fun completionDateUsesDeviceZoneRatherThanStoredOffsetOrCreationDate() {
        val item = done("2026-10-01T01:00:00Z")
        assertEquals("month:2026-09", todoArchive(listOf(item), today, zone).folders.single().id)
        assertEquals("month:2026-10", todoArchive(listOf(item), today, ZoneId.of("UTC")).folders.single().id)
    }

    @Test fun springDstWeekIsSevenLocalCalendarDaysAndRollsForward() {
        val edge = done("2026-03-02T00:00:00-05:00")
        val spring = done("2026-03-08T03:00:00-04:00")
        assertEquals(listOf(edge, spring), todoArchive(listOf(edge, spring), LocalDate.of(2026, 3, 8), zone).recent)
        val nextDay = todoArchive(listOf(edge, spring), LocalDate.of(2026, 3, 9), zone)
        assertEquals(listOf(spring), nextDay.recent)
        assertEquals(listOf(edge, spring), nextDay.folders.single().items)
    }

    @Test fun expandedContainersKeepDistinctKeysAndMarkOnlyTheirFinalChild() {
        val older = done("2026-10-07T12:00:00-04:00")
        val newer = done("2026-10-08T12:00:00-04:00")
        val archive = todoArchive(listOf(newer, older), today, zone)
        val rows = groupedTodoRows(emptyList(), archive, archive.folders.map { it.id })
        assertEquals(rows.size, rows.map { it.key }.distinct().size)
        assertEquals(4, rows.filterIsInstance<TodoListRow.Task>().size)
        val children = rows.filterIsInstance<TodoListRow.Task>().filter { it.folderId != null }
        assertEquals(listOf(older, newer), children.map { it.item })
        assertEquals(listOf(false, true), children.map { it.lastInFolder })
        assertTrue(rows.takeLast(2).filterIsInstance<TodoListRow.Task>().all { it.folderId == null && !it.lastInFolder })
        val edited = newer.copy(title = "Edited", flags = setOf(TodoFlag.IMPORTANT))
        val updated = todoArchive(listOf(edited), today, zone)
        assertEquals(edited, updated.recent.single())
        assertEquals(edited, updated.folders.single().items.single())
        val reopened = edited.copy(completedAt = null)
        val reopenedArchive = todoArchive(listOf(reopened), today, zone)
        assertTrue(reopenedArchive.folders.isEmpty())
        assertTrue(reopenedArchive.recent.isEmpty())
        assertEquals(listOf(TodoListRow.Task(reopened)), groupedTodoRows(listOf(reopened), reopenedArchive, emptyList()))
    }

    @Test fun initialPositionIncludesExpandedHistoryAndHandlesEmptyLists() {
        val open = TodoItem(title = "Active")
        val archive = todoArchive(listOf(done("2026-10-08T12:00:00-04:00")), today, zone)
        val closedRows = groupedTodoRows(listOf(open), archive, emptyList())
        val expandedRows = groupedTodoRows(listOf(open), archive, archive.folders.map { it.id })
        assertEquals(3, todoOpenListIndex(closedRows))
        assertEquals(4, todoOpenListIndex(expandedRows))
        val noOpenRows = groupedTodoRows(emptyList(), archive, emptyList())
        assertEquals(noOpenRows.size + 1, todoOpenListIndex(noOpenRows))
        assertEquals(0, todoOpenListIndex(emptyList()))
        assertEquals(1, todoOpenListIndex(listOf(TodoListRow.Task(open))))
    }

    @Test fun collapsedOldHistoryDoesNotEmitTaskRows() {
        val open = TodoItem(title = "Still active", flags = setOf(TodoFlag.LONG_TERM))
        val history = (1..1000).map { done("2025-01-01T12:00:00-05:00").copy(title = "Task $it") }
        val archive = todoArchive(history + open, today, zone)
        val rows = groupedTodoRows(listOf(open), archive, emptyList())
        assertEquals(2, rows.size)
        assertEquals(TodoListRow.Task(open), rows.last())
        assertEquals(1000, archive.folders.single().items.size)
    }
}
