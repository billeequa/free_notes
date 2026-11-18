package com.plainnotes.android.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.plainnotes.android.data.AppSettingsRepository
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val ReaderFontSizes = listOf(75, 90, 100, 110, 125, 150, 175, 200)
    .map { "$it%" to it / 100f }

@Composable
fun ReaderFontSizeSetting() {
    val context = LocalContext.current
    val settings = remember { AppSettingsRepository(context.applicationContext) }
    val scale by settings.readerFontScale.collectAsStateWithLifecycle(1f)
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    FontSizeChoices("Reader font size", ReaderFontSizes, (scale * 100).roundToInt() / 100f,
        previewBaseSize = 16.sp, previewFontFamily = FontFamily.Serif) { selected ->
        scope.launch {
            try { settings.setReaderFontScale(selected); error = null }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { error = "Could not save font size. Try again." }
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
fun ReaderFontSizeDialog(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Reader font size") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Applies to book text. App text size is separate.", style = MaterialTheme.typography.bodySmall)
                ReaderFontSizeSetting()
            }
        }, confirmButton = { FilledTonalButton(onClick = onDismiss) { Text("Done") } })
}
