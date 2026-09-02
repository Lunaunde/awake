package com.example.awake.data.repository

import android.content.Context
import kotlin.math.abs

/** 课前提醒偏好，仅保存本机设置，不包含账号、Cookie 或课表内容。 */
data class ReminderSettings(
    val enabled: Boolean = false,
    val minutesBefore: Int = 15
)

class ReminderSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("awake_reminder_settings", Context.MODE_PRIVATE)

    fun read(): ReminderSettings = ReminderSettings(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        minutesBefore = preferences.getInt(KEY_MINUTES, 15).let { value ->
            ALLOWED_MINUTES.minByOrNull { abs(it - value) } ?: 15
        }
    )

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setMinutesBefore(minutesBefore: Int) {
        if (minutesBefore in ALLOWED_MINUTES) {
            preferences.edit().putInt(KEY_MINUTES, minutesBefore).apply()
        }
    }

    companion object {
        val ALLOWED_MINUTES = setOf(5, 10, 15)
        private const val KEY_ENABLED = "enabled"
        private const val KEY_MINUTES = "minutes_before"
    }
}
