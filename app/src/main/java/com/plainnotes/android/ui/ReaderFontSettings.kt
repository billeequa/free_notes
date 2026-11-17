package com.plainnotes.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.plainnotes.android.data.AppSettingsRepository
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val ReaderFontSizes = listOf(75, 90, 100, 110, 125, 150, 175, 200)

@Composable
fun ReaderFontSizeSetting() {
    val context = LocalContext.current
    val settings = remember { AppSettingsRepository(context.applicationContext) }
    val scale by settings.readerFontScale.collectAsStateWithLifecycle(1f)
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    Text("Reader font size", style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ReaderFontSizes.forEach { percent ->
            FilterChip(selected = (scale * 100).roundToInt() == percent,
                onClick = { scope.launch {
                    try { settings.setReaderFontScale(percent / 100f); error = null }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { error = "Could not save font size. Try again." }
                } }, label = { Text("$percent%") })
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
fun ReaderFontSizeDialog(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Reader font size") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Applies to book text. App text size is separate.", style = MaterialTheme.typography.bodySmall)
                ReaderFontSizeSetting()
            }
        }, confirmButton = { FilledTonalButton(onClick = onDismiss) { Text("Done") } })
}
