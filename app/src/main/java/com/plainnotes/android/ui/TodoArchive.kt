package com.plainnotes.android.ui

import com.plainnotes.android.data.TodoItem
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class TodoArchiveFolder(val id: String, val title: String, val items: List<TodoItem>)
data class TodoArchive(val folders: List<TodoArchiveFolder>, val recent: List<TodoItem>)

/** Overlapping virtual folders reference the original tasks; no records are copied or moved. */
fun todoArchive(
    items: List<TodoItem>,
    today: LocalDate,
    zone: ZoneId,
): TodoArchive {
    val weekStart = today.minusDays(6)
    val month = YearMonth.from(today)
    val completed = items.filter { it.completedAt != null }.sortedBy { it.completedAt!!.toInstant() }
    val dated = completed.map { it to it.completedAt!!.atZoneSameInstant(zone).toLocalDate() }
    val grouped = dated.groupBy { (_, date) ->
        if (date.year == today.year) "month:${YearMonth.from(date)}" else "year:${date.year}"
    }
    val keys = grouped.keys.filter { it.startsWith("year:") }.sorted() +
        grouped.keys.filter { it.startsWith("month:") }.sorted()
    val folders = keys.map { key ->
        val title = if (key.startsWith("year:")) key.removePrefix("year:") else {
            val folderMonth = YearMonth.parse(key.removePrefix("month:"))
            folderMonth.format(DateTimeFormatter.ofPattern("MMMM")) +
                if (folderMonth == month) " (this month)" else ""
        }
        TodoArchiveFolder(key, "✅ $title", grouped.getValue(key).map { it.first })
    }
    val recent = dated.filter { (_, date) -> date in weekStart..today }.map { it.first }
    return TodoArchive(folders, recent)
}

internal sealed interface TodoListRow {
    val key: String
    data class Task(val item: TodoItem, val folderId: String? = null, val lastInFolder: Boolean = false) : TodoListRow {
        override val key = "task:${folderId ?: "list"}:${item.id}"
    }
    data class Folder(val folder: TodoArchiveFolder) : TodoListRow { override val key = "folder:${folder.id}" }
}

internal fun groupedTodoRows(
    open: List<TodoItem>,
    archive: TodoArchive,
    expanded: List<String>,
): List<TodoListRow> = buildList {
    archive.folders.forEach { folder ->
        add(TodoListRow.Folder(folder))
        if (folder.id in expanded) folder.items.forEachIndexed { index, item ->
            add(TodoListRow.Task(item, folder.id, lastInFolder = index == folder.items.lastIndex))
        }
    }
    archive.recent.forEach { add(TodoListRow.Task(it)) }
    open.forEach { add(TodoListRow.Task(it)) }
}

/** LazyColumn has one View header before the rows and a no-open message after history. */
internal fun todoOpenListIndex(rows: List<TodoListRow>): Int {
    val openIndex = rows.indexOfFirst { it is TodoListRow.Task && it.item.completedAt == null && it.folderId == null }
    return when {
        openIndex >= 0 -> openIndex + 1
        rows.isNotEmpty() -> rows.size + 1
        else -> 0
    }
}
