package com.example.awake.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeekExpressionParserTest {
    @Test fun parsesRangeAndList() {
        assertEquals((1..16).toSet(), WeekExpressionParser.parse("第1-16周", 16).weeks)
        assertEquals(setOf(1, 3, 5), WeekExpressionParser.parse("1,3,5", 16).weeks)
        assertEquals(setOf(1, 2, 3), WeekExpressionParser.parse("1、2、3", 16).weeks)
    }

    @Test fun parsesParity() {
        assertEquals(setOf(1, 3, 5, 7), WeekExpressionParser.parse("1-8单周", 8).weeks)
        assertEquals(setOf(2, 4, 6, 8), WeekExpressionParser.parse("1-8双周", 8).weeks)
    }

    @Test fun parsesScheduleExpressionsWithPerRangeSuffixes() {
        assertEquals(
            (setOf(1, 3) + (4..17).toSet()),
            WeekExpressionParser.parse("1-3周(单),4-17周", 17).weeks
        )
        assertEquals(
            ((3..10).toSet() + setOf(12, 13)),
            WeekExpressionParser.parse("3-10周,12-13周", 17).weeks
        )
    }
    @Test fun parsesAllWithinConfiguredBoundary() {
        assertEquals(setOf(1, 2, 3, 4), WeekExpressionParser.parse("all", 4).weeks)
        assertTrue(WeekExpressionParser.parse("1-16", 15).warning != null)
    }

    @Test fun unknownAndBlankAreWarnings() {
        assertNotNull(WeekExpressionParser.parse(null).warning)
        assertNotNull(WeekExpressionParser.parse("待定").warning)
        assertNotNull(WeekExpressionParser.parse("1-16abc", 16).warning)
        assertNotNull(WeekExpressionParser.parse("第1-2周extra", 16).warning)
        assertNotNull(WeekExpressionParser.parse("1-16单双周", 16).warning)
        assertNotNull(WeekExpressionParser.parse("1,,3", 16).warning)
        assertNotNull(WeekExpressionParser.parse("1周2", 16).warning)
    }

    @Test fun rejectsInvalidRangeAndParityResult() {
        assertNotNull(WeekExpressionParser.parse("0-3", 16).warning)
        assertNotNull(WeekExpressionParser.parse("3-1", 16).warning)
        assertNotNull(WeekExpressionParser.parse("1-1双周", 16).warning)
    }
}
