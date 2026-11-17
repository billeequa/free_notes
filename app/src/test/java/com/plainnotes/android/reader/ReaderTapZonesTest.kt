package com.plainnotes.android.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderTapZonesTest {
    @Test fun wideCurvesTurnPagesAndLeaveTheCenterForControls() {
        assertEquals(-1, ReaderTapZones.direction(199f, 400f, 1000, 800))
        assertEquals(1, ReaderTapZones.direction(801f, 400f, 1000, 800))
        assertEquals(0, ReaderTapZones.direction(500f, 400f, 1000, 800))
        assertEquals(-1, ReaderTapZones.direction(119f, 0f, 1000, 800))
        assertEquals(0, ReaderTapZones.direction(150f, 0f, 1000, 800))
        assertEquals(1, ReaderTapZones.direction(881f, 800f, 1000, 800))
        assertEquals(0, ReaderTapZones.direction(850f, 800f, 1000, 800))
    }

    @Test fun unmeasuredNavigatorDoesNotTurnPages() {
        assertEquals(0, ReaderTapZones.direction(10f, 10f, 0, 0))
    }
}
