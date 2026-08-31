package com.example.awake.data.local

import androidx.room.withTransaction
import com.example.awake.domain.parser.WeekExpressionParser

/** 迁移旧表并按 rawWeekText 修复课程周次关系；无法识别的表达式保留原有关系。 */
class LegacyCourseImporter(private val db: AppDatabase) {
    suspend fun expandMissingWeeks() = db.withTransaction {
        db.courseDao().getAllTimetableIds().forEach { timetableId ->
            val timetable = db.timetableDao().getById(timetableId) ?: return@forEach
            db.courseDao().getAll(timetable.id).forEach { course ->
                val weeks = WeekExpressionParser.parse(course.rawWeekText, timetable.totalWeeks).weeks
                if (weeks.isNotEmpty()) {
                    // 旧版本或编辑课程时可能只更新了 rawWeekText，导致单双周关系表过期。
                    db.courseDao().deleteWeeks(course.id)
                    db.courseDao().insertWeeks(weeks.map { CourseWeekEntity(course.id, it) })
                }
            }
        }
    }
}