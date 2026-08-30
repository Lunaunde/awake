package com.example.awake.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScutDtosTest {
    @Test
    fun parsesStudentAndBothCourseLists() {
        val payload = ScutSchedulePayload.fromJson(
            """
            {
              "xsxx": {"xh": "20201234", "xm": "张三"},
              "kbList": [{"kcmc": "高等数学", "xm": "李老师", "cdmc": "A101", "xqj": "1", "jcs": "1-2", "zcd": "1-16"}],
              "sjkList": [{"kcmc": "体育", "xqjmc": "星期日", "jcs": "3", "zcd": "单周"}]
            }
            """.trimIndent()
        )

        assertEquals("20201234", payload.student?.studentId)
        assertEquals("张三", payload.student?.name)
        assertEquals(2, payload.courses.size)
        assertEquals("SCUT_KB", payload.courses[0].source)
        assertEquals(1, payload.courses[0].day)
        assertEquals("SCUT_SJK", payload.courses[1].source)
        assertEquals(7, payload.courses[1].day)
    }

    @Test
    fun unknownDayDoesNotBecomeMonday() {
        val payload = ScutSchedulePayload.fromJson(
            """{"kbList":[{"kcmc":"未知日","xqj":"9","xqjmc":"不明","jcs":"1","zcd":"1"}]}"""
        )
        assertEquals(0, payload.courses.single().day)
    }

    @Test
    fun missingCourseArraysAreRejected() {
        try {
            ScutSchedulePayload.fromJson("{\"xsxx\":{\"xh\":\"1\"}}")
            throw AssertionError("expected invalid response")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("kbList/sjkList"))
        }
    }

    @Test
    fun emptyCourseNameRemainsVisibleToMapper() {
        val payload = ScutSchedulePayload.fromJson(
            "{\"kbList\":[{\"xqj\":1,\"jcs\":\"1\",\"zcd\":\"1\"}]}"
        )
        assertEquals("", payload.courses.single().name)
    }
}
