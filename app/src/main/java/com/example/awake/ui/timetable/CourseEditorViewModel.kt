package com.example.awake.ui.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.awake.data.local.CourseEntity
import com.example.awake.data.local.PeriodConfigDefaults
import com.example.awake.data.repository.LocalTimetableRepository
import com.example.awake.data.repository.ReminderCoordinator
import com.example.awake.domain.parser.WeekExpressionParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CourseEditorUiState(
    val name: String = "",
    val teacher: String = "",
    val room: String = "",
    val dayOfWeek: String,
    val startPeriod: String,
    val endPeriod: String,
    val weeks: String = "1-30",
    val busy: Boolean = false,
    val error: String? = null
)

class CourseEditorViewModel(
    private val local: LocalTimetableRepository,
    private val reminderCoordinator: ReminderCoordinator,
    private val timetableId: Long,
    initialDay: Int,
    initialStartPeriod: Int
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        CourseEditorUiState(
            dayOfWeek = initialDay.coerceIn(1, 7).toString(),
            startPeriod = initialStartPeriod.coerceIn(1, PeriodConfigDefaults.periodCount).toString(),
            endPeriod = initialStartPeriod.coerceIn(1, PeriodConfigDefaults.periodCount).toString()
        )
    )
    val uiState: StateFlow<CourseEditorUiState> = _uiState.asStateFlow()

    fun setName(value: String) = update { copy(name = value, error = null) }
    fun setTeacher(value: String) = update { copy(teacher = value, error = null) }
    fun setRoom(value: String) = update { copy(room = value, error = null) }
    fun setDay(value: String) = update { copy(dayOfWeek = value.filter(Char::isDigit), error = null) }
    fun setStart(value: String) = update { copy(startPeriod = value.filter(Char::isDigit), error = null) }
    fun setEnd(value: String) = update { copy(endPeriod = value.filter(Char::isDigit), error = null) }
    fun setWeeks(value: String) = update { copy(weeks = value, error = null) }

    fun save(onDone: () -> Unit) {
        val state = _uiState.value
        if (state.busy) return
        val dayValue = state.dayOfWeek.toIntOrNull()
        val startValue = state.startPeriod.toIntOrNull()
        val endValue = state.endPeriod.toIntOrNull()
        val weekResult = WeekExpressionParser.parse(state.weeks, maxWeek = 30)
        val validationError = when {
            state.name.isBlank() -> "请填写课程名称"
            dayValue == null || dayValue !in 1..7 -> "星期必须是 1-7"
            startValue == null || endValue == null || startValue !in 1..PeriodConfigDefaults.periodCount || endValue !in 1..PeriodConfigDefaults.periodCount -> "节次必须是 1-${PeriodConfigDefaults.periodCount}"
            startValue > endValue -> "结束节次不能早于开始节次"
            weekResult.warning != null -> weekResult.warning.message
            else -> null
        }
        if (validationError != null) {
            _uiState.value = state.copy(error = validationError)
            return
        }
        val day = dayValue ?: return
        val start = startValue ?: return
        val end = endValue ?: return
        viewModelScope.launch {
            _uiState.value = state.copy(busy = true, error = null)
            runCatching {
                local.insertManualCourse(
                    CourseEntity(
                        timetableId = timetableId,
                        source = "MANUAL",
                        remoteKey = "manual_${System.currentTimeMillis()}",
                        name = state.name.trim(),
                        teacher = state.teacher.trim(),
                        room = state.room.trim(),
                        dayOfWeek = day,
                        startPeriod = start,
                        endPeriod = end,
                        color = 0xffe78fb3.toInt(),
                        rawWeekText = state.weeks.trim()
                    ),
                    weekResult.weeks
                )
                reminderCoordinator.reschedule(timetableId)
            }.onSuccess { onDone() }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        busy = false,
                        error = error.message ?: "保存课程失败"
                    )
                }
        }
    }

    private fun update(block: CourseEditorUiState.() -> CourseEditorUiState) {
        _uiState.value = _uiState.value.block()
    }
}

class CourseEditorViewModelFactory(
    private val local: LocalTimetableRepository,
    private val reminderCoordinator: ReminderCoordinator,
    private val timetableId: Long,
    private val dayOfWeek: Int,
    private val startPeriod: Int
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        CourseEditorViewModel(local, reminderCoordinator, timetableId, dayOfWeek, startPeriod) as T
}
