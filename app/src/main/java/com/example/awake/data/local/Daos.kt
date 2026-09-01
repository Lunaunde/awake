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

/** 扁平时段行的公共 SELECT 片段由各 @Query 展开书写（Room 不支持 SQL 片段复用）。 */
@Dao
interface CourseDao {
    // ---- 课程主记录 ----
    @Query("SELECT * FROM courses WHERE id = :id LIMIT 1") fun observeCourse(id: Long): Flow<CourseEntity?>
    @Query("SELECT * FROM courses WHERE id = :id LIMIT 1") suspend fun getCourse(id: Long): CourseEntity?
    @Query("SELECT * FROM courses WHERE timetableId = :timetableId AND source = :source AND remoteKey = :remoteKey LIMIT 1")
    suspend fun findCourse(timetableId: Long, source: String, remoteKey: String): CourseEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertCourse(course: CourseEntity): Long
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertCoursesStrict(courses: List<CourseEntity>): List<Long>
    @Update suspend fun updateCourse(course: CourseEntity)
    @Query("UPDATE courses SET color = :color WHERE id = :id")
    suspend fun updateCourseColor(id: Long, color: Int)
    @Query("DELETE FROM courses WHERE id = :id") suspend fun deleteCourseById(id: Long)
    @Query("DELETE FROM courses WHERE timetableId = :timetableId AND source IN ('SCUT_KB', 'SCUT_SJK')") suspend fun deleteRemoteForTimetable(timetableId: Long)
    @Query("SELECT * FROM courses WHERE timetableId = :timetableId AND source IN ('SCUT_KB', 'SCUT_SJK')")
    suspend fun getRemoteMasters(timetableId: Long): List<CourseEntity>

    // ---- 时段扁平行（含课程主记录信息）----
    @Query(
        """SELECT s.id AS sectionId, c.id AS courseId, c.timetableId AS timetableId, s.source AS source,
           c.name AS name, CASE WHEN s.teacher != '' THEN s.teacher ELSE c.teacher END AS teacher,
           s.room AS room, s.dayOfWeek AS dayOfWeek, s.startPeriod AS startPeriod, s.endPeriod AS endPeriod,
           c.color AS color, s.rawWeekText AS rawWeekText, s.locked AS locked
           FROM course_sections s JOIN courses c ON c.id = s.courseId
           WHERE c.timetableId = :timetableId AND EXISTS (SELECT 1 FROM course_weeks w WHERE w.sectionId = s.id AND w.weekNumber = :week)
           ORDER BY s.dayOfWeek, s.startPeriod, c.name"""
    )
    fun observeSlotsForWeek(timetableId: Long, week: Int): Flow<List<CourseSlotEntity>>

    // 显示非本周课程时，只保留当前周或未来仍有课的时段；课程最后一周结束后不再显示。
    @Query(
        """SELECT s.id AS sectionId, c.id AS courseId, c.timetableId AS timetableId, s.source AS source,
           c.name AS name, CASE WHEN s.teacher != '' THEN s.teacher ELSE c.teacher END AS teacher,
           s.room AS room, s.dayOfWeek AS dayOfWeek, s.startPeriod AS startPeriod, s.endPeriod AS endPeriod,
           c.color AS color, s.rawWeekText AS rawWeekText, s.locked AS locked
           FROM course_sections s JOIN courses c ON c.id = s.courseId
           WHERE c.timetableId = :timetableId AND EXISTS (SELECT 1 FROM course_weeks w WHERE w.sectionId = s.id AND w.weekNumber >= :week)
           ORDER BY s.dayOfWeek, s.startPeriod, c.name"""
    )
    fun observeSlotsThroughEnd(timetableId: Long, week: Int): Flow<List<CourseSlotEntity>>

    @Query(
        """SELECT s.id AS sectionId, c.id AS courseId, c.timetableId AS timetableId, s.source AS source,
           c.name AS name, CASE WHEN s.teacher != '' THEN s.teacher ELSE c.teacher END AS teacher,
           s.room AS room, s.dayOfWeek AS dayOfWeek, s.startPeriod AS startPeriod, s.endPeriod AS endPeriod,
           c.color AS color, s.rawWeekText AS rawWeekText, s.locked AS locked
           FROM course_sections s JOIN courses c ON c.id = s.courseId
           WHERE c.timetableId = :timetableId ORDER BY s.dayOfWeek, s.startPeriod, c.name"""
    )
    suspend fun getAllSlots(timetableId: Long): List<CourseSlotEntity>

    @Query(
        """SELECT s.id AS sectionId, c.id AS courseId, c.timetableId AS timetableId, s.source AS source,
           c.name AS name, CASE WHEN s.teacher != '' THEN s.teacher ELSE c.teacher END AS teacher,
           s.room AS room, s.dayOfWeek AS dayOfWeek, s.startPeriod AS startPeriod, s.endPeriod AS endPeriod,
           c.color AS color, s.rawWeekText AS rawWeekText, s.locked AS locked
           FROM course_sections s JOIN courses c ON c.id = s.courseId
           WHERE s.id = :sectionId LIMIT 1"""
    )
    fun observeSlot(sectionId: Long): Flow<CourseSlotEntity?>

    @Query(
        """SELECT s.id AS sectionId, c.id AS courseId, c.timetableId AS timetableId, s.source AS source,
           c.name AS name, CASE WHEN s.teacher != '' THEN s.teacher ELSE c.teacher END AS teacher,
           s.room AS room, s.dayOfWeek AS dayOfWeek, s.startPeriod AS startPeriod, s.endPeriod AS endPeriod,
           c.color AS color, s.rawWeekText AS rawWeekText, s.locked AS locked
           FROM course_sections s JOIN courses c ON c.id = s.courseId
           WHERE s.id = :sectionId LIMIT 1"""
    )
    suspend fun getSlot(sectionId: Long): CourseSlotEntity?

    // ---- 时段原始行 ----
    @Query("SELECT * FROM course_sections WHERE courseId = :courseId ORDER BY dayOfWeek, startPeriod")
    fun observeSections(courseId: Long): Flow<List<CourseSectionEntity>>
    @Query("SELECT * FROM course_sections WHERE courseId = :courseId ORDER BY dayOfWeek, startPeriod")
    suspend fun getSections(courseId: Long): List<CourseSectionEntity>
    @Query("SELECT * FROM course_sections WHERE id = :sectionId LIMIT 1") suspend fun getSectionById(sectionId: Long): CourseSectionEntity?
    @Query("SELECT * FROM course_sections WHERE courseId = :courseId AND source = :source AND remoteKey = :remoteKey LIMIT 1")
    suspend fun findSection(courseId: Long, source: String, remoteKey: String): CourseSectionEntity?
    @Query(
        "SELECT * FROM course_sections WHERE courseId = :courseId AND source = :source " +
            "AND dayOfWeek = :dayOfWeek AND startPeriod = :startPeriod AND endPeriod = :endPeriod LIMIT 1"
    )
    suspend fun findIdenticalSlot(courseId: Long, source: String, dayOfWeek: Int, startPeriod: Int, endPeriod: Int): CourseSectionEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSection(section: CourseSectionEntity): Long
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertSectionsStrict(sections: List<CourseSectionEntity>): List<Long>
    @Update suspend fun updateSection(section: CourseSectionEntity)
    @Query("DELETE FROM course_sections WHERE id = :sectionId") suspend fun deleteSectionById(sectionId: Long)
    @Query("SELECT COUNT(*) FROM course_sections WHERE courseId = :courseId") suspend fun countSections(courseId: Long): Int
    @Query(
        "SELECT s.* FROM course_sections s JOIN courses c ON c.id = s.courseId " +
            "WHERE c.timetableId = :timetableId ORDER BY s.dayOfWeek, s.startPeriod"
    )
    suspend fun getAllSectionsRaw(timetableId: Long): List<CourseSectionEntity>

    // ---- 周次 ----
    @Query("SELECT * FROM course_weeks WHERE sectionId = :sectionId ORDER BY weekNumber")
    suspend fun getWeeks(sectionId: Long): List<CourseWeekEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertWeeks(weeks: List<CourseWeekEntity>)
    @Query("DELETE FROM course_weeks WHERE sectionId = :sectionId") suspend fun deleteWeeks(sectionId: Long)

    // ---- 远程/演示数据清理 ----
    @Query(
        "DELETE FROM course_weeks WHERE sectionId IN (" +
            "SELECT s.id FROM course_sections s JOIN courses c ON c.id = s.courseId " +
            "WHERE c.timetableId = :timetableId AND c.remoteKey LIKE 'demo-%')"
    )
    suspend fun deleteDemoWeeks(timetableId: Long)
    @Query(
        "DELETE FROM course_sections WHERE courseId IN (" +
            "SELECT id FROM courses WHERE timetableId = :timetableId AND remoteKey LIKE 'demo-%')"
    )
    suspend fun deleteDemoSections(timetableId: Long)
    @Query("DELETE FROM courses WHERE timetableId = :timetableId AND remoteKey LIKE 'demo-%'")
    suspend fun deleteDemoCourses(timetableId: Long)
    @Query(
        "DELETE FROM course_weeks WHERE sectionId IN (" +
            "SELECT s.id FROM course_sections s JOIN courses c ON c.id = s.courseId " +
            "WHERE c.timetableId = :timetableId AND c.source IN ('SCUT_KB', 'SCUT_SJK'))"
    )
    suspend fun deleteRemoteWeeks(timetableId: Long)
    @Query(
        "DELETE FROM course_sections WHERE courseId IN (" +
            "SELECT id FROM courses WHERE timetableId = :timetableId AND source IN ('SCUT_KB', 'SCUT_SJK'))"
    )
    suspend fun deleteRemoteSections(timetableId: Long)
    @Query("SELECT id FROM timetables") suspend fun getAllTimetableIds(): List<Long>
    @Query("DELETE FROM course_weeks") suspend fun deleteAllWeeks()
    @Query("DELETE FROM course_sections") suspend fun deleteAllSections()
    @Query("DELETE FROM courses") suspend fun deleteAllCourses()

    // ---- JSON 导出/导入（整表替换）----
    @Query("SELECT * FROM courses WHERE timetableId = :timetableId ORDER BY id")
    suspend fun getAllMasters(timetableId: Long): List<CourseEntity>
    @Query(
        "DELETE FROM course_weeks WHERE sectionId IN (" +
            "SELECT s.id FROM course_sections s JOIN courses c ON c.id = s.courseId " +
            "WHERE c.timetableId = :timetableId)"
    )
    suspend fun deleteWeeksFor(timetableId: Long)
    @Query("DELETE FROM course_sections WHERE courseId IN (SELECT id FROM courses WHERE timetableId = :timetableId)")
    suspend fun deleteSectionsFor(timetableId: Long)
    @Query("DELETE FROM courses WHERE timetableId = :timetableId")
    suspend fun deleteCoursesFor(timetableId: Long)
}

@Dao
interface PeriodConfigDao {
    @Query("SELECT * FROM period_configs WHERE timetableId = 0 ORDER BY period")
    fun observeDefaults(): Flow<List<PeriodConfigEntity>>
    @Query("SELECT * FROM period_configs WHERE timetableId = :timetableId ORDER BY period")
    fun observeFor(timetableId: Long): Flow<List<PeriodConfigEntity>>
    @Query("SELECT * FROM period_configs WHERE timetableId = 0 ORDER BY period")
    suspend fun getDefaults(): List<PeriodConfigEntity>
    @Query("SELECT * FROM period_configs WHERE timetableId = :timetableId ORDER BY period")
    suspend fun getFor(timetableId: Long): List<PeriodConfigEntity>
    @Query("SELECT * FROM period_configs ORDER BY period")
    suspend fun getAll(): List<PeriodConfigEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(configs: List<PeriodConfigEntity>)
    @Query("DELETE FROM period_configs WHERE timetableId = 0 AND period > :maxPeriod")
    suspend fun deleteDefaultAfter(maxPeriod: Int)
    @Query("DELETE FROM period_configs WHERE timetableId = :timetableId")
    suspend fun deleteFor(timetableId: Long)
}