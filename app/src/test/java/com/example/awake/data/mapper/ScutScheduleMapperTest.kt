package com.example.awake.data.mapper

import com.example.awake.data.remote.ScutCourseDto
import com.example.awake.data.remote.ScutSchedulePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScutScheduleMapperTest {
    @Test
    fun mapsValidCourseAndUsesConfiguredWeekBoundary() {
        val payload = ScutSchedulePayload(
            student = null,
            courses = listOf(
                ScutCourseDto("SCUT_KB", "高等数学", "李老师", "A101", 1, "星期一", "1-2", "1-4", "4", "64", "必修", "考试", "一班")
            )
        )
        val mapped = ScutScheduleMapper().map(payload, timetableId = 8, maxWeek = 4)
        assertEquals(1, mapped.courses.size)
        assertEquals(8, mapped.courses.single().timetableId)
        assertEquals(4, mapped.weeks.size)
        assertEquals(0L, mapped.weeks.first().courseId)
        assertTrue(mapped.warnings.isEmpty())
    }

    @Test
    fun mapsMixedParityAndMultipleRangesUsedByScutSchedule() {
        val payload = ScutSchedulePayload(
            student = null,
            courses = listOf(
                ScutCourseDto("SCUT_KB", "软件项目管理", "吴欣", "A4102", 2, "星期二", "1-3", "1-3周(单),4-17周", null, null, null, null, null),
                ScutCourseDto("SCUT_KB", "软件体系结构", "邓紫坤", "A1503", 3, "星期三", "3-4", "3-10周,12-13周", null, null, null, null, null),
                ScutCourseDto("SCUT_KB", "软件体系结构", "邓紫坤", "A4101", 4, "星期四", "3-4", "3-10周,12-13周", null, null, null, null, null)
            )
        )
        val mapped = ScutScheduleMapper().map(payload, timetableId = 8, maxWeek = 17)

        assertEquals(3, mapped.courses.size)
        assertTrue(mapped.warnings.isEmpty())
        assertTrue(mapped.weeks.count { it.weekNumber == 12 } == 3)
    }
    @Test
    fun invalidDayAndEmptyNameBecomeWarningsAndAreSkipped() {
        val payload = ScutSchedulePayload(
            null,
            listOf(
                ScutCourseDto("SCUT_KB", "", "", "", 1, "", "1", "1", null, null, null, null, null),
                ScutCourseDto("SCUT_KB", "错误星期", "", "", 0, "不明", "1", "1", null, null, null, null, null)
            )
        )
        val mapped = ScutScheduleMapper().map(payload, 1, 4)
        assertTrue(mapped.courses.isEmpty())
        assertEquals(2, mapped.warnings.size)
        assertTrue(mapped.warnings.any { it.message.contains("课程名称") })
        assertTrue(mapped.warnings.any { it.message.contains("星期") })
    }

    @Test
    fun invalidPeriodAndWeekAreWarnings() {
        val payload = ScutSchedulePayload(
            null,
            listOf(ScutCourseDto("SCUT_KB", "测试", "", "", 1, "星期一", "2,4", "1-8", null, null, null, null, null))
        )
        val mapped = ScutScheduleMapper().map(payload, 1, 4)
        assertTrue(mapped.courses.isEmpty())
        assertEquals(2, mapped.warnings.size)
    }
}
