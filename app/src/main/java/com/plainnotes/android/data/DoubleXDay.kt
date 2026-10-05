package com.plainnotes.android.data

import com.plainnotes.android.model.NoteTextContent
import com.plainnotes.android.model.NoteType
import java.time.LocalDate
import java.time.ZoneId

object DoubleXDay {
    /**
     * Snapshot the template into real, editable note text exactly once.
     * Persist the marker separately: detecting headings in the body would
     * recreate them after a user edits/deletes them and destroy that choice.
     * Never regenerate the accomplishment list on recomposition or reopening.
     */
    fun withTemplate(content: NoteTextContent, items: List<TodoItem>, zone: ZoneId = ZoneId.systemDefault()): NoteTextContent {
        val date = content.doubleXDate ?: return content
        if (content.noteType != NoteType.DOUBLE_X_DAY || content.doubleXTemplateVersion >= 1) return content
        val completed = accomplished(items, date, zone)
        val template = buildString {
            appendLine("Things Accomplished")
            if (completed.isEmpty()) appendLine("No completed tasks yet.")
            completed.forEach { appendLine("✓ ${it.title}") }
            appendLine()
            appendLine("Notes on the Day")
            appendLine()
            append(content.body) // Preserve existing writing, including blank lines.
        }
        return content.copy(body = template, doubleXTemplateVersion = 1)
    }

    fun accomplished(items: List<TodoItem>, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<TodoItem> =
        items.filter { it.completedAt?.atZoneSameInstant(zone)?.toLocalDate() == date }
            .sortedBy { it.completedAt?.toInstant() }

    fun newlyCompleted(previous: List<TodoItem>, current: List<TodoItem>): List<TodoItem> {
        val earlier = previous.associateBy { it.id }
        return current.filter { it.completedAt != null && earlier[it.id]?.completedAt == null }
    }
}
