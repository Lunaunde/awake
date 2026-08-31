package com.example.awake.data.repository

import androidx.room.withTransaction
import com.example.awake.data.local.AppDatabase
import com.example.awake.data.local.CourseEntity
import com.example.awake.data.local.CourseWeekEntity
import com.example.awake.data.local.ProfileEntity
import com.example.awake.data.local.PeriodConfigDefaults
import com.example.awake.data.local.TimetableEntity
import com.example.awake.domain.model.CourseSource
import com.example.awake.domain.model.SchoolCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

private val TIME_PATTERN = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")

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
        val id = if (profile.id == 0L) db.profileDao().insert(profile) else {
            db.profileDao().update(profile)
            profile.id
        }
        return profile.copy(id = id)
    }

    fun observeTimetables(profileId: Long): Flow<List<TimetableEntity>> = db.timetableDao().observeForProfile(profileId)
    suspend fun getTimetables(profileId: Long): List<TimetableEntity> = db.timetableDao().getAllForProfile(profileId)
    fun observeTimetable(id: Long): Flow<TimetableEntity?> = db.timetableDao().observeById(id)
    fun observeCourses(id: Long, week: Int): Flow<List<CourseEntity>> = db.courseDao().observeForWeek(id, week)
    fun observeCoursesThroughEnd(id: Long, week: Int): Flow<List<CourseEntity>> = db.courseDao().observeThroughEnd(id, week)
    fun observeAllCourses(id: Long): Flow<List<CourseEntity>> = db.courseDao().observeAll(id)
    fun observeCourse(id: Long): Flow<CourseEntity?> = db.courseDao().observeById(id)
    suspend fun getCourseOrNull(id: Long): CourseEntity? = db.courseDao().getById(id)
    suspend fun getAllCourses(timetableId: Long): List<CourseEntity> = db.courseDao().getAll(timetableId)
    fun observePeriodConfigs() = db.periodConfigDao().observeAll()
    suspend fun getPeriodConfigs() = db.periodConfigDao().getAll()
    suspend fun savePeriodConfigs(configs: List<com.example.awake.data.local.PeriodConfigEntity>) {
        require(configs.map { it.period }.distinct().size == configs.size) { "节次编号不能重复" }
        require(configs.all { it.period in 1..PeriodConfigDefaults.periodCount && TIME_PATTERN.matches(it.startTime) && TIME_PATTERN.matches(it.endTime) }) {
            "节次时间格式应为 HH:mm"
        }
        db.periodConfigDao().insertAll(configs)
    }
    suspend fun getTimetable(id: Long): TimetableEntity = db.timetableDao().getById(id) ?: error("课表不存在")
    suspend fun getTimetableOrNull(id: Long): TimetableEntity? = db.timetableDao().getById(id)
    suspend fun getFirstTimetable(): TimetableEntity? = db.profileDao().getActive()?.let { db.timetableDao().getFirstForProfile(it.id) }
    suspend fun findTimetable(profileId: Long, xnm: Int, xqm: String): TimetableEntity? =
        db.timetableDao().find(profileId, xnm, xqm)

    suspend fun createTimetable(profileId: Long, xnm: Int, xqm: String, label: String): TimetableEntity {
        val value = TimetableEntity(profileId = profileId, xnm = xnm, xqm = xqm, label = label)
        return value.copy(id = db.timetableDao().insert(value))
    }

    suspend fun updateTimetable(timetable: TimetableEntity) = db.timetableDao().update(timetable)

    suspend fun findOrCreateTimetable(profileId: Long, xnm: Int, xqm: String, label: String): TimetableEntity {
        return findTimetable(profileId, xnm, xqm) ?: createTimetable(profileId, xnm, xqm, label)
    }

    suspend fun deleteTimetable(id: Long) = db.withTransaction {
        db.timetableDao().deleteById(id)
    }

    suspend fun insertManualCourse(course: CourseEntity, weeks: Set<Int>): Long = db.withTransaction {
        val id = db.courseDao().insertCourses(listOf(course)).first()
        db.courseDao().insertWeeks(weeks.map { CourseWeekEntity(id, it) })
        id
    }

suspend fun updateCourse(course: CourseEntity, weeks: Set<Int>? = null) = db.withTransaction {
        db.courseDao().update(course)
        if (weeks != null) {
            db.courseDao().deleteWeeks(course.id)
            if (weeks.isNotEmpty()) {
                db.courseDao().insertWeeks(weeks.map { CourseWeekEntity(course.id, it) })
            }
        }
    }
    suspend fun deleteCourse(id: Long) = db.courseDao().deleteById(id)

    /** 清理旧版本可能混入正式课表的演示样例，不触碰用户真正的手动课程。 */
    suspend fun cleanupLegacyDemoCourses() = db.withTransaction {
        val profile = db.profileDao().getActive()
        if (profile != null) {
            db.timetableDao().getAllForProfile(profile.id)
                .filterNot { it.label.endsWith("（演示）") }
                .forEach { timetable ->
                    db.courseDao().deleteDemoWeeks(timetable.id)
                    db.courseDao().deleteDemoCourses(timetable.id)
                }
        }
    }
    /** weeks.courseId 在调用方传入时是 courses 列表下标，事务内会替换成真实 ID。 */
    suspend fun replaceRemoteCourses(timetable: TimetableEntity, courses: List<CourseEntity>, weeks: List<CourseWeekEntity>) = db.withTransaction {
        // 演示课表曾经把样例课程保存成 MANUAL；真实导入时必须先清理这类样例，不能把它们带入正式课表。
        db.courseDao().deleteDemoWeeks(timetable.id)
        db.courseDao().deleteDemoCourses(timetable.id)
        db.courseDao().deleteRemoteWeeks(timetable.id)
        db.courseDao().deleteRemoteForTimetable(timetable.id)
        val ids = db.courseDao().insertCourses(courses)
        val remappedWeeks = weeks.mapNotNull { week -> ids.getOrNull(week.courseId.toInt())?.let { CourseWeekEntity(it, week.weekNumber) } }
        if (remappedWeeks.isNotEmpty()) db.courseDao().insertWeeks(remappedWeeks)
        db.timetableDao().update(timetable.copy(lastSyncedAt = System.currentTimeMillis()))
    }

    suspend fun seedDemoTimetable(): TimetableEntity {
        val profile = ensureProfile()
        val demoLabel = "2026-2027 第一学期（演示）"
        // 演示课表必须独立创建，不能因为学期相同而复用真实教务课表。
        val original = getTimetables(profile.id).firstOrNull { it.label == demoLabel }
            ?: createTimetable(profile.id, 2026, "3", demoLabel)
        val timetable = if (original.startDate == null) {
            original.copy(startDate = LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)).toString()).also { updateTimetable(it) }
        } else original
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

