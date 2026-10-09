package com.plainnotes.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import com.plainnotes.android.data.TodoFlag
import org.junit.Assert.*
import org.junit.Test

class TodoFlagColorsTest {
    @Test fun combinationsHaveDistinctReadableColorsInLightAndDarkThemes() {
        for (dark in listOf(false, true)) {
            assertNull(todoFlagColors(emptySet(), dark))
            val colors = (1..7).map { mask ->
                todoFlagColors(TodoFlag.entries.filter { mask and it.bit != 0 }.toSet(), dark)!!
            }
            assertEquals(7, colors.map { it.container }.distinct().size)
            for (palette in colors) {
                assertTrue("Open text must meet 4.5:1 contrast", contrast(palette.container, palette.content) >= 4.5f)
                assertTrue("Completed text must meet 4.5:1 contrast", contrast(palette.container, palette.completedContent) >= 4.5f)
            }
        }
    }

    @Test fun finishedCardsAreMutedButAllFlagCombinationsRemainDistinctAndReadable() {
        for (dark in listOf(false, true)) {
            val neutral = (if (dark) darkColorScheme() else lightColorScheme()).surfaceContainerHigh
            val palettes = (1..7).map { mask ->
                todoFlagColors(TodoFlag.entries.filter { mask and it.bit != 0 }.toSet(), dark)!!
            }
            val muted = palettes.map { completedTodoContainer(it.container, neutral) }
            assertEquals(7, muted.distinct().size)
            palettes.zip(muted).forEach { (palette, container) ->
                assertNotEquals(palette.container, container)
                assertTrue("Muted completed text must meet 4.5:1 contrast",
                    contrast(container, palette.completedContent) >= 4.5f)
            }
        }
    }

    @Test fun folderUsesASoftPaletteTintWithoutTheDarkOverlay() {
        for (scheme in listOf(lightColorScheme(), darkColorScheme())) {
            val folder = completedFolderContainer(scheme.surface, scheme.primary)
            val task = completedTodoContainer(scheme.surfaceContainerHighest, scheme.surfaceContainerHigh)
            assertNotEquals(task, folder)
            assertNotEquals(scheme.surface, folder)
            assertTrue(contrast(folder, scheme.onSurfaceVariant) >= 4.5f)
            // Forest/Paper use a sage accent. A very small amount keeps light folders light.
            if (scheme.background.luminance() > 0.5f) assertTrue(folder.luminance() > 0.8f)
        }
    }

    private fun contrast(a: Color, b: Color): Float {
        val x = a.luminance()
        val y = b.luminance()
        return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
    }
}
