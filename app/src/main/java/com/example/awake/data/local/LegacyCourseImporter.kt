package com.example.awake.data.local

import android.content.Context
import androidx.room.withTransaction
import com.example.awake.domain.parser.WeekExpressionParser

/** 迁移旧表后补齐周次关系；无法识别的表达式保留在 rawWeekText 中。 */
class LegacyCourseImporter(private val db: AppDatabase) {
    suspend fun expandMissingWeeks() = db.withTransaction {
        val timetables = db.timetableDao().getById(legacyTimetableId())
        if (timetables == null) return@withTransaction
        db.courseDao().getAll(timetables.id).filter { it.source == "MIGRATED_LEGACY" }.forEach { course ->
            val weeks = WeekExpressionParser.parse(course.rawWeekText, timetables.totalWeeks).weeks
            if (weeks.isNotEmpty()) {
                db.courseDao().insertWeeks(weeks.map { CourseWeekEntity(course.id, it) })
            }
        }
    }

    private suspend fun legacyTimetableId(): Long = db.timetableDao().find(1, 0, "legacy")?.id ?: 0L
}
