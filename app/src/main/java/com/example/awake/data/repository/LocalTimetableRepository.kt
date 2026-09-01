package com.example.awake.data.repository

import androidx.room.withTransaction
import com.example.awake.data.local.AppDatabase
import com.example.awake.data.local.CourseEntity
import com.example.awake.data.local.CourseSectionEntity
import com.example.awake.data.local.CourseSlotEntity
import com.example.awake.data.local.CourseWeekEntity
import com.example.awake.data.local.ProfileEntity
import com.example.awake.data.local.PeriodConfigDefaults
import com.example.awake.data.local.PeriodConfigEntity
import com.example.awake.data.local.TimetableEntity
import com.example.awake.domain.model.CourseIdentity
import com.example.awake.domain.model.SchoolCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
    fun observeCourses(id: Long, week: Int): Flow<List<CourseSlotEntity>> = db.courseDao().observeSlotsForWeek(id, week)
    fun observeCoursesThroughEnd(id: Long, week: Int): Flow<List<CourseSlotEntity>> = db.courseDao().observeSlotsThroughEnd(id, week)
    fun observeCourse(id: Long): Flow<CourseEntity?> = db.courseDao().observeCourse(id)
    suspend fun getAllSlots(timetableId: Long): List<CourseSlotEntity> = db.courseDao().getAllSlots(timetableId)
    fun observeSections(courseId: Long): Flow<List<CourseSectionEntity>> = db.courseDao().observeSections(courseId)
    fun observeSlot(sectionId: Long): Flow<CourseSlotEntity?> = db.courseDao().observeSlot(sectionId)
    suspend fun getSlotOrNull(sectionId: Long): CourseSlotEntity? = db.courseDao().getSlot(sectionId)
    suspend fun getSectionOrNull(sectionId: Long): CourseSectionEntity? = db.courseDao().getSectionById(sectionId)
    suspend fun getCourseOrNull(id: Long): CourseEntity? = db.courseDao().getCourse(id)
    /** 时段设置跟随课表：没有独立配置的课表回退到全局默认（timetableId = 0）。 */
    suspend fun getPeriodConfigsFor(timetableId: Long): List<PeriodConfigEntity> {
        val custom = db.periodConfigDao().getFor(timetableId)
        return custom.ifEmpty { db.periodConfigDao().getDefaults() }
    }

    fun observePeriodConfigsFor(timetableId: Long): Flow<List<PeriodConfigEntity>> = kotlinx.coroutines.flow.combine(
        db.periodConfigDao().observeFor(timetableId),
        db.periodConfigDao().observeDefaults()
    ) { custom, defaults -> if (custom.isEmpty()) defaults else custom }

    suspend fun getPeriodConfigs() = db.periodConfigDao().getDefaults()
    fun observePeriodConfigs() = db.periodConfigDao().observeDefaults()
    suspend fun savePeriodConfigs(timetableId: Long, configs: List<com.example.awake.data.local.PeriodConfigEntity>) {
        require(configs.map { it.period }.distinct().size == configs.size) { "节次编号不能重复" }
        require(configs.all { it.period in 1..PeriodConfigDefaults.periodCount && TIME_PATTERN.matches(it.startTime) && TIME_PATTERN.matches(it.endTime) }) {
            "节次时间格式应为 HH:mm"
        }
        db.withTransaction {
            db.periodConfigDao().deleteFor(timetableId)
            db.periodConfigDao().insertAll(configs.map { it.copy(timetableId = timetableId) })
        }
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
        // 课表删除时同步清理它的独立节次配置。
        db.periodConfigDao().deleteFor(id)
        db.timetableDao().deleteById(id)
    }

    /**
     * 手动新增一个时段：身份相同（名称+教师）的手动课程会复用已有主记录，不同时段成为新子记录。
     * 时段键相同、或“星期+节次”完全相同的既有时段会被复用更新（兼容迁移后的旧时段键），
     * 避免手动重复添加产生重复行。返回主课程 ID。
     */
    suspend fun insertManualCourse(course: CourseEntity, section: CourseSectionEntity, weeks: Set<Int>): Long = db.withTransaction {
        val masterId = db.courseDao().findCourse(course.timetableId, course.source, course.remoteKey)?.id
            ?: db.courseDao().insertCourse(course.copy(id = 0))
        val existingSection = db.courseDao().findSection(masterId, section.source, section.remoteKey)
            ?: db.courseDao().findIdenticalSlot(masterId, section.source, section.dayOfWeek, section.startPeriod, section.endPeriod)
        val sectionId = if (existingSection != null) {
            db.courseDao().updateSection(section.copy(id = existingSection.id, courseId = masterId))
            existingSection.id
        } else {
            db.courseDao().insertSection(section.copy(id = 0, courseId = masterId))
        }
        db.courseDao().deleteWeeks(sectionId)
        if (weeks.isNotEmpty()) {
            db.courseDao().insertWeeks(weeks.map { CourseWeekEntity(sectionId, it) })
        }
        masterId
    }

    /** 编辑课程主记录（名称/教师/学分等）；周次与时间在时段层维护。 */
    suspend fun updateCourse(course: CourseEntity) = db.withTransaction {
        db.courseDao().updateCourse(course)
    }

    /** 立即把颜色写库（点选即存，不等待“保存课程信息”）。 */
    suspend fun updateCourseColor(courseId: Long, color: Int) {
        db.courseDao().updateCourseColor(courseId, color or 0xFF000000.toInt())
    }

    /** 编辑单个时段；周次文本变化时同步重建该时段的 week 关系。 */
    suspend fun updateSection(section: CourseSectionEntity, weeks: Set<Int>? = null) = db.withTransaction {
        db.courseDao().updateSection(section)
        if (weeks != null) {
            db.courseDao().deleteWeeks(section.id)
            if (weeks.isNotEmpty()) {
                db.courseDao().insertWeeks(weeks.map { CourseWeekEntity(section.id, it) })
            }
        }
    }

    suspend fun deleteSection(sectionId: Long) = db.withTransaction {
        val section = db.courseDao().getSectionById(sectionId) ?: return@withTransaction
        db.courseDao().deleteWeeks(sectionId)
        db.courseDao().deleteSectionById(sectionId)
        if (db.courseDao().countSections(section.courseId) == 0) {
            db.courseDao().deleteCourseById(section.courseId)
        }
    }

    suspend fun deleteCourse(id: Long) = db.courseDao().deleteCourseById(id)

    /** 清理旧版本可能混入正式课表的演示样例，不触碰用户真正的手动课程。 */
    suspend fun cleanupLegacyDemoCourses() = db.withTransaction {
        val profile = db.profileDao().getActive()
        if (profile != null) {
            db.timetableDao().getAllForProfile(profile.id)
                .filterNot { it.label.endsWith("（演示）") }
                .forEach { timetable ->
                    db.courseDao().deleteDemoWeeks(timetable.id)
                    db.courseDao().deleteDemoSections(timetable.id)
                    db.courseDao().deleteDemoCourses(timetable.id)
                }
        }
    }

    /**
     * 原子替换课表内的教务同步课程。
     * courses 为去重主记录；sections.courseId 是主课程下标、weeks.sectionId 是时段下标，
     * 事务内统一重映射。任一步冲突抛异常时整体回滚，旧课表保留。
     */
    suspend fun replaceRemoteCourses(
        timetable: TimetableEntity,
        courses: List<CourseEntity>,
        sections: List<CourseSectionEntity>,
        weeks: List<CourseWeekEntity>
    ) = db.withTransaction {
        // 用户在详情页改过颜色的课程（≠ 默认算法色），在整删重建后按 (source, remoteKey) 原样带回，
        // 避免自动刷新看起来“没有落库”。
        val customizedColors = db.courseDao().getRemoteMasters(timetable.id)
            .filter { it.color != com.example.awake.domain.model.defaultCourseColor(it.remoteKey) }
            .associate { (it.source to it.remoteKey) to it.color }
        db.courseDao().deleteDemoWeeks(timetable.id)
        db.courseDao().deleteDemoSections(timetable.id)
        db.courseDao().deleteDemoCourses(timetable.id)
        db.courseDao().deleteRemoteWeeks(timetable.id)
        db.courseDao().deleteRemoteSections(timetable.id)
        db.courseDao().deleteRemoteForTimetable(timetable.id)
        val adjusted = courses.map { master ->
            customizedColors[master.source to master.remoteKey]?.let { master.copy(color = it) } ?: master
        }
        val masterIds = db.courseDao().insertCoursesStrict(adjusted)
        val sectionIds = sections.map { section ->
            val masterId = masterIds.getOrNull(section.courseId.toInt())
                ?: error("时段引用了不存在的主课程下标 ${section.courseId}")
            db.courseDao().insertSection(section.copy(id = 0, courseId = masterId))
        }
        val remapped = weeks.mapNotNull { week ->
            sectionIds.getOrNull(week.sectionId.toInt())?.let { CourseWeekEntity(it, week.weekNumber) }
        }
        if (remapped.isNotEmpty()) db.courseDao().insertWeeks(remapped)
        db.timetableDao().update(timetable.copy(lastSyncedAt = System.currentTimeMillis()))
    }

    // ---- JSON 分享导出 / 导入 ----

    /** 导出当前课表完整 JSON：学期元数据 + 全部主课程与时段（含周次、颜色）。 */
    suspend fun exportTimetableJson(timetableId: Long): String? {
        val timetable = db.timetableDao().getById(timetableId) ?: return null
        val masters = db.courseDao().getAllMasters(timetableId)
        val meta = com.example.awake.data.export.TimetableJson.JsonTimetableMeta(
            label = timetable.label,
            xnm = timetable.xnm,
            xqm = timetable.xqm,
            startDate = timetable.startDate,
            totalWeeks = timetable.totalWeeks
        )
        val courses = masters.map { master ->
            val sections = db.courseDao().getSections(master.id).map { section ->
                com.example.awake.data.export.TimetableJson.JsonSection(
                    dayOfWeek = section.dayOfWeek,
                    startPeriod = section.startPeriod,
                    endPeriod = section.endPeriod,
                    room = section.room,
                    teacher = section.teacher,
                    rawWeekText = section.rawWeekText
                )
            }
            com.example.awake.data.export.TimetableJson.JsonCourse(
                source = master.source,
                name = master.name,
                teacher = master.teacher,
                color = master.color,
                credits = master.credits,
                totalHours = master.totalHours,
                courseType = master.courseType,
                assessment = master.assessment,
                className = master.className,
                sections = sections
            )
        }
        return com.example.awake.data.export.TimetableJson.toString(meta, courses)
    }

    /**
     * JSON 导入 = 整表替换：目标课表的全部课程（含手动课）被分享文本中的内容重建。
     * 仅用于“从 JSON 新建课表”的路径，不参与教务同步。
     */
    suspend fun replaceAllCourses(
        timetable: TimetableEntity,
        courses: List<CourseEntity>,
        sections: List<CourseSectionEntity>,
        weeks: List<CourseWeekEntity>
    ) = db.withTransaction {
        db.courseDao().deleteWeeksFor(timetable.id)
        db.courseDao().deleteSectionsFor(timetable.id)
        db.courseDao().deleteCoursesFor(timetable.id)
        val masterIds = db.courseDao().insertCoursesStrict(courses)
        val sectionIds = sections.map { section ->
            val masterId = masterIds.getOrNull(section.courseId.toInt())
                ?: error("时段引用了不存在的主课程下标 ${section.courseId}")
            db.courseDao().insertSection(section.copy(id = 0, courseId = masterId))
        }
        val remapped = weeks.mapNotNull { week ->
            sectionIds.getOrNull(week.sectionId.toInt())?.let { CourseWeekEntity(it, week.weekNumber) }
        }
        if (remapped.isNotEmpty()) db.courseDao().insertWeeks(remapped)
    }

    /**
     * 从分享文本创建/覆盖课表：
     * - overrideTargetId != null：整表替换目标课表（元数据一并更新），失败恢复原元数据；
     * - 否则新建课表（名称重复自动加“（新建）”后缀）。
     */
    suspend fun importTimetableFromJson(
        profileId: Long,
        data: com.example.awake.data.export.TimetableJson.JsonTimetableData,
        overrideTargetId: Long? = null
    ): TimetableEntity {
        val timetable: TimetableEntity
        var originalMeta: TimetableEntity? = null
        if (overrideTargetId != null) {
            val target = getTimetableOrNull(overrideTargetId) ?: error("要覆盖的课表不存在")
            originalMeta = target
            timetable = target.copy(
                label = data.meta.label,
                xnm = data.meta.xnm,
                xqm = data.meta.xqm,
                startDate = data.meta.startDate,
                totalWeeks = data.meta.totalWeeks
            ).also { updateTimetable(it) }
        } else {
            val labels = getTimetables(profileId).map { it.label }.toSet()
            var label = data.meta.label
            if (label in labels) {
                val base = "$label（新建）"
                label = if (base !in labels) {
                    base
                } else {
                    var suffix = 2
                    while ("$base $suffix" in labels) suffix++
                    "$base $suffix"
                }
            }
            timetable = createTimetable(profileId, data.meta.xnm, data.meta.xqm, label).copy(
                startDate = data.meta.startDate,
                totalWeeks = data.meta.totalWeeks
            ).also { updateTimetable(it) }
        }
        try {
            val (courses, sections, weeks) = buildJsonEntities(timetable, data)
            replaceAllCourses(timetable, courses, sections, weeks)
        } catch (error: Throwable) {
            originalMeta?.let { updateTimetable(it) }
            throw error
        }
        return timetable
    }

    private fun buildJsonEntities(
        timetable: TimetableEntity,
        data: com.example.awake.data.export.TimetableJson.JsonTimetableData
    ): Triple<List<CourseEntity>, List<CourseSectionEntity>, List<CourseWeekEntity>> {
        val courses = mutableListOf<CourseEntity>()
        val sections = mutableListOf<CourseSectionEntity>()
        val weeks = mutableListOf<CourseWeekEntity>()
        val usedColors = mutableListOf<Int>()
        data.courses.forEach { jsonCourse ->
            val index = courses.size
            val color = jsonCourse.color
                ?.takeIf { it != 0 }
                ?: com.example.awake.domain.model.pickNewCourseColor(usedColors)
            usedColors += color
            courses += CourseEntity(
                timetableId = timetable.id,
                source = jsonCourse.source,
                remoteKey = CourseIdentity.masterKey(jsonCourse.source, jsonCourse.name, jsonCourse.className, jsonCourse.teacher),
                name = jsonCourse.name,
                teacher = jsonCourse.teacher,
                credits = jsonCourse.credits,
                totalHours = jsonCourse.totalHours,
                courseType = jsonCourse.courseType,
                assessment = jsonCourse.assessment,
                className = jsonCourse.className,
                color = color
            )
            jsonCourse.sections.forEach { jsonSection ->
                val sectionIndex = sections.size
                sections += CourseSectionEntity(
                    courseId = index.toLong(),
                    source = jsonCourse.source,
                    remoteKey = CourseIdentity.sectionKey(
                        jsonCourse.source, jsonCourse.name, jsonSection.teacher, jsonSection.room,
                        jsonSection.dayOfWeek, "${jsonSection.startPeriod}-${jsonSection.endPeriod}",
                        jsonSection.rawWeekText, jsonCourse.className
                    ),
                    dayOfWeek = jsonSection.dayOfWeek,
                    startPeriod = jsonSection.startPeriod,
                    endPeriod = jsonSection.endPeriod,
                    room = jsonSection.room,
                    teacher = jsonSection.teacher,
                    rawWeekText = jsonSection.rawWeekText
                )
                val parsed = com.example.awake.domain.parser.WeekExpressionParser.parse(jsonSection.rawWeekText, maxWeek = 60)
                parsed.weeks.forEach { weeks += CourseWeekEntity(sectionIndex.toLong(), it) }
            }
        }
        return Triple(courses, sections, weeks)
    }

    suspend fun deleteAll() = db.withTransaction {
        db.courseDao().deleteAllWeeks()
        db.courseDao().deleteAllSections()
        db.courseDao().deleteAllCourses()
        db.timetableDao().deleteAll()
        db.profileDao().deleteAll()
    }

    private fun String.maskStudentId(): String = if (length <= 4) "****" else take(2) + "****" + takeLast(2)
    private fun ProfileEntity.toDomain() = com.example.awake.domain.model.Profile(id, SchoolCode.SCUT, maskedStudentId, displayName, lastLoginAt)
}