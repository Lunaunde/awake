package com.example.awake.data.local

import androidx.room.withTransaction
import com.example.awake.domain.parser.WeekExpressionParser

/** 迁移旧表后补齐周次关系；无法识别的表达式保留在 rawWeekText 中。 */
class LegacyCourseImporter(private val db: AppDatabase) {
    suspend fun expandMissingWeeks() = db.withTransaction {
        val timetable = db.timetableDao().findLegacy() ?: return@withTransaction
        db.courseDao().getAll(timetable.id)
            .filter { it.source == "MIGRATED_LEGACY" }
            .forEach { course ->
                val weeks = WeekExpressionParser.parse(course.rawWeekText, timetable.totalWeeks).weeks
                if (weeks.isNotEmpty()) {
                    db.courseDao().insertWeeks(weeks.map { CourseWeekEntity(course.id, it) })
                }
            }
    }
}
