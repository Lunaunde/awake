package com.example.awake.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PeriodExpressionParserTest {
    @Test fun parsesChineseRange() {
        val result = PeriodExpressionParser.parse("第3-5节")
        assertEquals(3, result.start)
        assertEquals(5, result.end)
        assertEquals(null, result.warning)
    }

    @Test fun parsesSingleAndSeparatedContinuousPeriods() {
        assertEquals(2, PeriodExpressionParser.parse("第2节").start)
        assertEquals(2, PeriodExpressionParser.parse("第2节").end)
        assertEquals(1, PeriodExpressionParser.parse("1、2").start)
        assertEquals(2, PeriodExpressionParser.parse("1、2").end)
    }

    @Test fun invalidPeriodIsVisible() {
        assertNotNull(PeriodExpressionParser.parse("未知").warning)
        assertNotNull(PeriodExpressionParser.parse("第3-5节extra").warning)
        assertNotNull(PeriodExpressionParser.parse("5-3").warning)
        assertNotNull(PeriodExpressionParser.parse("1,3").warning)
        assertNotNull(PeriodExpressionParser.parse("0-2").warning)
        assertNotNull(PeriodExpressionParser.parse("1节2").warning)
    }
}
