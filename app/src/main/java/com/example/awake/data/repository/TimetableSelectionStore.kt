package com.example.awake.data.repository

import android.content.Context

/** 当前课表选择持久化，便于重启后恢复提醒和小组件数据源。 */
class TimetableSelectionStore(context: Context) {
    private val preferences = context.getSharedPreferences("awake_timetable_selection", Context.MODE_PRIVATE)

    fun read(): Long? = preferences.getLong(KEY_SELECTED_ID, -1L).takeIf { it > 0L }

    fun setSelected(id: Long) {
        if (id > 0L) preferences.edit().putLong(KEY_SELECTED_ID, id).apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_SELECTED_ID).apply()
    }

    private companion object {
        const val KEY_SELECTED_ID = "selected_timetable_id"
    }
}
