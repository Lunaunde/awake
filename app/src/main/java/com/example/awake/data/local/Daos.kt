package com.example.awake.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY id LIMIT 1") fun observeActive(): Flow<ProfileEntity?>
    @Query("SELECT * FROM profiles ORDER BY id LIMIT 1") suspend fun getActive(): ProfileEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(profile: ProfileEntity): Long
    @Update suspend fun update(profile: ProfileEntity)
    @Query("DELETE FROM profiles") suspend fun deleteAll()
}

@Dao
interface TimetableDao {
    @Query("SELECT * FROM timetables WHERE profileId = :profileId ORDER BY xnm DESC, xqm") fun observeForProfile(profileId: Long): Flow<List<TimetableEntity>>
    @Query("SELECT * FROM timetables WHERE id = :id LIMIT 1") fun observeById(id: Long): Flow<TimetableEntity?>
    @Query("SELECT * FROM timetables WHERE id = :id LIMIT 1") suspend fun getById(id: Long): TimetableEntity?
    @Query("SELECT * FROM timetables WHERE profileId = :profileId ORDER BY id LIMIT 1") suspend fun getFirstForProfile(profileId: Long): TimetableEntity?
    @Query("SELECT * FROM timetables WHERE profileId = :profileId ORDER BY id") suspend fun getAllForProfile(profileId: Long): List<TimetableEntity>
    @Query("SELECT * FROM timetables WHERE profileId = :profileId AND xnm = :xnm AND xqm = :xqm LIMIT 1") suspend fun find(profileId: Long, xnm: Int, xqm: String): TimetableEntity?
    @Query("SELECT * FROM timetables WHERE xnm = 0 AND xqm = 'legacy' LIMIT 1") suspend fun findLegacy(): TimetableEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(timetable: TimetableEntity): Long
    @Update suspend fun update(timetable: TimetableEntity)
    @Query("DELETE FROM timetables WHERE id = :id") suspend fun deleteById(id: Long)
    @Query("DELETE FROM timetables") suspend fun deleteAll()
}

@Dao
interface CourseDao {
    @Query("SELECT c.* FROM courses c WHERE c.timetableId = :timetableId AND EXISTS (SELECT 1 FROM course_weeks w WHERE w.courseId = c.id AND w.weekNumber = :week) ORDER BY c.dayOfWeek, c.startPeriod") fun observeForWeek(timetableId: Long, week: Int): Flow<List<CourseEntity>>
    // 显示非本周课程时，只保留当前周或未来仍有课的课程；课程最后一周结束后不再显示。
    @Query("SELECT c.* FROM courses c WHERE c.timetableId = :timetableId AND EXISTS (SELECT 1 FROM course_weeks w WHERE w.courseId = c.id AND w.weekNumber >= :week) ORDER BY c.dayOfWeek, c.startPeriod, c.name") fun observeThroughEnd(timetableId: Long, week: Int): Flow<List<CourseEntity>>
    @Query("SELECT * FROM courses WHERE timetableId = :timetableId ORDER BY dayOfWeek, startPeriod, name") suspend fun getAll(timetableId: Long): List<CourseEntity>
    @Query("SELECT * FROM courses WHERE timetableId = :timetableId ORDER BY dayOfWeek, startPeriod, name") fun observeAll(timetableId: Long): Flow<List<CourseEntity>>
    @Query("SELECT * FROM courses WHERE id = :id LIMIT 1") fun observeById(id: Long): Flow<CourseEntity?>
    @Query("SELECT * FROM courses WHERE id = :id LIMIT 1") suspend fun getById(id: Long): CourseEntity?
    @Query("SELECT id FROM timetables") suspend fun getAllTimetableIds(): List<Long>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertCourses(courses: List<CourseEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertWeeks(weeks: List<CourseWeekEntity>)
    @Query("DELETE FROM course_weeks WHERE courseId = :courseId") suspend fun deleteWeeks(courseId: Long)
    @Update suspend fun update(course: CourseEntity)
    @Query("DELETE FROM courses WHERE id = :id") suspend fun deleteById(id: Long)
    @Query("DELETE FROM courses WHERE timetableId = :timetableId AND source IN ('SCUT_KB', 'SCUT_SJK')") suspend fun deleteRemoteForTimetable(timetableId: Long)
    @Query("DELETE FROM course_weeks WHERE courseId IN (SELECT id FROM courses WHERE timetableId = :timetableId AND source IN ('SCUT_KB', 'SCUT_SJK'))") suspend fun deleteRemoteWeeks(timetableId: Long)
    // 清理旧版本把演示课程误标为 MANUAL 的数据，避免导入真实课表后继续显示演示课程。
    @Query("DELETE FROM course_weeks WHERE courseId IN (SELECT id FROM courses WHERE timetableId = :timetableId AND remoteKey LIKE 'demo-%')") suspend fun deleteDemoWeeks(timetableId: Long)
    @Query("DELETE FROM courses WHERE timetableId = :timetableId AND remoteKey LIKE 'demo-%'") suspend fun deleteDemoCourses(timetableId: Long)
}

@Dao
interface PeriodConfigDao {
    @Query("SELECT * FROM period_configs ORDER BY period") fun observeAll(): Flow<List<PeriodConfigEntity>>
    @Query("SELECT * FROM period_configs ORDER BY period") suspend fun getAll(): List<PeriodConfigEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(configs: List<PeriodConfigEntity>)
    @Query("DELETE FROM period_configs WHERE period > :maxPeriod") suspend fun deleteAfter(maxPeriod: Int)
}

