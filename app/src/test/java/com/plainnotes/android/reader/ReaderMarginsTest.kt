package com.plainnotes.android.reader

import com.plainnotes.android.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ReaderMarginsTest {
    @Test fun portraitUsesSmallMarginsInsteadOfReadiumsFortyDpDefault() = checkMargin()
    @Test @Config(qualifiers="land") fun landscapeAlsoUsesSmallMargins() = checkMargin()
    private fun checkMargin() {
        val resources = RuntimeEnvironment.getApplication().resources
        assertEquals(4f, resources.getDimension(R.dimen.readium_navigator_epub_vertical_padding) / resources.displayMetrics.density, 0.01f)
    }
}
