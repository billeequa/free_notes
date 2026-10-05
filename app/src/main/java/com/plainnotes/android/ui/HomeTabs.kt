package com.plainnotes.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// One order for both labels and pager destinations. Notes remains the startup page.
internal enum class HomePage(val label: String) {
    EBOOKS("Ebooks"), NOTES("Notes"), JOURNAL("Journal"), TODOS("To Do"),
}

internal fun homePages(showReader: Boolean): List<HomePage> =
    HomePage.entries.filter { showReader || it != HomePage.EBOOKS }

@Composable
internal fun HomeTabs(selectedIndex: Int, pages: List<HomePage>, onPage: (Int) -> Unit) {
    val latestOnPage by rememberUpdatedState(onPage)
    Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
        // Fixed equal-width choices: never replace this with ScrollableTabRow or
        // horizontalScroll. A drag selects an adjacent PAGE; the bar stays still.
        // This handler belongs only to the bar, so To Do keeps its own gestures.
        TabRow(
            selectedTabIndex = selectedIndex.coerceIn(pages.indices),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.pointerInput(selectedIndex, pages.size) {
                var distance = 0f
                detectHorizontalDragGestures(
                    onDragStart = { distance = 0f },
                    onDragCancel = { distance = 0f },
                    onDragEnd = {
                        val destination = when {
                            distance > 56.dp.toPx() -> selectedIndex - 1
                            distance < -56.dp.toPx() -> selectedIndex + 1
                            else -> selectedIndex
                        }
                        if (destination in pages.indices && destination != selectedIndex) {
                            latestOnPage(destination)
                        }
                    },
                    onHorizontalDrag = { change, amount -> change.consume(); distance += amount },
                )
            },
        ) {
            pages.forEachIndexed { index, page ->
                Tab(
                    selected = selectedIndex == index,
                    onClick = { onPage(index) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        page.label,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}
