package com.plainnotes.android.ui.components

import android.graphics.Color
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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
}
