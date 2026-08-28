package com.example.awake.ui.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.awake.data.local.CourseEntity
import com.example.awake.data.local.TimetableEntity
import com.example.awake.data.repository.LocalTimetableRepository
import com.example.awake.data.repository.ScutScheduleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TimetableViewModel(private val local: LocalTimetableRepository, private val remote: ScutScheduleRepository) : ViewModel() {
    val profile = local.activeProfile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val timetables: StateFlow<List<TimetableEntity>> = profile.flatMapLatest { p ->
        if (p == null) flowOf(emptyList()) else local.observeTimetables(p.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val selectedId = MutableStateFlow<Long?>(null)
    val currentWeek = MutableStateFlow(1)
    val selectedTimetableId: StateFlow<Long?> = combine(timetables, selectedId) { list, selected ->
        selected?.takeIf { id -> list.any { it.id == id } } ?: list.firstOrNull()?.id
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val courses: StateFlow<List<CourseEntity>> = combine(selectedTimetableId, currentWeek) { id, week -> id to week }
        .flatMapLatest { (id, week) -> if (id == null) flowOf(emptyList()) else local.observeCourses(id, week) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()

    fun selectTimetable(id: Long) { selectedId.value = id }
    fun selectWeek(week: Int) { currentWeek.value = week.coerceIn(1, 30) }
    fun clearMessage() { _message.value = null }
    fun seedDemo() = viewModelScope.launch {
        val timetable = local.seedDemoTimetable()
        selectedId.value = timetable.id
        _message.value = "已创建离线演示课表"
    }
    fun refresh() {
        val id = selectedTimetableId.value ?: return
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            _message.value = runCatching { remote.import(id); "同步成功" }.getOrElse { it.message ?: "同步失败，已保留旧课表" }
            _refreshing.value = false
        }
    }
}

class TimetableViewModelFactory(private val local: LocalTimetableRepository, private val remote: ScutScheduleRepository) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = TimetableViewModel(local, remote) as T
}
