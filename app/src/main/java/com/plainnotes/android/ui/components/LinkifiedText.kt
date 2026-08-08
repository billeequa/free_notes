package com.plainnotes.android.ui.components

import android.graphics.Color
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.util.LinkifyCompat

@Composable
fun LinkifiedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle(fontSize = 16.sp),
    textColor: Int = Color.BLACK,
    linkColor: Int,
    maxLines: Int = Int.MAX_VALUE,
) {
    val context = LocalContext.current
    AndroidView(
        modifier = modifier,
        factory = {
            TextView(context).apply {
                movementMethod = LinkMovementMethod.getInstance()
                linksClickable = true
                setPadding(0, 0, 0, 0)
            }
        },
        update = { textView ->
            textView.text = text
            textView.textSize = if (style.fontSize.value.isNaN()) 16f else style.fontSize.value
            textView.setTextColor(textColor)
            textView.setLinkTextColor(linkColor)
            textView.maxLines = maxLines
            textView.ellipsize = TextUtils.TruncateAt.END
            LinkifyCompat.addLinks(textView, Linkify.WEB_URLS)
        },
    )
}
