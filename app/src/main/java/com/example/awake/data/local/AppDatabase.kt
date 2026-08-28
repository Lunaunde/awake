package com.example.awake.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProfileEntity::class, TimetableEntity::class, CourseEntity::class, CourseWeekEntity::class, PeriodConfigEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun timetableDao(): TimetableDao
    abstract fun courseDao(): CourseDao
    abstract fun periodConfigDao(): PeriodConfigDao

    companion object {
        /** 旧 CourseDBHelper 使用 user_version=2；迁移只增加新表，不删除旧表。 */
        val LEGACY_MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS profiles (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, schoolCode TEXT NOT NULL, maskedStudentId TEXT, displayName TEXT, lastLoginAt INTEGER)")
                db.execSQL("CREATE TABLE IF NOT EXISTS timetables (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, profileId INTEGER NOT NULL, schoolCode TEXT NOT NULL, xnm INTEGER NOT NULL, xqm TEXT NOT NULL, label TEXT NOT NULL, startDate TEXT, totalWeeks INTEGER NOT NULL, lastSyncedAt INTEGER, FOREIGN KEY(profileId) REFERENCES profiles(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_timetables_profileId_xnm_xqm ON timetables(profileId, xnm, xqm)")
                db.execSQL("CREATE TABLE IF NOT EXISTS courses_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, timetableId INTEGER NOT NULL, source TEXT NOT NULL, remoteKey TEXT NOT NULL, name TEXT NOT NULL, teacher TEXT NOT NULL, room TEXT NOT NULL, dayOfWeek INTEGER NOT NULL, startPeriod INTEGER NOT NULL, endPeriod INTEGER NOT NULL, credits TEXT, totalHours TEXT, courseType TEXT, assessment TEXT, className TEXT, color INTEGER NOT NULL, rawWeekText TEXT NOT NULL, FOREIGN KEY(timetableId) REFERENCES timetables(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_courses_timetableId_source_remoteKey ON courses_new(timetableId, source, remoteKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_courses_timetableId ON courses_new(timetableId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS course_weeks (courseId INTEGER NOT NULL, weekNumber INTEGER NOT NULL, PRIMARY KEY(courseId, weekNumber), FOREIGN KEY(courseId) REFERENCES courses_new(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_course_weeks_weekNumber ON course_weeks(weekNumber)")
                db.execSQL("CREATE TABLE IF NOT EXISTS period_configs (period INTEGER NOT NULL PRIMARY KEY, startTime TEXT NOT NULL, endTime TEXT NOT NULL)")
                db.execSQL("INSERT INTO profiles(schoolCode, maskedStudentId, displayName, lastLoginAt) SELECT 'SCUT', NULL, '历史数据', NULL WHERE NOT EXISTS (SELECT 1 FROM profiles)")
                db.execSQL("INSERT INTO timetables(profileId, schoolCode, xnm, xqm, label, startDate, totalWeeks, lastSyncedAt) SELECT (SELECT id FROM profiles ORDER BY id LIMIT 1), 'SCUT', 0, 'legacy', '历史手工课表', NULL, 30, NULL WHERE NOT EXISTS (SELECT 1 FROM timetables WHERE xnm=0 AND xqm='legacy')")
                db.execSQL("INSERT INTO courses_new(id, timetableId, source, remoteKey, name, teacher, room, dayOfWeek, startPeriod, endPeriod, credits, totalHours, courseType, assessment, className, color, rawWeekText) SELECT id, (SELECT id FROM timetables WHERE xnm=0 AND xqm='legacy' LIMIT 1), 'MIGRATED_LEGACY', 'legacy_' || id, name, COALESCE(teacher,''), COALESCE(room,''), day, start, end, NULL, NULL, NULL, NULL, NULL, color, week_config FROM courses WHERE NOT EXISTS (SELECT 1 FROM courses_new WHERE remoteKey='legacy_' || courses.id)")
                db.execSQL("DROP TABLE IF EXISTS courses")
                db.execSQL("ALTER TABLE courses_new RENAME TO courses")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_courses_timetableId_source_remoteKey ON courses(timetableId, source, remoteKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_courses_timetableId ON courses(timetableId)")
            }
        }
    }
}
