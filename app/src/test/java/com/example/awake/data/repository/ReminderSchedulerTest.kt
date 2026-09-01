package com.example.awake.data.repository

import com.example.awake.data.local.CourseSlotEntity
import com.example.awake.data.local.PeriodConfigEntity
import com.example.awake.data.local.TimetableEntity
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderSchedulerTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Clock.fixed(Instant.parse("2026-08-29T00:00:00Z"), zone)
    private val slot = CourseSlotEntity(
        sectionId = 7, courseId = 3, timetableId = 1, source = "MANUAL", name = "测试课",
        teacher = "张老师", room = "A101",
        dayOfWeek = 1, startPeriod = 1, endPeriod = 2, color = 0, rawWeekText = "1-2"
    )

    @Test
    fun calculatesFutureReminder() {
        val reminder = ReminderTimeCalculator.calculate(
            slot = slot,
            date = LocalDate.of(2026, 8, 29),
            periodStart = LocalTime.of(9, 0),
            minutesBefore = 15,
            clock = now
        )

        requireNotNull(reminder)
        assertEquals(3L, reminder.sectionId)
        assertEquals(LocalTime.of(8, 45), reminder.triggerAt.toLocalTime())
        assertEquals(15, reminder.minutesBefore)
        assertEquals(1L, reminder.timetableId)
    }

    @Test
    fun skipsReminderAlreadyInThePast() {
        val reminder = ReminderTimeCalculator.calculate(
            slot = slot,
            date = LocalDate.of(2026, 8, 29),
            periodStart = LocalTime.of(8, 0),
            minutesBefore = 15,
            clock = now
        )

        assertNull(reminder)
    }

    @Test
    fun fakeSchedulerRecordsAndCancels() {
        val scheduler = FakeReminderScheduler()
        val reminder = Reminder(3, java.time.LocalDateTime.of(2026, 8, 29, 8, 45), 15)

        scheduler.schedule(listOf(reminder))
        scheduler.cancelForTimetable(1)

        assertEquals(listOf(reminder), scheduler.scheduled)
        assertEquals(listOf(1L), scheduler.cancelledTimetables)
    }

    @Test
    fun plannerExpandsFutureWeeksAndUsesPeriodStart() {
        val timetable = TimetableEntity(
            id = 1,
            profileId = 1,
            xnm = 2026,
            xqm = "3",
            label = "测试学期",
            startDate = "2026-08-31",
            totalWeeks = 2
        )
        val reminders = ReminderPlanner.plan(
            timetable,
            listOf(slot.copy(rawWeekText = "1-2")),
            listOf(PeriodConfigEntity(1, "08:00", "08:45")),
            minutesBefore = 15,
            clock = now
        )

        assertEquals(2, reminders.size)
        assertEquals(LocalDate.of(2026, 8, 31), reminders.first().triggerAt.toLocalDate())
        assertEquals(LocalTime.of(7, 45), reminders.first().triggerAt.toLocalTime())
        assertEquals(1L, reminders.first().timetableId)
    }

    @Test
    fun plannerSkipsTimetableWithoutStartDate() {
        val timetable = TimetableEntity(
            id = 1,
            profileId = 1,
            xnm = 2026,
            xqm = "3",
            label = "无日期学期",
            startDate = null
        )
        val reminders = ReminderPlanner.plan(
            timetable,
            listOf(slot),
            listOf(PeriodConfigEntity(1, "08:00", "08:45")),
            minutesBefore = 15,
            clock = now
        )

        assertEquals(emptyList<Reminder>(), reminders)
    }
}