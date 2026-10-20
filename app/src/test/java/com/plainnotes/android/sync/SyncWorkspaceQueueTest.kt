package com.plainnotes.android.sync

import androidx.work.ListenableWorker.Result
import com.plainnotes.android.data.Workspace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SyncWorkspaceQueueTest {
    @Test fun personalRetryDoesNotStarveWorkAndStillRequestsRetry() = runBlocking {
        val visited = mutableListOf<Workspace>()
        val result = syncWorkspaces(listOf(Workspace.PERSONAL, Workspace.WORK)) {
            visited += it
            if (it == Workspace.PERSONAL) Result.retry() else Result.success()
        }
        assertEquals(listOf(Workspace.PERSONAL, Workspace.WORK), visited)
        assertEquals(Result.retry(), result)
    }

    @Test fun personalRepairFailureStillAttemptsWork() = runBlocking {
        val visited = mutableListOf<Workspace>()
        val result = syncWorkspaces(listOf(Workspace.PERSONAL, Workspace.WORK)) {
            visited += it
            if (it == Workspace.PERSONAL) Result.failure() else Result.success()
        }
        assertEquals(listOf(Workspace.PERSONAL, Workspace.WORK), visited)
        assertEquals(Result.failure(), result)
    }

    @Test fun workRetrySurvivesPersonalSuccessAndDisabledWorkIsNotVisited() = runBlocking {
        assertEquals(Result.retry(), syncWorkspaces(listOf(Workspace.PERSONAL, Workspace.WORK)) {
            if (it == Workspace.WORK) Result.retry() else Result.success()
        })
        val visited = mutableListOf<Workspace>()
        assertEquals(Result.success(), syncWorkspaces(listOf(Workspace.PERSONAL)) {
            visited += it; Result.success()
        })
        assertEquals(listOf(Workspace.PERSONAL), visited)
    }
}
