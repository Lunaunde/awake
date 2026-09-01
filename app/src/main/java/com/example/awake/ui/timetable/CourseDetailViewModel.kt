package com.example.awake.ui.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.awake.data.local.CourseEntity
import com.example.awake.data.local.CourseSectionEntity
import com.example.awake.data.repository.LocalTimetableRepository
import com.example.awake.data.repository.ReminderCoordinator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 课程详情 = 课程主记录 + 全部时段。
 * 主记录名称/教师影响整门课；时间/教室/周次在具体时段上编辑。
 */
class CourseDetailViewModel(
    private val local: LocalTimetableRepository,
    private val reminderCoordinator: ReminderCoordinator,
    courseId: Long
) : ViewModel() {
    val course: StateFlow<CourseEntity?> =
        local.observeCourse(courseId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val sections: StateFlow<List<CourseSectionEntity>> =
        local.observeSections(courseId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun saveMaster(course: CourseEntity, onDone: () -> Unit) = viewModelScope.launch {
        local.updateCourse(course)
        reminderCoordinator.reschedule(course.timetableId)
        onDone()
    }

    /** 点选即落库：颜色变化直接写 courses.color，返回写回的最终值（用于校准输入框）。 */
    fun updateColor(courseId: Long, color: Int, onSaved: (Int) -> Unit = {}) = viewModelScope.launch {
        val stored = color or 0xFF000000.toInt()
        local.updateCourseColor(courseId, stored)
        onSaved(stored)
    }

    fun deleteMaster(onDone: () -> Unit) = viewModelScope.launch {
        val timetableId = course.value?.timetableId
        course.value?.let { local.deleteCourse(it.id) }
        timetableId?.let { reminderCoordinator.reschedule(it) }
        onDone()
    }

    fun deleteSection(sectionId: Long, onDone: () -> Unit) = viewModelScope.launch {
        val timetableId = course.value?.timetableId
        local.deleteSection(sectionId)
        timetableId?.let { reminderCoordinator.reschedule(it) }
        onDone()
    }
}

class CourseDetailViewModelFactory(
    private val local: LocalTimetableRepository,
    private val reminderCoordinator: ReminderCoordinator,
    private val courseId: Long
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        CourseDetailViewModel(local, reminderCoordinator, courseId) as T
}