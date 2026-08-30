package com.example.awake.data.mapper

import com.example.awake.data.local.CourseEntity
import com.example.awake.data.local.CourseWeekEntity
import com.example.awake.data.remote.ScutCourseDto
import com.example.awake.data.remote.ScutSchedulePayload
import com.example.awake.domain.model.ParseWarning
import com.example.awake.domain.parser.PeriodExpressionParser
import com.example.awake.domain.parser.WeekExpressionParser
import java.security.MessageDigest

data class MappedSchedule(
    val courses: List<CourseEntity>,
    val weeks: List<CourseWeekEntity>,
    val warnings: List<ParseWarning>,
    val studentId: String?,
    val studentName: String?
)

class ScutScheduleMapper {
    fun map(payload: ScutSchedulePayload, timetableId: Long, maxWeek: Int = 30): MappedSchedule {
        val courses = mutableListOf<CourseEntity>()
        val weeks = mutableListOf<CourseWeekEntity>()
        val warnings = mutableListOf<ParseWarning>()
        payload.courses.forEach { dto ->
            val period = PeriodExpressionParser.parse(dto.periods)
            val parsedWeeks = WeekExpressionParser.parse(dto.weeks, maxWeek)
            period.warning?.let(warnings::add)
            parsedWeeks.warning?.let(warnings::add)

            if (dto.name.isBlank()) {
                warnings += ParseWarning(dto.source, "课程名称为空，已跳过课程")
                return@forEach
            }
            if (dto.day !in 1..7) {
                warnings += ParseWarning(dto.dayName.ifBlank { dto.day.toString() }, "星期无效，已跳过课程")
                return@forEach
            }
            if (period.warning != null || parsedWeeks.weeks.isEmpty()) return@forEach

            // CourseWeekEntity.courseId 暂存课程列表下标，由本地仓储在插入事务中重映射为真实 ID。
            val index = courses.size
            val entity = CourseEntity(
                timetableId = timetableId,
                source = dto.source,
                remoteKey = dto.remoteKey(),
                name = dto.name,
                teacher = dto.teacher,
                room = dto.room,
                dayOfWeek = dto.day,
                startPeriod = period.start,
                endPeriod = period.end,
                credits = dto.credits,
                totalHours = dto.hours,
                courseType = dto.courseType,
                assessment = dto.assessment,
                className = dto.className,
                color = colorFor(index),
                rawWeekText = dto.weeks
            )
            courses += entity
            parsedWeeks.weeks.forEach { weeks += CourseWeekEntity(index.toLong(), it) }
        }
        return MappedSchedule(courses, weeks, warnings, payload.student?.studentId, payload.student?.name)
    }

    private fun ScutCourseDto.remoteKey(): String =
        sha256(listOf(source, name, teacher, room, day, periods, weeks, className).joinToString("|"))

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun colorFor(index: Int): Int =
        listOf(0xff4f6bed, 0xff0f9d78, 0xffd97706, 0xffc24170, 0xff7c3aed)[index % 5].toInt()
}
