package com.plainnotes.android.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderTapZonesTest {
    @Test fun wideCurvesTurnPagesAndLeaveTheCenterForControls() {
        assertEquals(-1, ReaderTapZones.direction(319f, 400f, 1000, 800))
        assertEquals(1, ReaderTapZones.direction(681f, 400f, 1000, 800))
        assertEquals(0, ReaderTapZones.direction(500f, 400f, 1000, 800))
        assertEquals(-1, ReaderTapZones.direction(199f, 0f, 1000, 800))
        assertEquals(0, ReaderTapZones.direction(250f, 0f, 1000, 800))
        assertEquals(1, ReaderTapZones.direction(801f, 800f, 1000, 800))
        assertEquals(0, ReaderTapZones.direction(750f, 800f, 1000, 800))
    }

    @Test fun unmeasuredNavigatorDoesNotTurnPages() {
        assertEquals(0, ReaderTapZones.direction(10f, 10f, 0, 0))
    }
}
