package com.plainnotes.android.data

/** Declaration order is the display and file order, regardless of selection order. */
enum class TodoFlag(val key: String, val emoji: String, val label: String, val bit: Int) {
    URGENT("urgent", "🚨", "urgent", 1),
    IMPORTANT("important", "❗", "important", 2),
    LONG_TERM("long_term", "🎯", "long term", 4),
}

fun Set<TodoFlag>.flagMask(): Int = fold(0) { mask, flag -> mask or flag.bit }

fun TodoItem.withFlag(flag: TodoFlag): TodoItem = copy(
    flagged = false,
    flags = if (flag in flags) flags - flag else flags + flag,
)

val TodoItem.isFlagged: Boolean get() = flagged || flags.isNotEmpty()

val TodoItem.flagSymbols: String get() = if (flags.isEmpty()) {
    if (flagged) "⚑" else ""
} else TodoFlag.entries.filter { it in flags }.joinToString(" ") { it.emoji }
