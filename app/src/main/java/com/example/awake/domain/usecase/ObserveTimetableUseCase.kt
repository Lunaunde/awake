package com.example.awake.domain.usecase

import com.example.awake.data.local.CourseEntity
import com.example.awake.data.local.CourseSectionEntity
import com.example.awake.data.local.CourseSlotEntity
import com.example.awake.data.local.TimetableEntity
import com.example.awake.data.repository.LocalTimetableRepository
import com.example.awake.domain.model.Profile
import kotlinx.coroutines.flow.Flow

/** 所有离线查看数据均从当前本地课表流读取，不直接依赖网络。 */
class ObserveTimetableUseCase(private val local: LocalTimetableRepository) {
    val activeProfile: Flow<Profile?> = local.activeProfile

    fun timetables(profileId: Long): Flow<List<TimetableEntity>> = local.observeTimetables(profileId)
    fun timetable(timetableId: Long): Flow<TimetableEntity?> = local.observeTimetable(timetableId)

    /** 周视图消费的扁平时段行：一门课的一个时段占一行。 */
    fun courses(timetableId: Long, week: Int): Flow<List<CourseSlotEntity>> = local.observeCourses(timetableId, week)
    fun coursesThroughEnd(timetableId: Long, week: Int): Flow<List<CourseSlotEntity>> = local.observeCoursesThroughEnd(timetableId, week)

    /** 课程详情按主记录读取，并展示其全部时段。 */
    fun course(courseId: Long): Flow<CourseEntity?> = local.observeCourse(courseId)
    fun sections(courseId: Long): Flow<List<CourseSectionEntity>> = local.observeSections(courseId)
    fun slot(sectionId: Long): Flow<CourseSlotEntity?> = local.observeSlot(sectionId)
}