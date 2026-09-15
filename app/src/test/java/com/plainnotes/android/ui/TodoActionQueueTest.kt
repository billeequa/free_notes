package com.plainnotes.android.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TodoActionQueueTest {
    @Test fun rapidActionsWaitAndRunInOrder() = runBlocking {
        val queue = TodoActionQueue(this)
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val first = queue.submit { events += "saving"; gate.await(); events += "complete" }
        val second = queue.submit { events += "flag" }
        val third = queue.submit { events += "delete" }
        assertEquals(listOf("saving"), events)
        assertFalse(second.isCompleted)
        assertFalse(third.isCompleted)
        gate.complete(Unit)
        first.join(); second.join(); third.join()
        assertEquals(listOf("saving", "complete", "flag", "delete"), events)
    }

    @Test fun queuedActionsUseTheLatestCommittedState() = runBlocking {
        val queue = TodoActionQueue(this)
        val gate = CompletableDeferred<Unit>()
        var value = 0
        val first = queue.submit { gate.await(); value += 1 }
        val second = queue.submit { value *= 10 }
        gate.complete(Unit)
        first.join(); second.join()
        assertEquals(10, value)
    }
}
