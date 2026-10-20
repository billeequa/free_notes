package com.plainnotes.android.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.plainnotes.android.R
import com.plainnotes.android.data.Workspace

@Composable
internal fun WorkspaceSwitchButton(workspace: Workspace, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.secondary,
        contentColor = MaterialTheme.colorScheme.onSecondary,
        modifier = modifier.semantics { contentDescription = if (workspace == Workspace.WORK)
            "Work mode. Switch to Personal mode" else "Personal mode. Switch to Work mode" },
    ) {
        Icon(painterResource(if (workspace == Workspace.WORK) R.drawable.workspace_p else R.drawable.workspace_w),
            contentDescription = null, modifier = Modifier.size(32.dp))
    }
}
