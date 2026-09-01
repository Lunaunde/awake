package com.example.awake.domain.model

import java.security.MessageDigest

/**
 * 课程身份与时段键的唯一生成规则。
 *
 * - 主课程键：同一来源下「课程名 + 教学班号(jxbmc)」视为同一门课；
 *   教学班号缺失时退化为「课程名 + 教师」，避免把未知教学班的不同教师课程误合并。
 * - 时段键：完整教务字段哈希，与 v4 版本 courses.remoteKey 的输入完全一致，
 *   保证历史数据迁移与新增数据可以使用同一套键做匹配。
 */
object CourseIdentity {

    fun masterKey(source: String, name: String, className: String?, teacher: String): String =
        digest(listOf(source, name, className?.takeIf { it.isNotBlank() } ?: teacher))

    fun sectionKey(
        source: String,
        name: String,
        teacher: String,
        room: String,
        day: Int,
        periods: String,
        weeks: String,
        className: String?
    ): String = digest(listOf(source, name, teacher, room, day.toString(), periods, weeks, className))

    private fun digest(parts: List<String?>): String {
        val joined = parts.joinToString("|")
        val bytes = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}