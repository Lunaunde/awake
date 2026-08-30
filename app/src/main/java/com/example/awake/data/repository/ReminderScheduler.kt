package com.example.awake.data.repository

import com.example.awake.data.local.CourseEntity
import com.example.awake.data.local.PeriodConfigEntity
import com.example.awake.data.local.TimetableEntity
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** 提醒调度抽象。Android 端由 AlarmManager 实现，纯 JVM 测试可用 FakeReminderScheduler。 */
data class Reminder(
    val courseId: Long,
    val triggerAt: LocalDateTime,
    val minutesBefore: Int,
    val timetableId: Long = 0L
)

interface ReminderScheduler {
    fun schedule(reminders: List<Reminder>)
    fun cancelForTimetable(timetableId: Long)
    fun cancelAll() = Unit
}

class FakeReminderScheduler : ReminderScheduler {
    val scheduled = mutableListOf<Reminder>()
    val cancelledTimetables = mutableListOf<Long>()
    var cancelAllCount = 0

    override fun schedule(reminders: List<Reminder>) { scheduled += reminders }
    override fun cancelForTimetable(timetableId: Long) { cancelledTimetables += timetableId }
    override fun cancelAll() { cancelAllCount++ }
}

object ReminderTimeCalculator {
    fun calculate(
        course: CourseEntity,
        date: LocalDate,
        periodStart: LocalTime,
        minutesBefore: Int,
        clock: Clock = Clock.systemDefaultZone()
    ): Reminder? {
        val start = LocalDateTime.of(date, periodStart).minusMinutes(minutesBefore.toLong())
        return if (start.isAfter(LocalDateTime.now(clock))) {
            Reminder(course.id, start, minutesBefore, course.timetableId)
        } else {
            null
        }
    }
}

/** 将一个学期的本地课程展开为未来的系统提醒。 */
object ReminderPlanner {
    fun plan(
        timetable: TimetableEntity,
        courses: List<CourseEntity>,
        periodConfigs: List<PeriodConfigEntity>,
        minutesBefore: Int,
        clock: Clock = Clock.systemDefaultZone()
    ): List<Reminder> {
        val startDate = runCatching { LocalDate.parse(timetable.startDate) }.getOrNull() ?: return emptyList()
        val periods = periodConfigs.associateBy { it.period }
        val maxWeek = timetable.totalWeeks.coerceIn(1, 60)

        return courses.flatMap { course ->
            val periodStart = periods[course.startPeriod]?.startTime?.parseLocalTime()
                ?: return@flatMap emptyList()
            val weeks = com.example.awake.domain.parser.WeekExpressionParser
                .parse(course.rawWeekText, maxWeek)
                .weeks
            weeks.mapNotNull { week ->
                val date = startDate
                    .plusWeeks((week - 1).toLong())
                    .plusDays((course.dayOfWeek - 1).toLong())
                ReminderTimeCalculator.calculate(course, date, periodStart, minutesBefore, clock)
            }
        }.distinctBy { Triple(it.courseId, it.triggerAt, it.minutesBefore) }
            .sortedBy { it.triggerAt }
    }

    private fun String.parseLocalTime(): LocalTime? = runCatching { LocalTime.parse(this) }.getOrNull()
}

/** 统一处理设置、当前课表和系统调度，避免切换课表后残留旧提醒。 */
class ReminderCoordinator(
    private val local: LocalTimetableRepository,
    private val scheduler: ReminderScheduler,
    private val settings: ReminderSettingsStore,
    private val selection: TimetableSelectionStore
) {
    suspend fun reschedule(timetableId: Long) {
        scheduler.cancelAll()
        rescheduleWithoutClearing(timetableId)
    }

    suspend fun rescheduleSelected() {
        scheduler.cancelAll()
        if (!settings.read().enabled) return
        val selected = selection.read()
            ?.let { local.getTimetableOrNull(it) }
            ?: local.getFirstTimetable()
        selected?.let { rescheduleWithoutClearing(it.id) }
    }

    fun cancelAll() = scheduler.cancelAll()

    private suspend fun rescheduleWithoutClearing(timetableId: Long) {
        val reminderSettings = settings.read()
        if (!reminderSettings.enabled) return
        val timetable = local.getTimetableOrNull(timetableId) ?: return
        val reminders = ReminderPlanner.plan(
            timetable = timetable,
            courses = local.getAllCourses(timetable.id),
            periodConfigs = local.getPeriodConfigs(),
            minutesBefore = reminderSettings.minutesBefore
        )
        scheduler.schedule(reminders)
    }
}
