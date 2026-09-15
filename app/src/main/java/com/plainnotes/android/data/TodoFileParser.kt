package com.plainnotes.android.data

import java.time.OffsetDateTime
import java.util.UUID

data class TodoItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    val completedAt: OffsetDateTime? = null,
    val flagged: Boolean = false,
)

/** UTF-8 TSV, one physical line per item; escaping preserves arbitrary item text. */
object TodoFileParser {
    const val FILE_NAME = "to_do_jnotes.txt"
    private const val HEADER = "# Jnotes todo v1\tid\tcreated\tcompleted\tflagged\ttext"

    fun serialize(items: List<TodoItem>): String = buildString {
        appendLine(HEADER)
        items.forEach {
            require(it.text.isNotBlank()) { "An item cannot be blank." }
            UUID.fromString(it.id)
            appendLine(listOf(it.id, it.createdAt.toString(), it.completedAt?.toString() ?: "-",
                if (it.flagged) "1" else "0", escape(it.text)).joinToString("\t"))
        }
    }

    fun parse(raw: String): List<TodoItem> {
        val lines = raw.removePrefix("\uFEFF").lineSequence().toList()
        require(lines.firstOrNull() == HEADER) { "Unrecognized to-do file. The original file has not been changed." }
        val ids = mutableSetOf<String>()
        return lines.drop(1).filter { it.isNotEmpty() }.mapIndexed { index, line ->
            val fields = line.split('\t')
            require(fields.size == 5) { "Invalid to-do row ${index + 2}. The original file has not been changed." }
            UUID.fromString(fields[0])
            require(ids.add(fields[0])) { "Duplicate to-do ID." }
            require(fields[3] == "0" || fields[3] == "1") { "Invalid flag value." }
            val text = unescape(fields[4])
            require(text.isNotBlank()) { "Blank to-do item." }
            TodoItem(fields[0], text, OffsetDateTime.parse(fields[1]),
                fields[2].takeUnless { it == "-" }?.let(OffsetDateTime::parse), fields[3] == "1")
        }
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\")
        .replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    private fun unescape(value: String): String = buildString {
        var i = 0
        while (i < value.length) {
            val c = value[i++]
            if (c != '\\') append(c) else {
                require(i < value.length) { "Truncated escape." }
                append(when (val escaped = value[i++]) {
                    '\\' -> '\\'; 't' -> '\t'; 'n' -> '\n'; 'r' -> '\r'
                    else -> throw IllegalArgumentException("Unknown escape: $escaped")
                })
            }
        }
    }
}
