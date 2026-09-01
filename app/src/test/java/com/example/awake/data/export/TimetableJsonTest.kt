package com.example.awake.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows

class TimetableJsonTest {
    private val meta = TimetableJson.JsonTimetableMeta(
        label = "2026-2027 第一学期",
        xnm = 2026,
        xqm = "3",
        startDate = "2026-08-31",
        totalWeeks = 16
    )

    private val courses = listOf(
        TimetableJson.JsonCourse(
            source = "SCUT_KB",
            name = "软件体系结构",
            teacher = "邓紫坤",
            color = -12165016,
            credits = "3",
            totalHours = "48",
            courseType = "必修",
            assessment = "考试",
            className = "2026-RJGC-01",
            sections = listOf(
                TimetableJson.JsonSection(3, 3, 4, "A1503", "邓紫坤", "1-16"),
                TimetableJson.JsonSection(4, 3, 4, "A4101", "邓紫坤", "1-16")
            )
        ),
        TimetableJson.JsonCourse(
            source = "MANUAL",
            name = "手工课",
            teacher = "",
            color = null,
            credits = null,
            totalHours = null,
            courseType = null,
            assessment = null,
            className = null,
            sections = listOf(TimetableJson.JsonSection(1, 1, 2, "自选", "", "单周"))
        )
    )

    @Test
    fun roundTripPreservesMetaCoursesAndSections() {
        val text = TimetableJson.toString(meta, courses)
        val parsed = TimetableJson.parse(text)

        assertEquals(meta.label, parsed.meta.label)
        assertEquals(meta.xnm, parsed.meta.xnm)
        assertEquals(meta.xqm, parsed.meta.xqm)
        assertEquals(meta.startDate, parsed.meta.startDate)
        assertEquals(meta.totalWeeks, parsed.meta.totalWeeks)
        assertEquals(2, parsed.courses.size)

        val first = parsed.courses[0]
        assertEquals("软件体系结构", first.name)
        assertEquals("SCUT_KB", first.source)
        assertEquals(-12165016, first.color)
        assertEquals(2, first.sections.size)
        assertEquals("A4101", first.sections[1].room)

        val manual = parsed.courses[1]
        assertEquals(null, manual.color)
        assertEquals("单周", manual.sections.single().rawWeekText)
    }

    @Test
    fun parseRejectsForeignText() {
        val error = runCatching { TimetableJson.parse("""{"courses":[]}""") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun parseRejectsInvalidDay() {
        val text = TimetableJson.toString(
            meta,
            listOf(courses.first().copy(sections = listOf(courses.first().sections.first().copy(dayOfWeek = 0))))
        )
        val error = runCatching { TimetableJson.parse(text) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun parseRejectsCourseWithoutSections() {
        val text = TimetableJson.toString(meta, listOf(courses.first().copy(sections = emptyList())))
        val error = runCatching { TimetableJson.parse(text) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}