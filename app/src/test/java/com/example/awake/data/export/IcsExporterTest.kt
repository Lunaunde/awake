package com.example.awake.data.export

import com.example.awake.data.local.CourseEntity
import com.example.awake.data.local.TimetableEntity
import org.junit.Assert.assertTrue
import org.junit.Test

class IcsExporterTest {
    @Test fun exportsOneEventPerWeek() {
        val timetable = TimetableEntity(id = 2, profileId = 1, xnm = 2026, xqm = "3", label = "测试", startDate = "2026-09-01", totalWeeks = 2)
        val course = CourseEntity(id = 5, timetableId = 2, source = "MANUAL", remoteKey = "test", name = "测试课", room = "A101", dayOfWeek = 1, startPeriod = 1, endPeriod = 2, rawWeekText = "1-2")
        val ics = IcsExporter.export(timetable, listOf(course), emptyList())
        assertTrue(ics.startsWith("BEGIN:VCALENDAR"))
        assertTrue(ics.contains("BEGIN:VEVENT\r\n"))
        assertTrue(ics.contains("SUMMARY:测试课"))
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"))
    }
}
