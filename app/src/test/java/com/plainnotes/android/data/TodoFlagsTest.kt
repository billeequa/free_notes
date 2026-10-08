package com.plainnotes.android.data

import org.junit.Assert.*
import org.junit.Test
import java.time.OffsetDateTime

class TodoFlagsTest {
    @Test fun everyCombinationSurvivesSavingCompletionAndReopening() {
        val added = OffsetDateTime.parse("2026-10-08T09:00:00-04:00")
        val completed = added.plusHours(1)
        val items = (0..7).map { mask ->
            TodoItem(title = "Task $mask", addedAt = added,
                flags = TodoFlag.entries.filter { mask and it.bit != 0 }.toSet())
        }
        val saved = TodoFileParser.parse(TodoFileParser.serialize(items))
        assertEquals(items, saved)
        val done = saved.map { it.copy(completedAt = completed) }
        val reloaded = TodoFileParser.parse(TodoFileParser.serialize(done))
        assertEquals(done, reloaded)
        assertEquals(items, reloaded.map { it.copy(completedAt = null) })
        assertEquals((0..7).toList(), saved.map { it.flags.flagMask() })
        assertEquals(7, saved.count { it.isFlagged })
    }

    @Test fun selectionOrderDoesNotChangeEmojiOrFileOrder() {
        val item = TodoItem(title = "Task").withFlag(TodoFlag.LONG_TERM)
            .withFlag(TodoFlag.IMPORTANT).withFlag(TodoFlag.URGENT)
        assertEquals("🚨 ❗ 🎯", item.flagSymbols)
        assertTrue(TodoFileParser.serialize(listOf(item)).contains("flags: urgent,important,long_term\n"))
        assertEquals("🚨 🎯", item.withFlag(TodoFlag.IMPORTANT).flagSymbols)
        assertFalse(item.withFlag(TodoFlag.URGENT).withFlag(TodoFlag.IMPORTANT).withFlag(TodoFlag.LONG_TERM).isFlagged)
    }

    @Test fun legacyFlagIsPreservedUntilExplicitlyReplaced() {
        val legacy = TodoFileParser.parse("""Jnotes To-do: 1

id: 12345678-1234-1234-1234-123456789abc
title: Legacy task
description: Keep this
added: 2026-09-15T12:10:00-04:00
completed: 2026-09-16T13:20:00-04:00
flagged: true
""").single()
        assertEquals("⚑", legacy.flagSymbols)
        assertTrue(legacy.isFlagged)
        assertEquals(legacy, TodoFileParser.parse(TodoFileParser.serialize(listOf(legacy))).single())
        val updated = legacy.withFlag(TodoFlag.URGENT)
        assertFalse(updated.flagged)
        assertEquals("🚨", updated.flagSymbols)
        assertEquals(legacy.copy(flagged = false, flags = setOf(TodoFlag.URGENT)), updated)
        assertEquals("", TodoItem(title = "New task").flagSymbols)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownFlagWithoutDroppingIt() {
        val file = TodoFileParser.serialize(listOf(TodoItem(title = "Task", flags = setOf(TodoFlag.URGENT))))
        TodoFileParser.parse(file.replace("flags: urgent", "flags: unknown"))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsTruncatedNewRecord() {
        val file = TodoFileParser.serialize(listOf(TodoItem(title = "Task")))
        TodoFileParser.parse(file.substringBefore("flags: "))
    }
}
