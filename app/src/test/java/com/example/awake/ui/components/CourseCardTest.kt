package com.example.awake.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class CourseCardTest {
    @Test
    fun parityLabelOnlyAppearsInsideItsMarkedRange() {
        val raw = "1-3周(单),4-17周"

        assertEquals("单周", weekParityLabel(raw, currentWeek = 1, totalWeeks = 17))
        assertEquals("单周", weekParityLabel(raw, currentWeek = 2, totalWeeks = 17))
        assertEquals("单周", weekParityLabel(raw, currentWeek = 3, totalWeeks = 17))
        assertEquals(null, weekParityLabel(raw, currentWeek = 4, totalWeeks = 17))
        assertEquals(null, weekParityLabel(raw, currentWeek = 17, totalWeeks = 17))
    }

    @Test
    fun parityLabelSupportsWholeTermParityWithoutRange() {
        assertEquals("单周", weekParityLabel("单周", currentWeek = 9, totalWeeks = 17))
        assertEquals("双周", weekParityLabel("双周", currentWeek = 10, totalWeeks = 17))
        assertEquals(null, weekParityLabel("1-16周", currentWeek = 9, totalWeeks = 17))
    }
}
