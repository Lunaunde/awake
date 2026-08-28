package com.example.awake.domain.parser

import com.example.awake.domain.model.PeriodParseResult
import com.example.awake.domain.model.ParseWarning

/** 解析 jcs：例如 1-2、1，2、第1-2节。 */
object PeriodExpressionParser {
    private val rangeRegex = Regex("(\\d+)\\s*[-—~至到]\s*(\\d+)")
    private val numberRegex = Regex("\\d+")

    fun parse(expression: String?, maxPeriod: Int = 16): PeriodParseResult {
        val original = expression?.trim().orEmpty()
        if (original.isBlank()) return PeriodParseResult(1, 1, ParseWarning(original, "节次为空"))
        val normalized = original.replace("第", "").replace("节", "").replace("课", "").replace(" ", "")
        val range = rangeRegex.find(normalized)
        val numbers = if (range != null) listOf(range.groupValues[1].toInt(), range.groupValues[2].toInt())
        else numberRegex.findAll(normalized).map { it.value.toInt() }.toList()
        if (numbers.isEmpty() || numbers.any { it !in 1..maxPeriod }) {
            return PeriodParseResult(1, 1, ParseWarning(original, "无法识别节次：$original"))
        }
        val start = numbers.minOrNull() ?: 1
        val end = numbers.maxOrNull() ?: start
        return if (start <= end) PeriodParseResult(start, end)
        else PeriodParseResult(1, 1, ParseWarning(original, "节次范围无效：$original"))
    }
}
