package com.plainnotes.android.reader

/** The curved edge reaches 32% of the page at mid-height and 20% at either end. */
internal object ReaderTapZones {
    const val END_WIDTH = 0.20f
    const val MIDDLE_WIDTH = 0.32f
    const val CURVE_CONTROL_WIDTH = 2 * MIDDLE_WIDTH - END_WIDTH

    fun edgeWidth(yFraction: Float): Float {
        val centered = 2 * yFraction.coerceIn(0f, 1f) - 1
        return END_WIDTH + (MIDDLE_WIDTH - END_WIDTH) * (1 - centered * centered)
    }

    /** -1: previous, 0: controls, +1: next. Coordinates are relative to the navigator. */
    fun direction(x: Float, y: Float, width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 0
        val edge = width * edgeWidth(y / height)
        return when {
            x <= edge -> -1
            x >= width - edge -> 1
            else -> 0
        }
    }
}
