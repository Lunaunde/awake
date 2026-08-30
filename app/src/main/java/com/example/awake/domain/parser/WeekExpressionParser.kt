package com.example.awake.domain.parser

import com.example.awake.domain.model.ParseWarning
import com.example.awake.domain.model.WeekParseResult

/**
 * 将教务系统的周次表达式规范化为周号集合。
 * 支持“1-16”“第1-16周”“3-10周,12-13周”以及“1-3周(单),4-17周”等混合格式。
 */
object WeekExpressionParser {
    private val rangeRegex = Regex("\\d+[-—~至到]\\d+")
    private val numberRegex = Regex("\\d+")
    private val chineseParitySuffixRegex = Regex("^(.*?)(?:周)?\\(?(单|双)(?:周)?\\)?$")
    private val englishParitySuffixRegex = Regex("^(.*?)(?:周)?\\(?(odd|even)\\)?$", RegexOption.IGNORE_CASE)

    fun parse(expression: String?, maxWeek: Int = 30): WeekParseResult {
        val original = expression?.trim().orEmpty()
        if (original.isBlank()) return warning(original, "周次为空")
        if (maxWeek < 1) return warning(original, "最大周次无效：$maxWeek")

        val normalized = original.lowercase()
            .replace(Regex("\\s+"), "")
            .replace('（', '(')
            .replace('）', ')')
        if (normalized in setOf("all", "全周", "所有", "全部")) {
            return WeekParseResult((1..maxWeek).toSet())
        }

        val tokens = normalized.split(',', '，', '、', ';', '；', '|', '/')
        if (tokens.any { it.isBlank() }) return warning(original, "周次包含空项")

        val weeks = linkedSetOf<Int>()
        tokens.forEach { token ->
            val parsed = parseToken(token, maxWeek)
                ?: return warning(original, "无法识别周次：$token")
            weeks += parsed
        }
        if (weeks.isEmpty()) return warning(original, "周次解析后为空")
        return WeekParseResult(weeks)
    }

    private fun parseToken(token: String, maxWeek: Int): Set<Int>? {
        var core = token
        var parity: Int? = null

        val parityMatch = chineseParitySuffixRegex.matchEntire(core)
            ?: englishParitySuffixRegex.matchEntire(core)
        if (parityMatch != null) {
            core = parityMatch.groupValues[1]
            val marker = parityMatch.groupValues[2].ifBlank { parityMatch.groupValues.getOrNull(2).orEmpty() }
            parity = when (marker.lowercase()) {
                "单", "odd" -> 1
                "双", "even" -> 0
                else -> null
            }
        }

        if (core.startsWith("第")) core = core.removePrefix("第")
        if (core.endsWith("周")) core = core.removeSuffix("周")
        if (core.isBlank()) {
            if (parity == null) return null
            core = "1-$maxWeek"
        }
        if (core.contains('第') || core.contains('周') || core.contains('(') || core.contains(')')) return null

        val values = when {
            rangeRegex.matches(core) -> {
                val bounds = core.split('-', '—', '~', '至', '到')
                val start = bounds.getOrNull(0)?.toIntOrNull()
                val end = bounds.getOrNull(1)?.toIntOrNull()
                if (start == null || end == null || start < 1 || end < start || end > maxWeek) return null
                (start..end).toList()
            }
            numberRegex.matches(core) -> {
                val week = core.toIntOrNull() ?: return null
                if (week !in 1..maxWeek) return null
                listOf(week)
            }
            else -> return null
        }

        val filtered = if (parity == null) values else values.filter { it % 2 == parity }
        return filtered.toSet().takeIf { it.isNotEmpty() }
    }

    private fun warning(input: String, message: String) =
        WeekParseResult(emptySet(), ParseWarning(input, message))
}
