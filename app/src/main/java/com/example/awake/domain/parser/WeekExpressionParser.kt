package com.example.awake.domain.parser

import com.example.awake.domain.model.ParseWarning
import com.example.awake.domain.model.WeekParseResult

/** 将正方教务的周次表达式规范化为周号集合。未知格式不会被默认为全周。 */
object WeekExpressionParser {
    private val rangeRegex = Regex("(\\d+)\\s*[-—~至到]\s*(\\d+)")
    private val numberRegex = Regex("\\d+")

    fun parse(expression: String?, maxWeek: Int = 30): WeekParseResult {
        val original = expression?.trim().orEmpty()
        if (original.isBlank()) return WeekParseResult(emptySet(), ParseWarning(original, "周次为空"))
        val normalized = original.lowercase()
            .replace("第", "")
            .replace("周", "")
            .replace("星期", "")
            .replace(" ", "")
        if (normalized == "all" || normalized == "全周" || normalized == "所有") {
            return WeekParseResult((1..maxWeek).toSet())
        }

        val parity = when {
            normalized.contains("单") || normalized.contains("odd") -> 1
            normalized.contains("双") || normalized.contains("even") -> 0
            else -> null
        }
        val withoutParity = normalized.replace("单周", "").replace("双周", "")
            .replace("odd", "").replace("even", "")
        val weeks = linkedSetOf<Int>()
        val tokens = withoutParity.split(',', '，', ';', '；', '|', '/').filter { it.isNotBlank() }
        for (token in tokens) {
            val range = rangeRegex.find(token)
            if (range != null) {
                val start = range.groupValues[1].toInt()
                val end = range.groupValues[2].toInt()
                if (start <= 0 || end < start || end > maxWeek + 20) {
                    return WeekParseResult(emptySet(), ParseWarning(original, "周次范围越界：$token"))
                }
                weeks += start..end
                continue
            }
            val numbers = numberRegex.findAll(token).map { it.value.toInt() }.toList()
            if (numbers.size == 1 && numbers[0] in 1..(maxWeek + 20)) {
                weeks += numbers[0]
            } else if (numbers.isEmpty()) {
                return WeekParseResult(emptySet(), ParseWarning(original, "无法识别周次：$token"))
            } else {
                return WeekParseResult(emptySet(), ParseWarning(original, "无法识别周次：$token"))
            }
        }
        if (parity != null) weeks.removeIf { it % 2 != parity }
        if (weeks.isEmpty()) return WeekParseResult(emptySet(), ParseWarning(original, "周次解析后为空"))
        return WeekParseResult(weeks)
    }
}
