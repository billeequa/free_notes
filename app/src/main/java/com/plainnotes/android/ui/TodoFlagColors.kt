package com.plainnotes.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.plainnotes.android.data.TodoFlag
import com.plainnotes.android.data.flagMask

data class TodoFlagColors(val container: Color, val content: Color, val completedContent: Color)

/** To-do-only palettes: completion changes the text tone, never the flag color. */
fun todoFlagColors(flags: Set<TodoFlag>, dark: Boolean): TodoFlagColors? {
    val mask = flags.flagMask()
    if (mask == 0) return null
    val container = when (mask) {
        1 -> if (dark) 0xFF672D35 else 0xFFFFD9DE // urgent: red
        2 -> if (dark) 0xFF574A1B else 0xFFFFEDAB // important: gold
        4 -> if (dark) 0xFF25456D else 0xFFD9E8FF // long term: blue
        3 -> if (dark) 0xFF683C20 else 0xFFFFDDC0 // urgent + important: orange
        5 -> if (dark) 0xFF513466 else 0xFFECDCF8 // urgent + long term: purple
        6 -> if (dark) 0xFF20534F else 0xFFC8EFE9 // important + long term: teal
        else -> if (dark) 0xFF36522C else 0xFFDDEFCA // all three: green
    }
    return TodoFlagColors(
        container = Color(container),
        content = Color(if (dark) 0xFFF4F4F5 else 0xFF202124),
        completedContent = Color(if (dark) 0xFFD3D3D8 else 0xFF4B4B50),
    )
}

/** Keep the flag hue, but blend finished cards slightly toward the theme's neutral surface. */
fun completedTodoContainer(container: Color, neutral: Color): Color = lerp(container, neutral, 0.25f)
