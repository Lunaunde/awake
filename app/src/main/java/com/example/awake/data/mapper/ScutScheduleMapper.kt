package com.example.awake.data.mapper

import com.example.awake.data.local.CourseEntity
import com.example.awake.data.local.CourseSectionEntity
import com.example.awake.data.local.CourseWeekEntity
import com.example.awake.data.remote.ScutCourseDto
import com.example.awake.data.remote.ScutSchedulePayload
import com.example.awake.domain.model.CourseIdentity
import com.example.awake.domain.model.ParseWarning
import com.example.awake.domain.parser.PeriodExpressionParser
import com.example.awake.domain.parser.WeekExpressionParser

/**
 * 映射结果。courses 为去重后的课程主记录；sections.weekId 暂存其主记录在列表中的下标；
 * weeks.sectionId 暂存其时段在列表中的下标，插入事务内统一重映射为真实 ID。
 */
data class MappedSchedule(
    val courses: List<CourseEntity>,
    val sections: List<CourseSectionEntity>,
    val weeks: List<CourseWeekEntity>,
    val warnings: List<ParseWarning>,
    val studentId: String?,
    val studentName: String?
)

class ScutScheduleMapper {
    fun map(payload: ScutSchedulePayload, timetableId: Long, maxWeek: Int = 30): MappedSchedule {
        val masters = mutableListOf<CourseEntity>()
        val masterIndexByKey = LinkedHashMap<String, Int>()
        val sections = mutableListOf<CourseSectionEntity>()
        val seenSectionKeys = HashSet<String>()
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

            // 同一学期「课程名 + 教学班号（缺失退化为教师）」的行聚合为同一门课；不同时段成为其子时段。
            val identityKey = CourseIdentity.masterKey(dto.source, dto.name, dto.className, dto.teacher)
            val masterIndex = masterIndexByKey[identityKey] ?: run {
                val index = masters.size
                masters += CourseEntity(
                    timetableId = timetableId,
                    source = dto.source,
                    remoteKey = identityKey,
                    name = dto.name,
                    teacher = dto.teacher,
                    credits = dto.credits,
                    totalHours = dto.hours,
                    courseType = dto.courseType,
                    assessment = dto.assessment,
                    className = dto.className,
                    color = colorForMaster(index, identityKey)
                )
                masterIndexByKey[identityKey] = index
                index
            }
            // 主教师缺失时用后续时段的非空教师补齐，保持主记录信息完整。
            if (masters[masterIndex].teacher.isBlank() && dto.teacher.isNotBlank()) {
                masters[masterIndex] = masters[masterIndex].copy(teacher = dto.teacher)
            }

            // 教务偶尔会返回完全相同的段落；同键时段只保留第一条。
            val sectionKey = dto.remoteKey()
            if (!seenSectionKeys.add("$masterIndex|$sectionKey")) return@forEach

            // CourseSectionEntity.courseId 暂存主课程下标，CourseWeekEntity.sectionId
            // 暂存时段下标，由本地仓储在插入事务中重映射为真实 ID。
            sections += CourseSectionEntity(
                courseId = masterIndex.toLong(),
                source = dto.source,
                remoteKey = sectionKey,
                dayOfWeek = dto.day,
                startPeriod = period.start,
                endPeriod = period.end,
                room = dto.room,
                teacher = dto.teacher,
                rawWeekText = dto.weeks
            )
            parsedWeeks.weeks.forEach { weeks += CourseWeekEntity((sections.size - 1).toLong(), it) }
        }
        return MappedSchedule(
            courses = masters,
            sections = sections,
            weeks = weeks,
            warnings = warnings,
            studentId = payload.student?.studentId,
            studentName = payload.student?.name
        )
    }

    /**
     * 颜色按主课程序号分配：前几门使用预设色板中互相区分明显的颜色；
     * 超出色板数量后用基于身份键的稳定哈希色（仍保持统一马卡龙风格）。
     */
    private fun colorForMaster(index: Int, identityKey: String): Int =
        if (index < com.example.awake.domain.model.DefaultCourseColors.size) {
            com.example.awake.domain.model.DefaultCourseColors[index]
        } else {
            com.example.awake.domain.model.defaultCourseColor(identityKey)
        }
}