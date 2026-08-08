package com.plainnotes.android.data

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "plain_notes_settings")

class AppSettingsRepository(private val context: Context) {
    private val rootFolderKey = stringPreferencesKey("root_folder_uri")
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val fontScaleKey = stringPreferencesKey("font_scale")
    private val noteSortModeKey = stringPreferencesKey("note_sort_mode")

    val rootFolderUri: Flow<Uri?> = context.dataStore.data.map { preferences ->
        preferences[rootFolderKey]?.let(Uri::parse)
    }

    val themeMode: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[themeModeKey] ?: "dark_1"
    }

    val fontScale: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[fontScaleKey]?.toFloatOrNull() ?: 1.0f
    }

    val noteSortMode: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[noteSortModeKey] ?: "modified"
    }

    suspend fun setRootFolderUri(uri: Uri) {
        context.dataStore.edit { preferences ->
            preferences[rootFolderKey] = uri.toString()
        }
    }

    suspend fun setThemeMode(themeMode: String) {
        context.dataStore.edit { preferences ->
            preferences[themeModeKey] = themeMode
        }
    }

    suspend fun setFontScale(scale: Float) {
        context.dataStore.edit { preferences ->
            preferences[fontScaleKey] = scale.toString()
        }
    }

    suspend fun setNoteSortMode(sortMode: String) {
        context.dataStore.edit { preferences ->
            preferences[noteSortModeKey] = sortMode
        }
    }
}
