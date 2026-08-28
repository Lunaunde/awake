package com.example.awake.data.repository

import androidx.room.withTransaction
import com.example.awake.data.local.AppDatabase
import com.example.awake.data.local.CourseEntity
import com.example.awake.data.local.CourseWeekEntity
import com.example.awake.data.local.ProfileEntity
import com.example.awake.data.local.TimetableEntity
import com.example.awake.domain.model.CourseSource
import com.example.awake.domain.model.SchoolCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LocalTimetableRepository(private val db: AppDatabase) {
    val activeProfile = db.profileDao().observeActive().map { it?.toDomain() }

    suspend fun ensureProfile(): ProfileEntity = db.profileDao().getActive() ?: ProfileEntity(
        schoolCode = SchoolCode.SCUT.code, displayName = "未登录"
    ).let { it.copy(id = db.profileDao().insert(it)) }

    suspend fun saveLoggedInProfile(displayName: String?, studentId: String?): ProfileEntity {
        val existing = db.profileDao().getActive()
        val profile = (existing ?: ProfileEntity()).copy(
            schoolCode = SchoolCode.SCUT.code,
            displayName = displayName ?: existing?.displayName,
            maskedStudentId = studentId?.maskStudentId() ?: existing?.maskedStudentId,
            lastLoginAt = System.currentTimeMillis()
        )
        val id = if (profile.id == 0L) db.profileDao().insert(profile) else { db.profileDao().update(profile); profile.id }
        return profile.copy(id = id)
    }

    fun observeTimetables(profileId: Long): Flow<List<TimetableEntity>> = db.timetableDao().observeForProfile(profileId)
    fun observeTimetable(id: Long): Flow<TimetableEntity?> = db.timetableDao().observeById(id)
    fun observeCourses(id: Long, week: Int): Flow<List<CourseEntity>> = db.courseDao().observeForWeek(id, week)
    fun observeCourse(id: Long): Flow<CourseEntity?> = db.courseDao().observeById(id)
    suspend fun getTimetable(id: Long): TimetableEntity = db.timetableDao().getById(id) ?: error("课表不存在")

    suspend fun findOrCreateTimetable(profileId: Long, xnm: Int, xqm: String, label: String): TimetableEntity {
        return db.timetableDao().find(profileId, xnm, xqm) ?: run {
            val value = TimetableEntity(profileId = profileId, xnm = xnm, xqm = xqm, label = label)
            value.copy(id = db.timetableDao().insert(value))
        }
    }

    suspend fun insertManualCourse(course: CourseEntity, weeks: Set<Int>): Long = db.withTransaction {
        val id = db.courseDao().insertCourses(listOf(course)).first()
        db.courseDao().insertWeeks(weeks.map { CourseWeekEntity(id, it) })
        id
    }

    suspend fun updateCourse(course: CourseEntity) = db.courseDao().update(course)
    suspend fun deleteCourse(id: Long) = db.courseDao().deleteById(id)

    /** weeks.courseId 在调用方传入时是 courses 列表下标，事务内会替换成真实 ID。 */
    suspend fun replaceRemoteCourses(timetable: TimetableEntity, courses: List<CourseEntity>, weeks: List<CourseWeekEntity>) = db.withTransaction {
        db.courseDao().deleteRemoteWeeks(timetable.id)
        db.courseDao().deleteRemoteForTimetable(timetable.id)
        val ids = db.courseDao().insertCourses(courses)
        val remappedWeeks = weeks.mapNotNull { week -> ids.getOrNull(week.courseId.toInt())?.let { CourseWeekEntity(it, week.weekNumber) } }
        if (remappedWeeks.isNotEmpty()) db.courseDao().insertWeeks(remappedWeeks)
        db.timetableDao().update(timetable.copy(lastSyncedAt = System.currentTimeMillis()))
    }

    suspend fun seedDemoTimetable(): TimetableEntity {
        val profile = ensureProfile()
        val timetable = findOrCreateTimetable(profile.id, 2026, "3", "2026-2027 第一学期（演示）")
        val existing = db.courseDao().getAll(timetable.id)
        if (existing.none { it.remoteKey.startsWith("demo") }) {
            val samples = listOf(
                CourseEntity(timetableId = timetable.id, source = "MANUAL", remoteKey = "demo-math", name = "高等数学", teacher = "张老师", room = "A101", dayOfWeek = 1, startPeriod = 1, endPeriod = 2, color = 0xff4f6bed.toInt(), rawWeekText = "1-16"),
                CourseEntity(timetableId = timetable.id, source = "MANUAL", remoteKey = "demo-english", name = "大学英语", teacher = "李老师", room = "B202", dayOfWeek = 3, startPeriod = 3, endPeriod = 4, color = 0xff0f9d78.toInt(), rawWeekText = "1-16"),
                CourseEntity(timetableId = timetable.id, source = "MANUAL", remoteKey = "demo-lab", name = "程序设计实践", teacher = "王老师", room = "实验楼 302", dayOfWeek = 5, startPeriod = 7, endPeriod = 9, color = 0xffd97706.toInt(), rawWeekText = "单周")
            )
            samples.forEach { insertManualCourse(it, com.example.awake.domain.parser.WeekExpressionParser.parse(it.rawWeekText).weeks) }
        }
        return timetable
    }

    suspend fun deleteAll() = db.withTransaction {
        db.courseDao().getAllTimetableIds().forEach { timetableId ->
            db.courseDao().getAll(timetableId).forEach { db.courseDao().deleteById(it.id) }
        }
        db.timetableDao().deleteAll()
        db.profileDao().deleteAll()
    }

    private fun String.maskStudentId(): String = if (length <= 4) "****" else take(2) + "****" + takeLast(2)
    private fun ProfileEntity.toDomain() = com.example.awake.domain.model.Profile(id, SchoolCode.SCUT, maskedStudentId, displayName, lastLoginAt)
}


