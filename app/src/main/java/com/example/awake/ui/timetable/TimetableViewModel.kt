package com.example.awake.ui.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.awake.data.local.CourseEntity
import com.example.awake.data.local.TimetableEntity
import com.example.awake.data.remote.ScutHttpException
import com.example.awake.data.repository.LocalTimetableRepository
import com.example.awake.data.repository.ReminderCoordinator
import com.example.awake.data.repository.TimetableSelectionStore
import com.example.awake.domain.usecase.ObserveTimetableUseCase
import com.example.awake.domain.usecase.RefreshTimetableUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TimetableSyncState { IDLE, REFRESHING, SUCCESS, OFFLINE, SESSION_EXPIRED, ERROR }

class TimetableViewModel(
    private val observe: ObserveTimetableUseCase,
    private val refreshUseCase: RefreshTimetableUseCase,
    private val local: LocalTimetableRepository,
    private val reminderCoordinator: ReminderCoordinator,
    private val selection: TimetableSelectionStore
) : ViewModel() {
    val profile = observe.activeProfile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val timetables: StateFlow<List<TimetableEntity>> = profile.flatMapLatest { p ->
        if (p == null) flowOf(emptyList()) else observe.timetables(p.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val selectedId = MutableStateFlow<Long?>(selection.read())
    val currentWeek = MutableStateFlow(1)
    val selectedTimetableId: StateFlow<Long?> = combine(timetables, selectedId) { list, selected ->
        selected?.takeIf { id -> list.any { it.id == id } } ?: list.firstOrNull()?.id
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val selectedTimetable: StateFlow<TimetableEntity?> = combine(timetables, selectedTimetableId) { list, id ->
        list.firstOrNull { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val courses: StateFlow<List<CourseEntity>> = combine(selectedTimetableId, currentWeek) { id, week -> id to week }
        .flatMapLatest { (id, week) -> if (id == null) flowOf(emptyList()) else observe.courses(id, week) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val periodConfigs = local.observePeriodConfigs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _syncState = MutableStateFlow(TimetableSyncState.IDLE)
    val syncState = _syncState.asStateFlow()

    init {
        // 升级旧版本后立即清除误混入正式课表的演示课程，保留独立的演示课表。
        viewModelScope.launch(Dispatchers.IO) { local.cleanupLegacyDemoCourses() }
    }
    fun selectTimetable(id: Long) {
        selectedId.value = id
        selection.setSelected(id)
        _syncState.value = TimetableSyncState.IDLE
        viewModelScope.launch { reminderCoordinator.reschedule(id) }
    }

    fun selectWeek(week: Int) {
        currentWeek.value = week.coerceIn(1, 30)
    }

    fun clearMessage() {
        _message.value = null
        if (_syncState.value == TimetableSyncState.SUCCESS) _syncState.value = TimetableSyncState.IDLE
    }

    fun seedDemo() = viewModelScope.launch {
        val timetable = local.seedDemoTimetable()
        selectedId.value = timetable.id
        selection.setSelected(timetable.id)
        reminderCoordinator.reschedule(timetable.id)
        _syncState.value = TimetableSyncState.SUCCESS
        _message.value = "已创建离线演示课表"
    }

    fun refresh() {
        val id = selectedTimetableId.value ?: return
        if (_syncState.value == TimetableSyncState.REFRESHING) return
        viewModelScope.launch {
            _syncState.value = TimetableSyncState.REFRESHING
            _message.value = null
            runCatching { withContext(Dispatchers.IO) { refreshUseCase(id) } }
                .onSuccess { warnings ->
                    reminderCoordinator.reschedule(id)
                    _syncState.value = TimetableSyncState.SUCCESS
                    _message.value = if (warnings.isEmpty()) {
                        "同步成功"
                    } else {
                        "同步成功，跳过 ${warnings.size} 条无法解析的记录"
                    }
                }
                .onFailure { error ->
                    val state = when ((error as? ScutHttpException)?.kind) {
                        ScutHttpException.Kind.NETWORK -> TimetableSyncState.OFFLINE
                        ScutHttpException.Kind.SESSION_EXPIRED -> TimetableSyncState.SESSION_EXPIRED
                        else -> TimetableSyncState.ERROR
                    }
                    _syncState.value = state
                    _message.value = error.message ?: "同步失败，已保留旧课表"
                }
        }
    }
}

class TimetableViewModelFactory(
    private val observe: ObserveTimetableUseCase,
    private val refresh: RefreshTimetableUseCase,
    private val local: LocalTimetableRepository,
    private val reminderCoordinator: ReminderCoordinator,
    private val selection: TimetableSelectionStore
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        TimetableViewModel(observe, refresh, local, reminderCoordinator, selection) as T
}
