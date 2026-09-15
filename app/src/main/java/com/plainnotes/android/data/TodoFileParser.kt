package com.plainnotes.android.data

import java.time.OffsetDateTime
import java.util.UUID

data class TodoItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String = "",
    val addedAt: OffsetDateTime = OffsetDateTime.now(),
    val completedAt: OffsetDateTime? = null,
    val flagged: Boolean = false,
)

/** Versioned, readable UTF-8. Escaping keeps multiline descriptions inside one record. */
object TodoFileParser {
    const val FILE_NAME = "to_do_jnotes.txt"
    private const val HEADER = "Jnotes To-do: 1"

    fun serialize(items: List<TodoItem>): String = buildString {
        appendLine(HEADER)
        items.forEach { item ->
            appendLine()
            appendLine("id: ${item.id}")
            appendLine("title: ${escape(item.title)}")
            appendLine("description: ${escape(item.description)}")
            appendLine("added: ${item.addedAt}")
            appendLine("completed: ${item.completedAt ?: ""}")
            appendLine("flagged: ${item.flagged}")
        }
    }

    fun parse(text: String): List<TodoItem> {
        val lines = text.replace("\r\n", "\n").trimEnd('\n').split('\n')
        require(lines.firstOrNull() == HEADER) { "Unrecognized to-do file; the original has not been changed." }
        val records = lines.drop(1).filter { it.isNotEmpty() }
        require(records.size % 6 == 0) { "Incomplete to-do record; the original has not been changed." }
        val items = records.chunked(6).map { record ->
            fun field(index: Int, key: String): String {
                require(record[index].startsWith("$key: ")) { "Invalid to-do field: $key" }
                return record[index].removePrefix("$key: ")
            }
            TodoItem(
                id = field(0, "id").also { UUID.fromString(it) },
                title = unescape(field(1, "title")),
                description = unescape(field(2, "description")),
                addedAt = OffsetDateTime.parse(field(3, "added")),
                completedAt = field(4, "completed").takeIf { it.isNotEmpty() }?.let(OffsetDateTime::parse),
                flagged = field(5, "flagged").toBooleanStrict(),
            )
        }
        require(items.map { it.id }.distinct().size == items.size) { "Duplicate to-do IDs." }
        return items
    }

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

    private fun unescape(value: String): String = buildString {
        var i = 0
        while (i < value.length) {
            val char = value[i++]
            if (char != '\\') append(char) else {
                require(i < value.length) { "Incomplete escape in to-do text." }
                append(when (value[i++]) {
                    'n' -> '\n'
                    'r' -> '\r'
                    '\\' -> '\\'
                    else -> error("Unknown escape in to-do text.")
                })
            }
        }
    }
}
