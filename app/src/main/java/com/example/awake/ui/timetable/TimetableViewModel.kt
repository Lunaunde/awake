package com.example.awake.ui.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.awake.data.local.CourseEntity
import com.example.awake.data.local.TimetableEntity
import com.example.awake.data.remote.ScutHttpException
import com.example.awake.data.remote.ScutAccessMode
import com.example.awake.data.remote.SessionAvailability
import com.example.awake.data.remote.SessionAvailabilityState
import com.example.awake.data.repository.ScutScheduleRepository
import com.example.awake.data.repository.LocalTimetableRepository
import com.example.awake.data.repository.ReminderCoordinator
import com.example.awake.data.repository.TimetableSelectionStore
import com.example.awake.data.repository.TimetableDisplaySettingsStore
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
import kotlinx.coroutines.flow.first

enum class TimetableSyncState { IDLE, REFRESHING, SUCCESS, OFFLINE, SESSION_EXPIRED, ERROR }


data class TimetableWeekPage(
    val week: Int,
    val coursesThroughEnd: List<CourseEntity>,
    val currentCourses: List<CourseEntity>,
    val currentCourseIds: Set<Long>
)

data class AdjacentTimetablePages(
    val previous: TimetableWeekPage,
    val current: TimetableWeekPage,
    val next: TimetableWeekPage
)

class TimetableViewModel(
    private val observe: ObserveTimetableUseCase,
    private val refreshUseCase: RefreshTimetableUseCase,
    private val local: LocalTimetableRepository,
    private val reminderCoordinator: ReminderCoordinator,
    private val selection: TimetableSelectionStore,
    private val displaySettings: TimetableDisplaySettingsStore,
    private val remote: ScutScheduleRepository
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
    /**
     * 开启“显示非本周”时使用的课程列表：包含本周和未来仍有课的课程，
     * 这样开课前可以半透明预览，但最后一周结束后不会继续显示。
     */
    val coursesThroughEnd: StateFlow<List<CourseEntity>> = combine(selectedTimetableId, currentWeek) { id, week -> id to week }
        .flatMapLatest { (id, week) -> if (id == null) flowOf(emptyList()) else observe.coursesThroughEnd(id, week) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 预取当前周的前后页面，让周视图在拖动时可以直接露出相邻周的真实课程。
     * currentCourses 用于关闭“显示非本周”时的精确周数据，coursesThroughEnd 用于开启时的半透明预览。
     */
    val adjacentWeekPages: StateFlow<AdjacentTimetablePages?> =
        combine(selectedTimetableId, currentWeek) { id, week -> id to week }
            .flatMapLatest { (id, week) ->
                if (id == null) {
                    flowOf(null)
                } else {
                    combine(
                        observeWeekPage(id, week - 1),
                        observeWeekPage(id, week),
                        observeWeekPage(id, week + 1)
                    ) { previous, current, next ->
                        AdjacentTimetablePages(previous, current, next)
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val showOtherWeeks: StateFlow<Boolean> = displaySettings.showOtherWeeks
    val periodConfigs = local.observePeriodConfigs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _syncState = MutableStateFlow(TimetableSyncState.IDLE)
    val syncState = _syncState.asStateFlow()

    private fun observeWeekPage(timetableId: Long, week: Int): kotlinx.coroutines.flow.Flow<TimetableWeekPage> {
        if (week !in 1..30) {
            return flowOf(TimetableWeekPage(week, emptyList(), emptyList(), emptySet()))
        }
        return combine(
            observe.coursesThroughEnd(timetableId, week),
            observe.courses(timetableId, week)
        ) { throughEnd, current ->
            TimetableWeekPage(
                week = week,
                coursesThroughEnd = throughEnd,
                currentCourses = current,
                currentCourseIds = current.mapTo(mutableSetOf()) { it.id }
            )
        }
    }

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

    fun renameTimetable(id: Long, label: String) {
        val normalized = label.trim()
        if (normalized.isEmpty()) {
            _message.value = "课表名称不能为空"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val timetable = local.getTimetableOrNull(id) ?: return@launch
            local.updateTimetable(timetable.copy(label = normalized))
            _message.value = "课表名称已修改"
        }
    }

    fun deleteTimetable(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val list = timetables.first()
            if (list.size <= 1) {
                _message.value = "至少保留一个课表，无法删除"
                return@launch
            }
            val deletingCurrent = selectedTimetableId.value == id
            local.deleteTimetable(id)
            if (deletingCurrent) {
                val next = list.firstOrNull { it.id != id }
                if (next != null) {
                    selectedId.value = next.id
                    selection.setSelected(next.id)
                    reminderCoordinator.reschedule(next.id)
                } else {
                    selection.clear()
                }
            }
            _syncState.value = TimetableSyncState.IDLE
            _message.value = "课表已删除"
        }
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

    private fun sessionSummary(sessions: List<SessionAvailability>): String? {
        if (sessions.isEmpty()) return null
        val usable = sessions.filter { it.state == SessionAvailabilityState.AVAILABLE }
        if (usable.isEmpty()) return "会话状态待确认"
        return usable.joinToString("、") {
            if (it.accessMode == ScutAccessMode.DIRECT) "直连可用" else "VPN可用"
        }
    }

    fun refresh() {
        val id = selectedTimetableId.value ?: return
        if (_syncState.value == TimetableSyncState.REFRESHING) return
        viewModelScope.launch {
            _syncState.value = TimetableSyncState.REFRESHING
            _message.value = "正在检查直连/VPN会话并同步课表…"
            val sessions = runCatching { withContext(Dispatchers.IO) { remote.probeSessions() } }.getOrDefault(emptyList())
            runCatching { withContext(Dispatchers.IO) { refreshUseCase(id) } }
                .onSuccess { warnings ->
                    reminderCoordinator.reschedule(id)
                    _syncState.value = TimetableSyncState.SUCCESS
                    val sessionText = sessionSummary(sessions)
                    _message.value = if (warnings.isEmpty()) {
                        "同步成功${sessionText?.let { " · $it" }.orEmpty()}"
                    } else {
                        "同步成功，跳过 ${warnings.size} 条无法解析的记录${sessionText?.let { " · $it" }.orEmpty()}"
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
    private val selection: TimetableSelectionStore,
    private val displaySettings: TimetableDisplaySettingsStore,
    private val remote: ScutScheduleRepository
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        TimetableViewModel(observe, refresh, local, reminderCoordinator, selection, displaySettings, remote) as T
}
