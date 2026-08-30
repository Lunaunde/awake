package com.example.awake.domain.parser

import com.example.awake.domain.model.ParseWarning
import com.example.awake.domain.model.PeriodParseResult

/** 解析 jcs：例如 1、1-2、1，2、第1-2节。 */
object PeriodExpressionParser {
    private val rangeRegex = Regex("\\d+[-—~至到]\\d+")
    private val numberRegex = Regex("\\d+")

    fun parse(expression: String?, maxPeriod: Int = 16): PeriodParseResult {
        val original = expression?.trim().orEmpty()
        if (original.isBlank()) return warning(original, "节次为空")
        if (maxPeriod < 1) return warning(original, "最大节次无效：$maxPeriod")

        var normalized = original.replace(Regex("\\s+"), "")
        if (normalized.startsWith("第")) normalized = normalized.removePrefix("第")
        if (normalized.endsWith("节") || normalized.endsWith("课")) normalized = normalized.dropLast(1)
        if (normalized.isBlank() || normalized.contains('第') || normalized.contains('节') || normalized.contains('课')) {
            return warning(original, "无法识别节次：$original")
        }

        val values = when {
            rangeRegex.matches(normalized) -> {
                val bounds = normalized.split('-', '—', '~', '至', '到')
                val start = bounds[0].toIntOrNull()
                val end = bounds[1].toIntOrNull()
                if (start == null || end == null || start > end) {
                    return warning(original, "节次范围无效：$original")
                }
                (start..end).toList()
            }

            numberRegex.matches(normalized) -> listOf(normalized.toIntOrNull() ?: return warning(original, "无法识别节次：$original"))

            else -> {
                val tokens = normalized.split(',', '，', '、', ';', '；', '|', '/')
                if (tokens.any { it.isBlank() } || tokens.any { !numberRegex.matches(it) }) {
                    return warning(original, "无法识别节次：$original")
                }
                tokens.map { it.toIntOrNull() ?: return warning(original, "无法识别节次：$original") }
            }
        }

        if (values.any { it !in 1..maxPeriod }) {
            return warning(original, "节次越界：$original")
        }

        val distinctSorted = values.distinct().sorted()
        if (distinctSorted.last() - distinctSorted.first() + 1 != distinctSorted.size) {
            return warning(original, "节次必须连续：$original")
        }
        return PeriodParseResult(distinctSorted.first(), distinctSorted.last())
    }

    private fun warning(input: String, message: String) =
        PeriodParseResult(1, 1, ParseWarning(input, message))
}
