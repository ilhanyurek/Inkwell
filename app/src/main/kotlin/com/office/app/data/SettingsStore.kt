package com.office.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    /** Multiplier applied to editor text, 0.8–1.6. */
    val editorTextScale: Float = 1f,
)

private val Context.settingsStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private val themeKey = stringPreferencesKey("theme")
    private val dynamicKey = booleanPreferencesKey("dynamic_color")
    private val scaleKey = floatPreferencesKey("editor_scale")

    val settings: Flow<Settings> = context.settingsStore.data.map { p ->
        Settings(
            theme = p[themeKey]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            dynamicColor = p[dynamicKey] ?: false,
            editorTextScale = p[scaleKey] ?: 1f,
        )
    }

    suspend fun setTheme(mode: ThemeMode) = context.settingsStore.edit { it[themeKey] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = context.settingsStore.edit { it[dynamicKey] = enabled }
    suspend fun setEditorTextScale(scale: Float) = context.settingsStore.edit { it[scaleKey] = scale.coerceIn(0.8f, 1.6f) }
}
