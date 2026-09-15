package com.plainnotes.android.ui

import com.plainnotes.android.data.TodoItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TodoSessionTest {
    @Test fun completionFlagAndReopenPreserveIdentityAndCreation() = runTest {
        val original = TodoItem(text = "Test")
        val session = TodoSession(backgroundScope, { listOf(original) }, {})
        session.load(); runCurrent()
        session.complete(original.id); runCurrent()
        val completed = session.state.value.items.single()
        assertNotNull(completed.completedAt)
        session.complete(original.id); session.flag(original.id); runCurrent()
        assertEquals(completed.completedAt, session.state.value.items.single().completedAt)
        assertTrue(session.state.value.items.single().flagged)
        session.reopen(original.id); runCurrent()
        assertEquals(original.copy(flagged = true), session.state.value.items.single())
    }

    @Test fun overlappingUpdatesAndFailedDeletionRemainRetryable() = runTest {
        val writes = mutableListOf<List<TodoItem>>()
        var gate: CompletableDeferred<Unit>? = null
        var fail = false
        val session = TodoSession(backgroundScope, { emptyList() }, {
            gate?.await()
            if (fail) error("Unavailable folder")
            writes += it
        })
        session.load(); runCurrent()
        gate = CompletableDeferred()
        session.add("One"); runCurrent()
        session.add("Two")
        gate!!.complete(Unit); runCurrent()
        assertEquals(listOf("One", "Two"), writes.last().map { it.text })
        fail = true
        session.delete(session.state.value.items.first().id); runCurrent()
        assertNotNull(session.state.value.error)
        fail = false
        session.retry(); runCurrent()
        assertEquals(listOf("Two"), writes.last().map { it.text })
        assertNull(session.state.value.error)
    }

    @Test fun unreadableFileCannotBeOverwrittenByAdd() = runTest {
        var writes = 0
        val session = TodoSession(backgroundScope, { error("Malformed file") }, { writes++ })
        session.load(); runCurrent(); session.add("Do not overwrite"); runCurrent()
        assertFalse(session.state.value.loaded)
        assertEquals(0, writes)
    }
}
