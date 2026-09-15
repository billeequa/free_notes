package com.plainnotes.android.ui.components

import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Editable
import android.text.InputType
import android.text.Layout
import android.text.TextWatcher
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.ActionMode
import android.view.GestureDetector
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.util.LinkifyCompat

private const val OpenLinkMenuItemId = 0x706c6169
private const val NoteBodyTextSizeSp = 18f
private const val NoteBodyLineSpacingMultiplier = 1.15f

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
    onRequestedSelectionHandled: () -> Unit,
    initialScrollY: Int,
    onSelectionChanged: (Int) -> Unit,
    onScrollChanged: (Int) -> Unit,
    fontScale: Float,
    modifier: Modifier = Modifier,
) {
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnUrlTapped by rememberUpdatedState(onUrlTapped)
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
                onSelectionChangedCallback = onSelectionChanged
                onScrollChangedCallback = onScrollChanged
                isFocusable = true
                isFocusableInTouchMode = true
                isCursorVisible = true
                showSoftInputOnFocus = true
                imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                post { scrollTo(0, initialScrollY) }
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
                            currentOnUrlTapped(selectedUrl)
                            mode.finish()
                            return true
                        }

                        override fun onDestroyActionMode(mode: ActionMode) = Unit
                    },
                )
                var focusedBeforeDown = false
                val gestureDetector = GestureDetector(
                    context,
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                            val tappedUrl = findUrlAtOffset(this@apply, event)
                            if (!focusedBeforeDown && tappedUrl != null) {
                                currentOnUrlTapped(tappedUrl)
                            }
                            return false
                        }
                    },
                )
                setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        focusedBeforeDown = hasFocus()
                    }
                    gestureDetector.onTouchEvent(event)
                    false
                }
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

                            currentOnValueChange(editable.toString())
                            removeCallbacks(applyLinksRunnable)
                            post {
                                bringSelectionIntoView(this@apply)
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
            }
        },
        update = { editText ->
            editText.configureNoteTextBase(
                textColor = textColor,
                hintColor = hintColor,
                linkColor = linkColor,
                fontScale = fontScale,
            )
            editText.onSelectionChangedCallback = onSelectionChanged
            editText.onScrollChangedCallback = onScrollChanged
            syncLinkifiedText(editText, value)
            if (shouldRequestFocus) {
                val targetSelection = requestedSelection
                    ?.coerceIn(0, editText.text?.length ?: 0)
                    ?: (editText.text?.length ?: 0)
                editText.post {
                    editText.requestFocus()
                    editText.setSelection(targetSelection)
                    editText.context.getSystemService(InputMethodManager::class.java)
                        ?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
                    bringSelectionIntoView(editText)
                }
                onRequestedSelectionHandled()
                onFocusHandled()
            }
        },
    )
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

private fun EditText.configureNoteTextBase(
    textColor: Int,
    hintColor: Int,
    linkColor: Int,
    fontScale: Float,
) {
    setBackgroundColor(Color.TRANSPARENT)
    setTextColor(textColor)
    setHintTextColor(hintColor)
    setLinkTextColor(linkColor)
    hint = "Start writing"
    textSize = NoteBodyTextSizeSp * fontScale
    minLines = 1
    setSingleLine(false)
    maxLines = Int.MAX_VALUE
    gravity = Gravity.TOP or Gravity.START
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

private fun bringSelectionIntoView(editText: EditText) {
    val layout = editText.layout ?: return
    val textLength = editText.text?.length ?: 0
    val safeOffset = if (textLength == 0) {
        0
    } else {
        editText.selectionEnd.takeIf { it >= 0 }?.coerceIn(0, textLength - 1) ?: (textLength - 1)
    }
    val line = layout.getLineForOffset(safeOffset)
    val rect = Rect()
    layout.getLineBounds(line, rect)
    rect.offset(editText.totalPaddingLeft, editText.totalPaddingTop)
    editText.requestRectangleOnScreen(rect, false)
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

private class NoteEditText(context: android.content.Context) : EditText(context) {
    var onSelectionChangedCallback: ((Int) -> Unit)? = null
    var onScrollChangedCallback: ((Int) -> Unit)? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var touchDownY = 0f
    private var userIsDragging = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownY = event.y
                userIsDragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!userIsDragging && kotlin.math.abs(event.y - touchDownY) > touchSlop) {
                    userIsDragging = true
                }
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

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onSelectionChangedCallback?.invoke(selEnd)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onScrollChangedCallback?.invoke(t)
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

