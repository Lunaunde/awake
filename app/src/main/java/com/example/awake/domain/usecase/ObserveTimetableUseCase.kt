package com.example.awake.domain.usecase

import com.example.awake.data.local.CourseEntity
import com.example.awake.data.local.TimetableEntity
import com.example.awake.data.repository.LocalTimetableRepository
import com.example.awake.domain.model.Profile
import kotlinx.coroutines.flow.Flow

/** 所有离线查看数据均从当前本地课表流读取，不直接依赖网络。 */
class ObserveTimetableUseCase(private val local: LocalTimetableRepository) {
    val activeProfile: Flow<Profile?> = local.activeProfile

    fun timetables(profileId: Long): Flow<List<TimetableEntity>> = local.observeTimetables(profileId)
    fun timetable(timetableId: Long): Flow<TimetableEntity?> = local.observeTimetable(timetableId)
    fun courses(timetableId: Long, week: Int): Flow<List<CourseEntity>> = local.observeCourses(timetableId, week)
    fun course(courseId: Long): Flow<CourseEntity?> = local.observeCourse(courseId)
}
