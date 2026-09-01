package com.example.awake.data.local

import androidx.room.withTransaction
import com.example.awake.domain.parser.WeekExpressionParser

/** 迁移旧表并按 rawWeekText 修复时段周次关系；无法识别的表达式保留原有关系。 */
class LegacyCourseImporter(private val db: AppDatabase) {
    suspend fun expandMissingWeeks() = db.withTransaction {
        db.courseDao().getAllTimetableIds().forEach { timetableId ->
            val timetable = db.timetableDao().getById(timetableId) ?: return@forEach
            db.courseDao().getAllSectionsRaw(timetable.id).forEach { section ->
                val weeks = WeekExpressionParser.parse(section.rawWeekText, timetable.totalWeeks).weeks
                if (weeks.isNotEmpty()) {
                    // 旧版本或编辑时段时可能只更新了 rawWeekText，导致周次关系表过期。
                    db.courseDao().deleteWeeks(section.id)
                    db.courseDao().insertWeeks(weeks.map { CourseWeekEntity(section.id, it) })
                }
            }
        }
    }
}