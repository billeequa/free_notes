package com.plainnotes.android.reader

import com.plainnotes.android.data.AppSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderFontSettingsTest {
    @Test fun readerSizeInheritsExistingSizeThenPersistsIndependently() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val settings = AppSettingsRepository(context)
        settings.setFontScale(1.15f)
        assertEquals(1.15f, settings.readerFontScale.first(), .0001f)
        settings.setReaderFontScale(1.5f)
        settings.setFontScale(.9f)
        assertEquals(1.5f, AppSettingsRepository(context).readerFontScale.first(), .0001f)
        assertEquals(.9f, settings.fontScale.first(), .0001f)
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, .1f, 3f)) {
            try { settings.setReaderFontScale(value); fail("Invalid reader font accepted") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(1.5f, settings.readerFontScale.first(), .0001f)
    }
}
