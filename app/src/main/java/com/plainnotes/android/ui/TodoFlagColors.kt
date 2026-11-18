package com.plainnotes.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.plainnotes.android.data.TodoFlag
import com.plainnotes.android.data.flagMask

data class TodoFlagColors(val container: Color, val content: Color, val completedContent: Color)

/** To-do palettes for flags, repeat, and their combinations. */
fun todoFlagColors(flags: Set<TodoFlag>, dark: Boolean, repeating: Boolean = false): TodoFlagColors? {
    val mask = flags.flagMask() or (if (repeating) 8 else 0)
    if (mask == 0) return null
    val container = when (mask) {
        1 -> if (dark) 0xFF672D35 else 0xFFFFD9DE // urgent: red
        2 -> if (dark) 0xFF574A1B else 0xFFFFEDAB // important: gold
        4 -> if (dark) 0xFF25456D else 0xFFD9E8FF // long term: blue
        3 -> if (dark) 0xFF683C20 else 0xFFFFDDC0 // urgent + important: orange
        5 -> if (dark) 0xFF513466 else 0xFFECDCF8 // urgent + long term: purple
        6 -> if (dark) 0xFF20534F else 0xFFC8EFE9 // important + long term: teal
        7 -> if (dark) 0xFF36522C else 0xFFDDEFCA // all three: green
        8 -> if (dark) 0xFF294D34 else 0xFFD6F0D2 // repeat: leaf green
        9 -> if (dark) 0xFF5A4235 else 0xFFF4D9CD // repeat + urgent: clay
        10 -> if (dark) 0xFF474D25 else 0xFFE9F2AF // repeat + important: lime
        11 -> if (dark) 0xFF5B4927 else 0xFFF7E5AB // repeat + urgent + important: honey
        12 -> if (dark) 0xFF244C43 else 0xFFC9E7DF // repeat + long term: mint
        13 -> if (dark) 0xFF453D59 else 0xFFE3DAED // repeat + urgent + long term: lilac
        14 -> if (dark) 0xFF34513A else 0xFFCBE5B9 // repeat + important + long term: sage
        else -> if (dark) 0xFF454B30 else 0xFFE4E8BA // all four: olive
    }
    return TodoFlagColors(
        container = Color(container),
        content = Color(if (dark) 0xFFF4F4F5 else 0xFF202124),
        completedContent = Color(if (dark) 0xFFD3D3D8 else 0xFF4B4B50),
    )
}

/** Keep the flag hue, but blend finished cards slightly toward the theme's neutral surface. */
fun completedTodoContainer(container: Color, neutral: Color): Color = lerp(container, neutral, 0.25f)

/** A soft tint from the existing theme palette separates archive boxes without a dark overlay. */
fun completedFolderContainer(surface: Color, accent: Color): Color = lerp(surface, accent, 0.12f)
