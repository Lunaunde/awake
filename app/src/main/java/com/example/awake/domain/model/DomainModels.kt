package com.example.awake.domain.model

/** 仅描述学校，不保存密码、Cookie 或 ticket。 */
enum class SchoolCode(val code: String, val displayName: String) {
    SCUT("SCUT", "华南理工大学")
}

enum class CourseSource { SCUT_KB, SCUT_SJK, MANUAL, MIGRATED_LEGACY }

data class Profile(
    val id: Long,
    val school: SchoolCode,
    val maskedStudentId: String?,
    val displayName: String?,
    val lastLoginAt: Long?
)

data class Timetable(
    val id: Long,
    val profileId: Long,
    val school: SchoolCode,
    val xnm: Int,
    val xqm: String,
    val label: String,
    val startDate: String?,
    val totalWeeks: Int,
    val lastSyncedAt: Long?
)

data class Course(
    val id: Long,
    val timetableId: Long,
    val source: CourseSource,
    val remoteKey: String,
    val name: String,
    val teacher: String,
    val room: String,
    val dayOfWeek: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val credits: String?,
    val totalHours: String?,
    val courseType: String?,
    val assessment: String?,
    val className: String?,
    val color: Int,
    val rawWeekText: String
)

data class CourseOccurrence(val courseId: Long, val weekNumber: Int)

data class PeriodConfig(
    val period: Int,
    val startTime: String,
    val endTime: String
)

data class ParseWarning(val input: String, val message: String)

data class WeekParseResult(val weeks: Set<Int>, val warning: ParseWarning? = null)
data class PeriodParseResult(val start: Int, val end: Int, val warning: ParseWarning? = null)
