package com.plainnotes.android.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverThumbnailsTest {
    @Test fun samplingKeepsEnoughPixelsForTheExistingDisplaySize() {
        assertEquals(16, CoverThumbnails.sampleSize(1000, 1500, 60, 90))
        assertEquals(4, CoverThumbnails.sampleSize(1000, 1500, 180, 270))
        assertEquals(1, CoverThumbnails.sampleSize(50, 75, 60, 90))
        assertEquals(1, CoverThumbnails.sampleSize(1000, 100, 60, 90))
    }
}
