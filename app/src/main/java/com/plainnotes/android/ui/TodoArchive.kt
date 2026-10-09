package com.plainnotes.android.ui

import com.plainnotes.android.data.TodoItem
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class TodoArchiveFolder(val id: String, val title: String, val items: List<TodoItem>)

/** Overlapping virtual folders reference the original tasks; no records are copied or moved. */
fun todoArchiveFolders(
    items: List<TodoItem>,
    today: LocalDate,
    zone: ZoneId,
): List<TodoArchiveFolder> {
    val weekStart = today.minusDays(6)
    val month = YearMonth.from(today)
    val completed = items.filter { it.completedAt != null }.sortedBy { it.completedAt!!.toInstant() }
    val dated = completed.map { it to it.completedAt!!.atZoneSameInstant(zone).toLocalDate() }
    val grouped = dated.groupBy { (_, date) ->
            when {
                date > today -> "later"
                YearMonth.from(date) == month -> "month"
                date.year == today.year -> "month:${YearMonth.from(date)}"
                else -> "years"
            }
        }.mapValues { (_, tasks) -> tasks.map { it.first } }.toMutableMap()
    val week = dated.filter { (_, date) -> date in weekStart..today }.map { it.first }
    if (week.isNotEmpty()) grouped["week"] = week
    val keys = listOf("week", "month") +
        grouped.keys.filter { it.startsWith("month:") }.sortedDescending() +
        listOf("years", "later")
    return keys.mapNotNull { key ->
        grouped[key]?.let { tasks ->
            val title = when (key) {
                "week" -> "Last week"
                "month" -> "This month"
                "years" -> "Previous years"
                "later" -> "Later dates"
                else -> YearMonth.parse(key.removePrefix("month:"))
                    .format(DateTimeFormatter.ofPattern("MMMM yyyy"))
            }
            TodoArchiveFolder(key, title, tasks)
        }
    }
}

internal sealed interface TodoListRow {
    val key: String
    data class Task(val item: TodoItem, val folderId: String? = null) : TodoListRow {
        override val key = "task:${folderId ?: "list"}:${item.id}"
    }
    data class Folder(val folder: TodoArchiveFolder) : TodoListRow { override val key = "folder:${folder.id}" }
    data object CompletedHeader : TodoListRow { override val key = "completed-header" }
}

internal fun groupedTodoRows(
    open: List<TodoItem>,
    folders: List<TodoArchiveFolder>,
    expanded: List<String>,
): List<TodoListRow> = buildList {
    open.forEach { add(TodoListRow.Task(it)) }
    if (folders.isNotEmpty()) add(TodoListRow.CompletedHeader)
    folders.forEach { folder ->
        add(TodoListRow.Folder(folder))
        if (folder.id in expanded) folder.items.forEach { add(TodoListRow.Task(it, folder.id)) }
    }
}
