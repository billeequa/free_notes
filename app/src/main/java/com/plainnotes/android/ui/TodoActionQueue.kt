package com.plainnotes.android.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owned by the ViewModel: tab changes cannot cancel queued user actions. */
class TodoActionQueue(private val scope: CoroutineScope) {
    private val mutex = Mutex()
    fun submit(action: suspend () -> Unit) = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        mutex.withLock { action() }
    }
}
