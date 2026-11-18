package com.plainnotes.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.plainnotes.android.ui.theme.plainNotesChromeColors
import com.plainnotes.android.ui.theme.plainNotesColorScheme

@Composable
internal fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
internal fun FontSizeChoices(
    label: String,
    options: List<Pair<String, Float>>,
    selectedScale: Float,
    previewBaseSize: TextUnit,
    previewFontFamily: FontFamily = FontFamily.Default,
    onSelected: (Float) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { (name, scale) ->
            FilterChip(
                selected = selectedScale == scale,
                onClick = { onSelected(scale) },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Aa", Modifier.clearAndSetSemantics {}, fontSize = previewBaseSize * scale,
                            lineHeight = previewBaseSize * scale * 1.2f, fontFamily = previewFontFamily, maxLines = 1)
                        Text(name)
                    }
                },
            )
        }
    }
}

@Composable
internal fun ThemeChoices(selected: ThemeMode, onSelected: (ThemeMode) -> Unit) {
    Text("Theme", style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ThemeMode.entries.forEach { theme ->
            val palette = plainNotesColorScheme(theme)
            val chrome = plainNotesChromeColors(theme, palette)
            val fill = lerp(palette.background, chrome.topAppBarContainer, 0.45f)
            val active = selected == theme
            FilterChip(
                selected = active,
                onClick = { onSelected(theme) },
                shape = RoundedCornerShape(50),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = fill, selectedContainerColor = fill,
                    labelColor = palette.onBackground, selectedLabelColor = palette.onBackground,
                ),
                border = BorderStroke(if (active) 2.dp else 1.dp,
                    if (active) palette.onBackground else lerp(fill, palette.primary, 0.65f)),
                leadingIcon = {
                    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                        if (active) Icon(Icons.Rounded.Check, null, tint = palette.onBackground)
                    }
                },
                label = { Text(theme.label) },
            )
        }
    }
}
