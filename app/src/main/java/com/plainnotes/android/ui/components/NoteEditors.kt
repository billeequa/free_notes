package com.plainnotes.android.ui.components

import android.graphics.Color
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Editable
import android.text.InputType
import android.text.Layout
import android.text.TextWatcher
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.ActionMode
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.util.LinkifyCompat
import androidx.core.view.WindowInsetsCompat

private const val OpenLinkMenuItemId = 0x706c6169
private const val NoteBodyTextSizeSp = 18f
private const val NoteBodyLineSpacingMultiplier = 1.15f
private const val NoteEndSpaceLines = 5

@Composable
fun TitleEditor(
    value: String,
    onValueChange: (String) -> Unit,
    fontScale: Float,
    modifier: Modifier = Modifier,
) {
    val textColor = MaterialTheme.colorScheme.onBackground.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()

    AndroidView(
        modifier = modifier,
        factory = { context ->
            EditText(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setTextColor(textColor)
                setHintTextColor(hintColor)
                hint = "Untitled"
                textSize = 28f * fontScale
                setSingleLine(true)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 0)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                addTextChangedListener(
                    SimpleTextWatcher { editable ->
                        onValueChange(editable?.toString().orEmpty())
                    },
                )
            }
        },
        update = { editText ->
            syncPlainText(editText, value)
            editText.setTextColor(textColor)
            editText.setHintTextColor(hintColor)
            editText.textSize = 28f * fontScale
        },
    )
}

@Composable
fun NoteBodyEditor(
    value: String,
    onValueChange: (String) -> Unit,
    onUrlTapped: (String) -> Unit,
    shouldRequestFocus: Boolean,
    onFocusHandled: () -> Unit,
    requestedSelection: Int?,
    initialScrollY: Int,
    onScrollChanged: (Int) -> Unit,
    fontScale: Float,
    focusController: NoteEditorFocusController,
    onEditingChanged: (Boolean) -> Unit,
    onBackToReading: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textColor = MaterialTheme.colorScheme.onBackground.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            NoteEditText(context).apply {
                configureNoteTextBase(
                    textColor = textColor,
                    hintColor = hintColor,
                    linkColor = linkColor,
                    fontScale = fontScale,
                )
                isFocusable = true
                isFocusableInTouchMode = true
                isCursorVisible = false
                setOnFocusChangeListener { _, focused ->
                    isCursorVisible = focused
                    onEditingChanged(focused)
                }
                this.onBackToReading = onBackToReading
                showSoftInputOnFocus = true
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setCustomSelectionActionModeCallback(
                    object : ActionMode.Callback {
                        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                            updateOpenLinkMenu(menu, this@apply)
                            return true
                        }

                        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                            updateOpenLinkMenu(menu, this@apply)
                            return false
                        }

                        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                            if (item.itemId != OpenLinkMenuItemId) {
                                return false
                            }

                            val selectedUrl = findSelectedUrl(this@apply) ?: return false
                            onUrlTapped(selectedUrl)
                            mode.finish()
                            return true
                        }

                        override fun onDestroyActionMode(mode: ActionMode) = Unit
                    },
                )
                addTextChangedListener(
                    object : TextWatcher {
                        private var isApplyingLinks = false
                        private var changedStart = 0
                        private var replacedCount = 0
                        private var insertedCount = 0
                        private val applyLinksRunnable = Runnable {
                            if (isApplyingLinks) {
                                return@Runnable
                            }
                            isApplyingLinks = true
                            editableText?.let(::reapplyUrlSpans)
                            isApplyingLinks = false
                        }

                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                            changedStart = start
                            replacedCount = count
                            insertedCount = after
                        }

                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                            changedStart = start
                            replacedCount = before
                            insertedCount = count
                        }

                        override fun afterTextChanged(editable: Editable?) {
                            if (isApplyingLinks || editable == null) {
                                return
                            }

                            onValueChange(editable.toString())
                            removeCallbacks(applyLinksRunnable)
                            post {
                                if (hasFocus()) bringSelectionIntoView(this@apply)
                            }
                            if (shouldRefreshLinks(
                                    editable = editable,
                                    changeStart = changedStart,
                                    replacedCount = replacedCount,
                                    insertedCount = insertedCount,
                                )
                            ) {
                                post(applyLinksRunnable)
                            }
                        }
                    },
                )
            }.let { editor ->
                createNoteScrollContainer(editor).apply {
                    focusController.attach(editor, this)
                    setOnScrollChangeListener { _, _, scrollY, _, _ -> onScrollChanged(scrollY) }
                    post { scrollTo(0, initialScrollY) }
                }
            }
        },
        update = { scrollView ->
            val editText = scrollView.getChildAt(0) as NoteEditText
            focusController.attach(editText, scrollView)
            editText.onBackToReading = onBackToReading
            editText.setOnFocusChangeListener { _, focused ->
                editText.isCursorVisible = focused
                onEditingChanged(focused)
            }
            // Recomposition (typing, saving, scrolling) must not reset native
            // line/input settings or request layout while selection handles run.
            editText.updateNoteTextAppearance(
                textColor = textColor,
                hintColor = hintColor,
                linkColor = linkColor,
                fontScale = fontScale,
            )
            scrollView.updateNoteEndSpace(editText)
            scrollView.setOnScrollChangeListener { _, _, scrollY, _, _ -> onScrollChanged(scrollY) }
            syncLinkifiedText(editText, value)
            if (shouldRequestFocus) {
                val targetSelection = requestedSelection
                    ?.coerceIn(0, editText.text?.length ?: 0)
                    ?: (editText.text?.length ?: 0)
                // Wait until AndroidView is attached and laid out before asking for the IME.
                editText.post {
                    editText.requestFocus()
                    editText.setSelection(targetSelection.coerceAtMost(editText.length()))
                    androidx.core.view.WindowInsetsControllerCompat(
                        (editText.context as android.app.Activity).window, editText,
                    ).show(androidx.core.view.WindowInsetsCompat.Type.ime())
                    bringSelectionIntoView(editText)
                }
                onFocusHandled()
            }
        },
    )
}

/**
 * A document-height editor inside an edge-to-edge scrolling viewport. The five
 * line tail is UI space, never newlines in the saved note. Padding belongs to
 * the ScrollView, with clipping OFF, so text can still draw all the way behind
 * the transparent gesture bar while scrolling. Padding the EditText or its
 * Compose parent instead would reserve a permanent blank ribbon.
 */
internal fun createNoteScrollContainer(editor: EditText): ScrollView = ScrollView(editor.context).apply {
    // Give focus somewhere to go on Back. Otherwise clearFocus can immediately
    // hand it back to the only focusable child and leave the caret visible.
    isFocusable = true
    isFocusableInTouchMode = true
    descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
    isFillViewport = true
    clipToPadding = false
    setBackgroundColor(Color.TRANSPARENT)
    overScrollMode = TextView.OVER_SCROLL_NEVER
    isVerticalScrollBarEnabled = false
    addView(editor, FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
    ))
    updateNoteEndSpace(editor)
}

/** Keep the same native document/viewport when switching from writing to reading. */
class NoteEditorFocusController {
    private var editor: EditText? = null
    private var viewport: ScrollView? = null

    internal fun attach(editor: EditText, viewport: ScrollView) {
        this.editor = editor
        this.viewport = viewport
    }

    fun stopEditing() {
        val field = editor ?: return
        val scrollView = viewport ?: return
        val scrollY = scrollView.scrollY
        field.isCursorVisible = false
        scrollView.requestFocus()
        field.clearFocus()
        androidx.core.view.ViewCompat.getWindowInsetsController(field)
            ?.hide(WindowInsetsCompat.Type.ime())
        scrollView.scrollTo(0, scrollY)
    }
}

internal fun ScrollView.updateNoteEndSpace(editor: EditText) {
    // Derive only from typography, never scroll position, text length or IME
    // visibility. Unchanged updates must not request layout (1.4.2 regression).
    val endSpace = editor.lineHeight * NoteEndSpaceLines
    if (paddingBottom != endSpace) setPadding(0, 0, 0, endSpace)
}

@Composable
fun NoteBodyViewer(
    value: String,
    onTapToEdit: (Int) -> Unit,
    onUrlTapped: (String) -> Unit,
    initialScrollY: Int,
    onScrollChanged: (Int) -> Unit,
    fontScale: Float,
    modifier: Modifier = Modifier,
) {
    val textColor = MaterialTheme.colorScheme.onBackground.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            ScrollView(context).apply {
                isFillViewport = true
                overScrollMode = TextView.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                setBackgroundColor(Color.TRANSPARENT)
                isVerticalFadingEdgeEnabled = false
                setOnScrollChangeListener { _, _, scrollY, _, _ ->
                    onScrollChanged(scrollY)
                }

                val textView = TextView(context).apply {
                    configureReaderTextBase(
                        textColor = textColor,
                        hintColor = hintColor,
                        linkColor = linkColor,
                        fontScale = fontScale,
                    )
                    setBackgroundColor(Color.TRANSPARENT)
                }

                addView(
                    textView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )

                val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
                var downX = 0f
                var downY = 0f
                var moved = false
                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.x
                            downY = event.y
                            moved = false
                            false
                        }

                        MotionEvent.ACTION_MOVE -> {
                            if (!moved) {
                                moved =
                                    kotlin.math.abs(event.x - downX) > touchSlop ||
                                    kotlin.math.abs(event.y - downY) > touchSlop
                            }
                            false
                        }

                        MotionEvent.ACTION_UP -> {
                            if (moved) {
                                return@setOnTouchListener false
                            }

                            val tappedUrl = findUrlAtPosition(
                                textView = textView,
                                x = event.x.toInt(),
                                y = (event.y + scrollY).toInt(),
                            )
                            if (tappedUrl != null) {
                                onUrlTapped(tappedUrl)
                                true
                            } else {
                                onTapToEdit(
                                    findTextOffsetAtPosition(
                                        textView = textView,
                                        x = event.x.toInt(),
                                        y = (event.y + scrollY).toInt(),
                                    ),
                                )
                                true
                            }
                        }

                        else -> false
                    }
                }
                post { scrollTo(0, initialScrollY) }
            }
        },
        update = { scrollView ->
            val textView = scrollView.getChildAt(0) as TextView
            textView.configureReaderTextBase(
                textColor = textColor,
                hintColor = hintColor,
                linkColor = linkColor,
                fontScale = fontScale,
            )
            if (value.isBlank()) {
                textView.text = ""
                textView.hint = "Tap to start writing"
            } else {
                textView.hint = ""
                syncLinkifiedDisplayText(textView, value)
            }
        },
    )
}

private fun syncPlainText(editText: EditText, value: String) {
    if (editText.text?.toString() == value) {
        return
    }

    val selectionStart = editText.selectionStart.takeIf { it >= 0 } ?: value.length
    val selectionEnd = editText.selectionEnd.takeIf { it >= 0 } ?: value.length
    editText.setText(value)
    editText.setSelection(
        selectionStart.coerceAtMost(value.length),
        selectionEnd.coerceAtMost(value.length),
    )
}

private fun syncLinkifiedText(editText: EditText, value: String) {
    if (editText.text?.toString() == value) {
        return
    }

    val selectionStart = editText.selectionStart.takeIf { it >= 0 } ?: value.length
    val selectionEnd = editText.selectionEnd.takeIf { it >= 0 } ?: value.length
    editText.setText(value, TextView.BufferType.EDITABLE)
    reapplyUrlSpans(editText.editableText)
    if (editText.isFocusable) {
        editText.setSelection(
            selectionStart.coerceAtMost(value.length),
            selectionEnd.coerceAtMost(value.length),
        )
    }
}

private fun syncLinkifiedDisplayText(textView: TextView, value: String) {
    if (textView.text?.toString() == value) {
        return
    }

    val spannable = SpannableStringBuilder(value)
    LinkifyCompat.addLinks(spannable, Linkify.WEB_URLS)
    textView.text = spannable
}

private fun reapplyUrlSpans(editable: Editable) {
    editable.getSpans(0, editable.length, URLSpan::class.java).forEach { span ->
        editable.removeSpan(span)
    }
    LinkifyCompat.addLinks(editable, Linkify.WEB_URLS)
}

private fun findUrlAtOffset(editText: EditText, event: MotionEvent): String? {
    val text = editText.text ?: return null
    val layout = editText.layout ?: return null
    val x = (event.x - editText.totalPaddingLeft + editText.scrollX).toInt()
    val y = (event.y - editText.totalPaddingTop + editText.scrollY).toInt()
    if (x < 0 || y < 0 || x > layout.width || y > layout.height) {
        return null
    }

    val line = layout.getLineForVertical(y)
    val offset = layout.getOffsetForHorizontal(line, x.toFloat())
    return findUrlAtIndex(text, offset)
}

private fun findUrlAtOffset(textView: TextView, event: MotionEvent): String? {
    return findUrlAtPosition(
        textView = textView,
        x = event.x.toInt(),
        y = event.y.toInt(),
    )
}

private fun findUrlAtPosition(textView: TextView, x: Int, y: Int): String? {
    val text = textView.text ?: return null
    val layout = textView.layout ?: return null
    val adjustedX = x - textView.totalPaddingLeft + textView.scrollX
    val adjustedY = y - textView.totalPaddingTop + textView.scrollY
    if (adjustedX < 0 || adjustedY < 0 || adjustedX > layout.width || adjustedY > layout.height) {
        return null
    }

    val line = layout.getLineForVertical(adjustedY)
    val offset = layout.getOffsetForHorizontal(line, adjustedX.toFloat())
    val spannable = SpannableStringBuilder(text)
    return findUrlAtIndex(spannable, offset)
}

private fun findTextOffsetAtPosition(textView: TextView, event: MotionEvent): Int {
    return findTextOffsetAtPosition(
        textView = textView,
        x = event.x.toInt(),
        y = event.y.toInt(),
    )
}

private fun findTextOffsetAtPosition(textView: TextView, x: Int, y: Int): Int {
    val layout = textView.layout ?: return 0
    val adjustedX = x - textView.totalPaddingLeft + textView.scrollX
    val adjustedY = y - textView.totalPaddingTop + textView.scrollY
    if (adjustedX < 0 || adjustedY < 0) {
        return 0
    }

    val safeY = adjustedY.coerceAtMost(layout.height)
    val line = layout.getLineForVertical(safeY)
    return layout.getOffsetForHorizontal(line, adjustedX.toFloat())
}

private fun findUrlAtSelection(editText: EditText): String? {
    val text = editText.text ?: return null
    val selectionStart = editText.selectionStart
    val selectionEnd = editText.selectionEnd
    if (selectionStart < 0 || selectionEnd < 0 || selectionStart != selectionEnd) {
        return null
    }

    return findUrlAtIndex(text, selectionStart)
        ?: findUrlAtIndex(text, (selectionStart - 1).coerceAtLeast(0))
}

private fun findSelectedUrl(editText: EditText): String? {
    val text = editText.text ?: return null
    val selectionStart = editText.selectionStart
    val selectionEnd = editText.selectionEnd
    if (selectionStart < 0 || selectionEnd < 0) {
        return null
    }

    val start = minOf(selectionStart, selectionEnd)
    val end = maxOf(selectionStart, selectionEnd)
    if (start == end) {
        return findUrlAtSelection(editText)
    }

    return text.getSpans(start, end, URLSpan::class.java)
        .firstOrNull { span ->
            val spanStart = text.getSpanStart(span)
            val spanEnd = text.getSpanEnd(span)
            start < spanEnd && end > spanStart
        }
        ?.url
}

private fun findUrlAtIndex(text: Editable, index: Int): String? {
    if (text.isEmpty()) {
        return null
    }

    val safeIndex = index.coerceIn(0, text.length - 1)
    val spans = text.getSpans(safeIndex, safeIndex, URLSpan::class.java)
    return spans.firstOrNull()?.url
}

private fun updateOpenLinkMenu(menu: Menu, editText: EditText) {
    menu.removeItem(OpenLinkMenuItemId)
    val selectedUrl = findSelectedUrl(editText) ?: return
    menu.add(Menu.NONE, OpenLinkMenuItemId, Menu.NONE, "Open link")
        .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
}

internal fun EditText.configureNoteTextBase(
    textColor: Int,
    hintColor: Int,
    linkColor: Int,
    fontScale: Float,
) {
    setBackgroundColor(Color.TRANSPARENT)
    updateNoteTextAppearance(textColor, hintColor, linkColor, fontScale)
    hint = "Start writing"
    // The scroll container fills short documents; do not force a synthetic
    // 12-line document that would overflow a small keyboard-visible viewport.
    minLines = 1
    setSingleLine(false)
    maxLines = Int.MAX_VALUE
    gravity = Gravity.TOP or Gravity.START
    // The viewport draws through the gesture-navigation area. Its Compose
    // parent handles IME padding; do not reserve navigation-bar space here.
    setPadding(0, 0, 0, 0)
    includeFontPadding = false
    setLineSpacing(0f, NoteBodyLineSpacingMultiplier)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
    }
    overScrollMode = TextView.OVER_SCROLL_NEVER
    isVerticalScrollBarEnabled = false
    setHorizontallyScrolling(false)
}

/** Appearance-only updates: keep Android's selection, handles, and layout alive. */
internal fun EditText.updateNoteTextAppearance(
    textColor: Int,
    hintColor: Int,
    linkColor: Int,
    fontScale: Float,
) {
    if (currentTextColor != textColor) setTextColor(textColor)
    if (currentHintTextColor != hintColor) setHintTextColor(hintColor)
    if (linkTextColors.defaultColor != linkColor) setLinkTextColor(linkColor)
    val pixels = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_SP,
        NoteBodyTextSizeSp * fontScale,
        resources.displayMetrics,
    )
    if (textSize != pixels) setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, pixels)
}

private fun TextView.configureReaderTextBase(
    textColor: Int,
    hintColor: Int,
    linkColor: Int,
    fontScale: Float,
) {
    setBackgroundColor(Color.TRANSPARENT)
    setTextColor(textColor)
    setHintTextColor(hintColor)
    setLinkTextColor(linkColor)
    textSize = NoteBodyTextSizeSp * fontScale
    gravity = Gravity.TOP or Gravity.START
    setPadding(0, 0, 0, 0)
    includeFontPadding = false
    setLineSpacing(0f, NoteBodyLineSpacingMultiplier)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
    }
    setHorizontallyScrolling(false)
}

internal fun bringSelectionIntoView(editText: EditText) {
    // Only assist a caret. Android owns both ends, handle positions, and
    // autoscrolling for a range selection; forcing its end into view interferes
    // with word selection and handle dragging, especially during IME resizing.
    if (editText.selectionStart != editText.selectionEnd) return
    if (editText.layout == null) return
    val textLength = editText.text?.length ?: 0
    val safeOffset = if (textLength == 0) {
        0
    } else {
        editText.selectionEnd.takeIf { it >= 0 }?.coerceIn(0, textLength) ?: textLength
    }
    editText.bringPointIntoView(safeOffset)
}

private fun shouldRefreshLinks(
    editable: Editable,
    changeStart: Int,
    replacedCount: Int,
    insertedCount: Int,
): Boolean {
    if (replacedCount > 0) {
        return hasUrlSpanNear(editable, changeStart)
    }

    if (insertedCount <= 0) {
        return false
    }

    if (insertedCount > 1) {
        return true
    }

    val insertedEnd = (changeStart + insertedCount).coerceAtMost(editable.length)
    val insertedText = editable.subSequence(changeStart, insertedEnd).toString()
    return insertedText.any(::isLinkCompletionCharacter)
}

private fun hasUrlSpanNear(editable: Editable, changeStart: Int): Boolean {
    if (editable.isEmpty()) {
        return false
    }

    val leftIndex = (changeStart - 1).coerceAtLeast(0)
    val rightIndex = changeStart.coerceAtMost(editable.length - 1)
    return editable.getSpans(leftIndex, leftIndex, URLSpan::class.java).isNotEmpty() ||
        editable.getSpans(rightIndex, rightIndex, URLSpan::class.java).isNotEmpty()
}

private fun isLinkCompletionCharacter(character: Char): Boolean {
    return character.isWhitespace() || character in listOf(',', ';', '!', '?', ')', ']', '}', '"', '\'')
}

internal class NoteEditText(context: android.content.Context) : EditText(context) {
    var onBackToReading: (() -> Unit)? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var touchDownY = 0f
    private var userIsDragging = false
    private var readingTouch = false

    // Older Android versions deliver Back before the IME consumes it. On
    // newer gesture-navigation versions the screen observes IME dismissal.
    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && hasFocus() && onBackToReading != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                keyDispatcherState?.startTracking(event, this)
                return true
            }
            if (event.action == KeyEvent.ACTION_UP) {
                keyDispatcherState?.handleUpEvent(event)
                if (event.isTracking && !event.isCanceled) {
                    onBackToReading?.invoke()
                    return true
                }
            }
        }
        return super.onKeyPreIme(keyCode, event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownY = event.y
                userIsDragging = false
                readingTouch = !hasFocus()
            }

            MotionEvent.ACTION_MOVE -> {
                if (!userIsDragging && kotlin.math.abs(event.y - touchDownY) > touchSlop) {
                    userIsDragging = true
                }
            }
        }

        // A reading-mode scroll must not focus EditText on ACTION_DOWN.
        // ScrollView intercepts drags; only a finished tap resumes editing.
        if (readingTouch) {
            when (event.actionMasked) {
                MotionEvent.ACTION_UP -> {
                    readingTouch = false
                    if (userIsDragging) return true
                    requestFocus()
                    isCursorVisible = true
                    val down = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_DOWN }
                    super.onTouchEvent(down)
                    down.recycle()
                }
                MotionEvent.ACTION_CANCEL -> { readingTouch = false; return true }
                else -> return true
            }
        }

        val handled = super.onTouchEvent(event)

        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            val shouldAdjustSelection = !userIsDragging && hasFocus()
            userIsDragging = false
            if (shouldAdjustSelection) {
                post { bringSelectionIntoView(this) }
            }
        }

        return handled
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h != oldh && hasFocus()) {
            post { bringSelectionIntoView(this) }
        }
    }

}

private class SimpleTextWatcher(
    private val afterTextChanged: (Editable?) -> Unit,
) : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

    override fun afterTextChanged(s: Editable?) {
        afterTextChanged.invoke(s)
    }
}
