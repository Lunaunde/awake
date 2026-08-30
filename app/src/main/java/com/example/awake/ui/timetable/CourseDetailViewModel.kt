package com.example.awake.ui.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.awake.data.local.CourseEntity
import com.example.awake.data.repository.LocalTimetableRepository
import com.example.awake.data.repository.ReminderCoordinator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CourseDetailViewModel(
    private val local: LocalTimetableRepository,
    private val reminderCoordinator: ReminderCoordinator,
    courseId: Long
) : ViewModel() {
    val course: StateFlow<CourseEntity?> = local.observeCourse(courseId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun save(course: CourseEntity, onDone: () -> Unit) = viewModelScope.launch {
        local.updateCourse(course)
        reminderCoordinator.reschedule(course.timetableId)
        onDone()
    }

    fun delete(courseId: Long, onDone: () -> Unit) = viewModelScope.launch {
        val timetableId = course.value?.timetableId
        local.deleteCourse(courseId)
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
