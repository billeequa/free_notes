package com.plainnotes.android.data

/** A fixed storage scope. Switching UI never changes an in-flight operation's scope. */
enum class Workspace {
    PERSONAL, WORK;

    fun recordDeployment(deployment: String) = if (this == WORK) "$deployment/work" else deployment
    fun rpc(name: String) = if (this == WORK && name in listOf("get_vault", "get_changes", "commit_operation", "get_history", "ack_cursor")) "work_$name" else name
}

data class WorkspaceSettings(val enabled: Boolean = false, val active: Workspace = Workspace.PERSONAL)
