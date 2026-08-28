package com.example.awake.data.remote

import org.json.JSONArray
import org.json.JSONObject

/** 对字段缺失保持容错；原始响应只在内存中解析，不落盘。 */
data class ScutStudentDto(val studentId: String?, val name: String?)
data class ScutCourseDto(
    val source: String,
    val name: String,
    val teacher: String,
    val room: String,
    val day: Int,
    val dayName: String,
    val periods: String,
    val weeks: String,
    val credits: String?,
    val hours: String?,
    val courseType: String?,
    val assessment: String?,
    val className: String?
)
data class ScutSchedulePayload(val student: ScutStudentDto?, val courses: List<ScutCourseDto>) {
    companion object {
        fun fromJson(raw: String): ScutSchedulePayload {
            val root = JSONObject(raw)
            val info = root.optJSONObject("xsxx")
            val student = info?.let { ScutStudentDto(it.string("xh"), it.string("xm")) }
            val courses = mutableListOf<ScutCourseDto>()
            readArray(root.optJSONArray("kbList"), "SCUT_KB", courses)
            readArray(root.optJSONArray("sjkList"), "SCUT_SJK", courses)
            return ScutSchedulePayload(student, courses)
        }

        private fun readArray(array: JSONArray?, source: String, output: MutableList<ScutCourseDto>) {
            if (array == null) return
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                output += ScutCourseDto(
                    source = source,
                    name = obj.string("kcmc") ?: obj.string("kcmc_display") ?: "未命名课程",
                    teacher = obj.string("xm").orEmpty(),
                    room = obj.string("cdmc").orEmpty(),
                    day = obj.string("xqj")?.toIntOrNull() ?: dayFromName(obj.string("xqjmc")),
                    dayName = obj.string("xqjmc").orEmpty(),
                    periods = obj.string("jcs").orEmpty(),
                    weeks = obj.string("zcd").orEmpty(),
                    credits = obj.string("xf"),
                    hours = obj.string("zxs"),
                    courseType = obj.string("kclbmc"),
                    assessment = obj.string("khfsmc"),
                    className = obj.string("jxbmc")
                )
            }
        }

        private fun JSONObject.string(key: String): String? = if (has(key) && !isNull(key)) opt(key)?.toString()?.trim() else null
        private fun dayFromName(name: String?): Int = when {
            name?.contains("一") == true -> 1
            name?.contains("二") == true -> 2
            name?.contains("三") == true -> 3
            name?.contains("四") == true -> 4
            name?.contains("五") == true -> 5
            name?.contains("六") == true -> 6
            name?.contains("日") == true || name?.contains("天") == true -> 7
            else -> 1
        }
    }
}
