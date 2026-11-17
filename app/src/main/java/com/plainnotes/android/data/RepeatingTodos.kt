package com.plainnotes.android.data

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID

object RepeatingTodos {
    /** Same UUID on every client: concurrent completion cannot create two successors. */
    fun nextId(id: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest("jnotes.repeat.${id.lowercase()}".toByteArray(Charsets.UTF_8))
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x80).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long).toString()
    }

    fun complete(items: List<TodoItem>, id: String, now: OffsetDateTime = OffsetDateTime.now()): List<TodoItem> {
        val original = items.find { it.id == id && it.completedAt == null } ?: return items
        val completed = items.map {
            if (it.id == id) it.copy(completedAt = now, repeatOnComplete = false) else it
        }
        if (!original.repeatOnComplete) return completed
        val nextId = nextId(id)
        if (items.any { it.id == nextId }) return completed
        return completed + original.copy(id = nextId, addedAt = now, completedAt = null)
    }
}
