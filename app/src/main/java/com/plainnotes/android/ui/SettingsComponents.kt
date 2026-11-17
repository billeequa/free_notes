package com.plainnotes.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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
internal fun SettingsChoice(label: String, options: List<Pair<String, Boolean>>, onSelected: (String) -> Unit) {
    Text(label, style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { (name, selected) ->
            FilterChip(selected = selected, onClick = { onSelected(name) }, label = { Text(name) })
        }
    }
}

@Composable
internal fun ThemeChoices(selected: ThemeMode, onSelected: (ThemeMode) -> Unit) {
    Text("Theme", style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ThemeMode.entries.forEach { theme ->
            val palette = com.plainnotes.android.ui.theme.plainNotesColorScheme(theme)
            FilterChip(selected = selected == theme, onClick = { onSelected(theme) },
                leadingIcon = {
                    Surface(shape = RoundedCornerShape(50), color = palette.background,
                        border = BorderStroke(1.dp, palette.onSurface.copy(alpha = 0.4f))) {
                        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Surface(Modifier.size(10.dp), shape = RoundedCornerShape(50), color = palette.primary) {}
                            Surface(Modifier.size(10.dp), shape = RoundedCornerShape(50), color = palette.onSurface) {}
                        }
                    }
                }, label = { Text(theme.label) })
        }
    }
}
