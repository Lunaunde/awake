package com.example.awake.data.repository

import android.content.Context
import com.example.awake.ui.theme.ThemeMode

/** 外观模式偏好持久化（SharedPreferences，很小，不依赖 Room）。 */
class ThemeModeStore(context: Context) {
    private val prefs = context.getSharedPreferences("awake_theme", Context.MODE_PRIVATE)

    fun read(): ThemeMode =
        ThemeMode.entries.firstOrNull { it.key == prefs.getString(KEY, ThemeMode.SYSTEM.key) }
            ?: ThemeMode.SYSTEM

    fun write(mode: ThemeMode) {
        prefs.edit().putString(KEY, mode.key).apply()
    }

    companion object {
        private const val KEY = "theme_mode"
    }
}