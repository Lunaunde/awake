package com.example.awake.data.repository

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 课表显示偏好，设置页与课表页共享同一个进程内状态并持久化到本地。 */
class TimetableDisplaySettingsStore(context: Context) {
    companion object {
        private const val PREFS_NAME = "awake_timetable_display_settings"
        private const val KEY_SHOW_OTHER_WEEKS = "show_other_weeks"
        const val DEFAULT_SHOW_OTHER_WEEKS = true
    }

    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _showOtherWeeks = MutableStateFlow(
        preferences.getBoolean(KEY_SHOW_OTHER_WEEKS, DEFAULT_SHOW_OTHER_WEEKS)
    )
    val showOtherWeeks: StateFlow<Boolean> = _showOtherWeeks.asStateFlow()

    fun setShowOtherWeeks(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_SHOW_OTHER_WEEKS, enabled).apply()
        _showOtherWeeks.value = enabled
    }
}
