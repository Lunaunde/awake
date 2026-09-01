package com.example.awake.data.export

import org.json.JSONArray
import org.json.JSONObject

/**
 * 课表 JSON 分享/导入格式（type = "awake-timetable", version = 1）。
 *
 * 结构：
 * {
 *   "type": "awake-timetable",
 *   "version": 1,
 *   "timetable": { "label": "...", "xnm": 2026, "xqm": "3", "startDate": "yyyy-MM-dd", "totalWeeks": 16 },
 *   "courses": [
 *     {
 *       "source": "SCUT_KB|SCUT_SJK|MANUAL|MIGRATED_LEGACY",
 *       "name": "课程名", "teacher": "主教师", "className": "教学班", "color": -12165016,
 *       "credits": "4", "totalHours": "64", "courseType": "必修", "assessment": "考试",
 *       "sections": [ { "dayOfWeek": 1, "startPeriod": 1, "endPeriod": 2, "room": "...", "teacher": "时段教师", "rawWeekText": "1-16" } ]
 *     }
 *   ]
 * }
 *
 * 分享文本含课表与个人信息，仅由用户主动分享；不写入任何日志。
 */
object TimetableJson {
    const val TYPE = "awake-timetable"
    const val VERSION = 1

    data class JsonTimetableMeta(
        val label: String,
        val xnm: Int,
        val xqm: String,
        val startDate: String?,
        val totalWeeks: Int
    )

    data class JsonSection(
        val dayOfWeek: Int,
        val startPeriod: Int,
        val endPeriod: Int,
        val room: String,
        val teacher: String,
        val rawWeekText: String
    )

    data class JsonCourse(
        val source: String,
        val name: String,
        val teacher: String,
        val color: Int?,
        val credits: String?,
        val totalHours: String?,
        val courseType: String?,
        val assessment: String?,
        val className: String?,
        val sections: List<JsonSection>
    )

    data class JsonTimetableData(
        val meta: JsonTimetableMeta,
        val courses: List<JsonCourse>
    )

    /** 解析分享文本；格式非法时抛 IllegalArgumentException，message 可直接展示。 */
    fun parse(raw: String): JsonTimetableData {
        val root = try {
            JSONObject(raw)
        } catch (error: Exception) {
            throw IllegalArgumentException("不是有效的课表 JSON 文本")
        }
        if (root.optString("type") != TYPE) {
            throw IllegalArgumentException("缺少 Awake 课表标识（type），可能不是本应用生成的分享文本")
        }
        val table = root.optJSONObject("timetable") ?: throw IllegalArgumentException("缺少 timetable 字段")
        val label = table.optString("label").trim()
        if (label.isBlank()) throw IllegalArgumentException("课表名称为空")
        val xnm = table.optInt("xnm", 0)
        val xqm = table.optString("xqm").ifBlank { "manual" }
        val startDate = if (table.has("startDate") && !table.isNull("startDate")) table.optString("startDate") else null
        val totalWeeks = table.optInt("totalWeeks", 30).coerceIn(1, 60)

        val coursesJson = root.optJSONArray("courses") ?: throw IllegalArgumentException("缺少 courses 字段")
        val courses = mutableListOf<JsonCourse>()
        for (i in 0 until coursesJson.length()) {
            val course = coursesJson.optJSONObject(i) ?: continue
            val name = course.optString("name").trim()
            if (name.isBlank()) throw IllegalArgumentException("第 ${i + 1} 门课程缺少名称")
            val sectionsJson = course.optJSONArray("sections")
            if (sectionsJson == null || sectionsJson.length() == 0) {
                throw IllegalArgumentException("课程“$name”没有任何时间段")
            }
            val sections = mutableListOf<JsonSection>()
            for (j in 0 until sectionsJson.length()) {
                val section = sectionsJson.optJSONObject(j) ?: continue
                val day = section.optInt("dayOfWeek", 0)
                if (day !in 1..7) throw IllegalArgumentException("课程“$name”的星期无效（$day）")
                val start = section.optInt("startPeriod", 0)
                val end = section.optInt("endPeriod", start)
                if (start !in 1..30 || end !in 1..30 || end < start) {
                    throw IllegalArgumentException("课程“$name”的节次无效（$start-$end）")
                }
                if (section.optString("rawWeekText").isBlank()) {
                    throw IllegalArgumentException("课程“$name”缺少周次")
                }
                sections += JsonSection(
                    dayOfWeek = day,
                    startPeriod = start,
                    endPeriod = end,
                    room = section.optString("room"),
                    teacher = section.optString("teacher"),
                    rawWeekText = section.optString("rawWeekText")
                )
            }
            courses += JsonCourse(
                source = course.optString("source").ifBlank { "MANUAL" },
                name = name,
                teacher = course.optString("teacher"),
                color = if (course.has("color") && !course.isNull("color")) course.optInt("color") else null,
                credits = if (course.has("credits") && !course.isNull("credits")) course.optString("credits") else null,
                totalHours = if (course.has("totalHours") && !course.isNull("totalHours")) course.optString("totalHours") else null,
                courseType = if (course.has("courseType") && !course.isNull("courseType")) course.optString("courseType") else null,
                assessment = if (course.has("assessment") && !course.isNull("assessment")) course.optString("assessment") else null,
                className = if (course.has("className") && !course.isNull("className")) course.optString("className") else null,
                sections = sections
            )
        }
        if (courses.isEmpty()) throw IllegalArgumentException("课表中没有任何课程")
        return JsonTimetableData(
            meta = JsonTimetableMeta(label, xnm, xqm, startDate, totalWeeks),
            courses = courses
        )
    }

    private fun sectionJson(section: JsonSection) = JSONObject().apply {
        put("dayOfWeek", section.dayOfWeek)
        put("startPeriod", section.startPeriod)
        put("endPeriod", section.endPeriod)
        put("room", section.room)
        put("teacher", section.teacher)
        put("rawWeekText", section.rawWeekText)
    }

    private fun courseJson(course: JsonCourse) = JSONObject().apply {
        put("source", course.source)
        put("name", course.name)
        put("teacher", course.teacher)
        course.color?.let { put("color", it) } ?: put("color", JSONObject.NULL)
        put("credits", course.credits ?: JSONObject.NULL)
        put("totalHours", course.totalHours ?: JSONObject.NULL)
        put("courseType", course.courseType ?: JSONObject.NULL)
        put("assessment", course.assessment ?: JSONObject.NULL)
        put("className", course.className ?: JSONObject.NULL)
        put("sections", JSONArray().apply { course.sections.forEach { put(sectionJson(it)) } })
    }

    fun toString(meta: JsonTimetableMeta, courses: List<JsonCourse>): String {
        val table = JSONObject().apply {
            put("label", meta.label)
            put("xnm", meta.xnm)
            put("xqm", meta.xqm)
            put("startDate", meta.startDate ?: JSONObject.NULL)
            put("totalWeeks", meta.totalWeeks)
        }
        val root = JSONObject().apply {
            put("type", TYPE)
            put("version", VERSION)
            put("timetable", table)
            put("courses", JSONArray().apply { courses.forEach { put(courseJson(it)) } })
        }
        return root.toString(2)
    }
}