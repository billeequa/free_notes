package com.plainnotes.android.ui.components

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
// Use real Android font metrics; legacy graphics uses fake metrics that do
// not change with text size and cannot verify line-scaled end spacing.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoteEditorNativeTest {
    private class TrackingEditText : EditText(ContextThemeWrapper(
        RuntimeEnvironment.getApplication(), android.R.style.Theme_Material_Light_NoActionBar,
    )) {
        var requestedPoint: Int? = null
        override fun bringPointIntoView(offset: Int): Boolean {
            requestedPoint = offset
            return super.bringPointIntoView(offset)
        }
    }

    private fun editor(): TrackingEditText = TrackingEditText().apply {
        // AndroidView supplies these in the app; Android's selection span
        // watcher also needs them in a standalone framework test.
        layoutParams = ViewGroup.LayoutParams(300, 180)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        configureNoteTextBase(Color.WHITE, Color.GRAY, Color.CYAN, 1f)
        setText((1..18).joinToString("\n") { "Line $it has selectable words" })
        measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY))
        layout(0, 0, 300, 180)
    }

    @Test fun appearanceUpdatesPreserveSelectionAndDoNotRequestLayout() {
        val field = editor()
        field.setSelection(5, 25)
        val originalLayout = field.layout
        assertFalse(field.isLayoutRequested)
        repeat(20) { field.updateNoteTextAppearance(Color.WHITE, Color.GRAY, Color.CYAN, 1f) }
        assertEquals(5, field.selectionStart)
        assertEquals(25, field.selectionEnd)
        assertSame(originalLayout, field.layout)
        assertFalse("Recomposition must not resize the editor", field.isLayoutRequested)
        assertNotNull(field.textSelectHandleLeft)
        assertNotNull(field.textSelectHandleRight)
    }

    @Test fun rangeSelectionIsLeftToNativeHandleScrolling() {
        val field = editor()
        field.setSelection(5, field.length() - 5)
        field.requestedPoint = null
        bringSelectionIntoView(field)
        assertNull("Do not pan to one end of a two-handle selection", field.requestedPoint)
        assertEquals(5, field.selectionStart)
        assertEquals(field.length() - 5, field.selectionEnd)
    }

    @Test fun collapsedCaretStillReceivesVisibilityAssistance() {
        val field = editor()
        field.setSelection(field.length())
        field.requestedPoint = null
        bringSelectionIntoView(field)
        assertEquals(field.length(), field.requestedPoint)
    }

    @Test fun finalLineCanScrollFiveLinesAboveBottomWithoutChangingNoteText() {
        val field = editor()
        val writing = field.text.toString()
        val viewport = createNoteScrollContainer(field)
        viewport.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
        viewport.layout(0, 0, 300, 400)
        viewport.scrollTo(0, Int.MAX_VALUE)
        val finalLineBottom = field.top + field.totalPaddingTop +
            field.layout.getLineBottom(field.lineCount - 1) - viewport.scrollY
        assertTrue("Final line needs the requested clearance",
            viewport.height - finalLineBottom >= field.lineHeight * 5)
        assertEquals(field.lineHeight * 5, viewport.paddingBottom)
        assertEquals(writing, field.text.toString())
        assertFalse("Text must draw through padding while scrolling", viewport.clipToPadding)
        assertEquals(0, (viewport.background as ColorDrawable).alpha)
        assertEquals(0, (field.background as ColorDrawable).alpha)
        assertEquals(0, field.paddingBottom)
    }

    @Test fun scrollingAndOverflowTransitionsKeepViewportAndTailStable() {
        val field = editor()
        val viewport = createNoteScrollContainer(field)
        for (lines in listOf(3, 6, 12, 15, 30)) {
            field.setText((1..lines).joinToString("\n") { "Line $it" })
            for (height in listOf(500, 180, 500, 180)) {
                viewport.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                viewport.layout(0, 0, 300, height)
                for (offset in listOf(0, Int.MAX_VALUE, 0)) {
                    viewport.scrollTo(0, offset)
                    field.updateNoteTextAppearance(Color.WHITE, Color.GRAY, Color.CYAN, 1f)
                    viewport.updateNoteEndSpace(field)
                    assertEquals(height, viewport.height)
                    assertEquals(field.lineHeight * 5, viewport.paddingBottom)
                    assertFalse(viewport.isLayoutRequested)
                    assertFalse(field.isLayoutRequested)
                }
            }
        }
    }

    @Test fun endSpaceScalesWithFontAndPreservesRangeSelection() {
        val field = editor()
        val viewport = createNoteScrollContainer(field)
        field.setSelection(5, 25)
        val originalTail = viewport.paddingBottom
        field.updateNoteTextAppearance(Color.WHITE, Color.GRAY, Color.CYAN, 1.5f)
        viewport.updateNoteEndSpace(field)
        assertTrue(viewport.paddingBottom > originalTail)
        assertEquals(field.lineHeight * 5, viewport.paddingBottom)
        assertEquals(5, field.selectionStart)
        assertEquals(25, field.selectionEnd)
    }
}
