package com.example.awake.data.export

import com.example.awake.data.local.CourseEntity
import com.example.awake.data.local.PeriodConfigDefaults
import com.example.awake.data.local.PeriodConfigEntity
import com.example.awake.data.local.TimetableEntity
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 生成 RFC 5545 兼容的纯文本 ICS；由系统分享/SAF 保存，不自动上传。 */
object IcsExporter {
    fun export(timetable: TimetableEntity, courses: List<CourseEntity>, periodConfigs: List<PeriodConfigEntity>): String {
        val periods = (periodConfigs + PeriodConfigDefaults.entities())
            .distinctBy { it.period }
            .associateBy { it.period }
        val startDate = runCatching { LocalDate.parse(timetable.startDate) }.getOrNull() ?: LocalDate.now()
        val out = StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Awake//Timetable//CN\r\n")
        courses.forEach { course ->
            val start = periods[course.startPeriod]?.startTime ?: PeriodConfigDefaults.values.first().first
            val end = periods[course.endPeriod]?.endTime ?: PeriodConfigDefaults.values.last().second
            val weeks = com.example.awake.domain.parser.WeekExpressionParser.parse(course.rawWeekText, timetable.totalWeeks).weeks
            weeks.forEach { week ->
                val date = startDate.plusWeeks((week - 1).toLong()).plusDays((course.dayOfWeek - 1).toLong())
                out.append("BEGIN:VEVENT\r\n")
                    .append("UID:${timetable.id}-${course.id}-$week@awake\r\n")
                    .append("DTSTART:${date.format(DateTimeFormatter.BASIC_ISO_DATE)}T${start.replace(":", "")}00\r\n")
                    .append("DTEND:${date.format(DateTimeFormatter.BASIC_ISO_DATE)}T${end.replace(":", "")}00\r\n")
                    .append("SUMMARY:").append(escape(course.name)).append("\r\n")
                    .append("LOCATION:").append(escape(course.room)).append("\r\n")
                    .append("DESCRIPTION:").append(escape(course.teacher)).append("\r\n")
                    .append("END:VEVENT\r\n")
            }
        }
        return out.append("END:VCALENDAR\r\n").toString()
    }
    private fun escape(value: String) = value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")
}
