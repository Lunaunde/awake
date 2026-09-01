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
        assertEquals(1, mapped.sections.size)
        assertEquals(4, mapped.weeks.size)
        // courseId/sectionId 在映射阶段是列表下标，插入事务内才重映射为真实 ID。
        assertEquals(0L, mapped.sections.single().courseId)
        assertEquals(0L, mapped.weeks.single().sectionId)
        assertEquals("李老师", mapped.sections.single().teacher)
        assertTrue(mapped.warnings.isEmpty())
    }

    @Test
    fun sameNameCoursesMergeIntoOneMasterWithSeparateSections() {
        val payload = ScutSchedulePayload(
            student = null,
            courses = listOf(
                ScutCourseDto("SCUT_KB", "软件项目管理", "吴欣", "A4102", 2, "星期二", "1-3", "1-3周(单),4-17周", null, null, null, null, null),
                ScutCourseDto("SCUT_KB", "软件体系结构", "邓紫坤", "A1503", 3, "星期三", "3-4", "3-10周,12-13周", null, null, null, null, null),
                ScutCourseDto("SCUT_KB", "软件体系结构", "邓紫坤", "A4101", 4, "星期四", "3-4", "3-10周,12-13周", null, null, null, null, null)
            )
        )
        val mapped = ScutScheduleMapper().map(payload, timetableId = 8, maxWeek = 17)

        // 软件体系结构的两行（同教师同名、无教学班号）合并为一个主课程 + 两个时段。
        assertEquals(2, mapped.courses.size)
        assertEquals(3, mapped.sections.size)
        assertTrue(mapped.warnings.isEmpty())
        assertEquals(3, mapped.weeks.count { it.weekNumber == 12 })

        val masterIndex = mapped.courses.indexOfFirst { it.name == "软件体系结构" }
        val sectionsOfMaster = mapped.sections.filter { it.courseId == masterIndex.toLong() }
        assertEquals(2, sectionsOfMaster.size)
        assertEquals(2, sectionsOfMaster.map { it.remoteKey }.distinct().size)
        assertEquals(3, sectionsOfMaster.first().dayOfWeek)
        assertEquals(4, sectionsOfMaster.last().dayOfWeek)
    }

    @Test
    fun distinctTeachingClassStaysSeparateEvenWithSameName() {
        val payload = ScutSchedulePayload(
            student = null,
            courses = listOf(
                ScutCourseDto("SCUT_KB", "大学英语", "王老师", "B202", 1, "星期一", "1-2", "1-16", null, null, null, null, "2026-JDYY-01"),
                ScutCourseDto("SCUT_KB", "大学英语", "刘老师", "B203", 3, "星期三", "1-2", "1-16", null, null, null, null, "2026-JDYY-02")
            )
        )
        val mapped = ScutScheduleMapper().map(payload, timetableId = 1, maxWeek = 16)
        // 不同教学班号 → 两个独立主课程，不合并。
        assertEquals(2, mapped.courses.size)
        assertEquals(2, mapped.sections.size)
        assertEquals(2, mapped.weeks.count { it.weekNumber == 1 })
    }

    @Test
    fun identicalDuplicateRowsAreDeduplicated() {
        val dto = ScutCourseDto("SCUT_KB", "大学物理", "陈老师", "C301", 2, "星期二", "3-4", "1-16", null, null, null, null, null)
        val payload = ScutSchedulePayload(null, listOf(dto, dto.copy()))
        val mapped = ScutScheduleMapper().map(payload, timetableId = 1, maxWeek = 16)
        assertEquals(1, mapped.sections.size)
        assertEquals(16, mapped.weeks.size)
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
        assertTrue(mapped.sections.isEmpty())
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
        assertTrue(mapped.sections.isEmpty())
        assertEquals(2, mapped.warnings.size)
    }
}