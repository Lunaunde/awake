package com.example.awake.data.widget

import android.content.Context

/**
 * 每个小组件实例的独立状态：绑定的课表 id 与当前查看的周次。
 * 多个组件互不影响；组件删除时清理对应记录。
 */
class AwakeWidgetPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("awake_widgets", Context.MODE_PRIVATE)

    /** 绑定课表 id；未绑定（跟随当前课表）时返回 null。 */
    fun timetableId(widgetId: Int): Long? =
        prefs.getLong(KEY_TABLE + widgetId, -1L).takeIf { it >= 0 }

    fun setTimetableId(widgetId: Int, timetableId: Long?) {
        if (timetableId == null) prefs.edit().remove(KEY_TABLE + widgetId).apply()
        else prefs.edit().putLong(KEY_TABLE + widgetId, timetableId).apply()
    }

    /** 当前周次；0 表示未设置（渲染时按课表起始日推算本周）。 */
    fun week(widgetId: Int): Int = prefs.getInt(KEY_WEEK + widgetId, 0)

    fun setWeek(widgetId: Int, week: Int) {
        prefs.edit().putInt(KEY_WEEK + widgetId, week.coerceIn(1, 30)).apply()
    }

    fun clear(widgetId: Int) {
        prefs.edit()
            .remove(KEY_TABLE + widgetId)
            .remove(KEY_WEEK + widgetId)
            .apply()
    }

    private companion object {
        const val KEY_TABLE = "widget_timetable_"
        const val KEY_WEEK = "widget_week_"
    }
}