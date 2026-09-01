package com.example.awake.data.repository

import android.content.Context

/**
 * 记录「JSON 分享导入」产生的课表及同步确认状态。
 * 分享课表保存的是别人的课程，刷新会替换为当前账号的教务课程，
 * 因此首次同步前需要用户确认；确认后不再重复提示。
 * 数据仅存本机 SharedPreferences，不包含任何课表内容。
 */
class JsonTimetableStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun markJsonImported(timetableId: Long) {
        val ids = prefs.getStringSet(KEY_JSON_IDS, emptySet()).orEmpty().toMutableSet()
        if (ids.add(timetableId.toString())) {
            prefs.edit().putStringSet(KEY_JSON_IDS, ids).apply()
        }
    }

    fun isJsonImported(timetableId: Long): Boolean =
        timetableId.toString() in prefs.getStringSet(KEY_JSON_IDS, emptySet()).orEmpty()

    fun confirmSync(timetableId: Long) {
        val ids = prefs.getStringSet(KEY_CONFIRMED, emptySet()).orEmpty().toMutableSet()
        if (ids.add(timetableId.toString())) {
            prefs.edit().putStringSet(KEY_CONFIRMED, ids).apply()
        }
    }

    fun isSyncConfirmed(timetableId: Long): Boolean =
        timetableId.toString() in prefs.getStringSet(KEY_CONFIRMED, emptySet()).orEmpty()

    private companion object {
        const val PREFS_NAME = "awake_json_timetables"
        const val KEY_JSON_IDS = "json_imported_ids"
        const val KEY_CONFIRMED = "sync_confirmed_ids"
    }
}