package com.plainnotes.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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

    private fun contrast(a: Color, b: Color): Float {
        val x = a.luminance()
        val y = b.luminance()
        return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
    }
}
