package com.example.awake.domain.parser

/**
 * 周次集合与展示文本的互转工具。
 * 存储侧沿用 WeekExpressionParser 可解析的紧凑表达式；
 * 展示侧把相邻/连续周合并成区间（1-6,9,12-16），由前端负责呈现。
 */
object WeekSelection {
    /** 将周次集合合并为区间文本，如 {1,2,3,4,5,6,9,12,13,14} → “1-6,9,12-14”。 */
    fun format(weeks: Collection<Int>, maxWeek: Int = 30): String {
        val valid = weeks.filter { it in 1..maxWeek }.toSortedSet()
        if (valid.isEmpty()) return ""
        val parts = mutableListOf<String>()
        var start = valid.first()
        var previous = start
        valid.drop(1).forEach { week ->
            if (week == previous + 1) {
                previous = week
                return@forEach
            }
            parts += rangeText(start, previous)
            start = week
            previous = week
        }
        parts += rangeText(start, previous)
        return parts.joinToString(",")
    }

    private fun rangeText(start: Int, end: Int): String =
        if (start == end) "$start" else "$start-$end"
}