package com.plainnotes.android.data

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

private val Context.dataStore by preferencesDataStore(name = "plain_notes_settings")

class AppSettingsRepository(private val context: Context) {
    private val rootFolderKey = stringPreferencesKey("root_folder_uri")
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val fontScaleKey = stringPreferencesKey("font_scale")
    private val noteSortModeKey = stringPreferencesKey("note_sort_mode")
    private val doubleXEnabledKey = booleanPreferencesKey("double_x_enabled")
    private val showReaderKey = booleanPreferencesKey("show_e_reader")
    private val bookFoldersKey = stringPreferencesKey("book_folder_uris")

    val rootFolderUri: Flow<Uri?> = context.dataStore.data.map { preferences ->
        preferences[rootFolderKey]?.let(Uri::parse)
    }.distinctUntilChanged()

    val themeMode: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[themeModeKey] ?: "dark_1"
    }.distinctUntilChanged()

    val fontScale: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[fontScaleKey]?.toFloatOrNull() ?: 1.0f
    }.distinctUntilChanged()

    val noteSortMode: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[noteSortModeKey] ?: "modified"
    }.distinctUntilChanged()

    val doubleXEnabled: Flow<Boolean> = context.dataStore.data.map { it[doubleXEnabledKey] ?: false }.distinctUntilChanged()
    val showReader: Flow<Boolean> = context.dataStore.data.map { it[showReaderKey] ?: true }.distinctUntilChanged()
    val bookFolders: Flow<List<Uri>> = context.dataStore.data.map { prefs ->
        prefs[bookFoldersKey]?.split('\n')?.filter(String::isNotBlank)?.map(Uri::parse).orEmpty()
    }.distinctUntilChanged()

    suspend fun setDoubleXEnabled(enabled: Boolean) { context.dataStore.edit { it[doubleXEnabledKey] = enabled } }
    suspend fun setShowReader(enabled: Boolean) { context.dataStore.edit { it[showReaderKey] = enabled } }
    suspend fun addBookFolder(uri: Uri) { context.dataStore.edit { prefs ->
        val folders = prefs[bookFoldersKey]?.split('\n')?.filter(String::isNotBlank).orEmpty()
        prefs[bookFoldersKey] = (folders + uri.toString()).distinct().joinToString("\n")
    } }
    suspend fun removeBookFolder(uri: Uri) { context.dataStore.edit { prefs ->
        prefs[bookFoldersKey] = prefs[bookFoldersKey]?.split('\n')?.filter { it.isNotBlank() && it != uri.toString() }?.joinToString("\n").orEmpty()
    } }

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
