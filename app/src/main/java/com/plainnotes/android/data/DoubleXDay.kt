package com.plainnotes.android.data

import java.time.LocalDate
import java.time.ZoneId

object DoubleXDay {
    fun accomplished(items: List<TodoItem>, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<TodoItem> =
        items.filter { it.completedAt?.atZoneSameInstant(zone)?.toLocalDate() == date }
            .sortedBy { it.completedAt?.toInstant() }

    fun newlyCompleted(previous: List<TodoItem>, current: List<TodoItem>): List<TodoItem> {
        val earlier = previous.associateBy { it.id }
        return current.filter { it.completedAt != null && earlier[it.id]?.completedAt == null }
    }
}
