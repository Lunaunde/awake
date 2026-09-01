package com.example.awake.ui.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.awake.data.local.CourseSlotEntity
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

enum class TimetableSyncState { IDLE, REFRESHING, SUCCESS, OFFLINE, SESSION_EXPIRED, ERROR }


data class TimetableWeekPage(
    val week: Int,
    val coursesThroughEnd: List<CourseSlotEntity>,
    val currentCourses: List<CourseSlotEntity>,
    /** 本周确有课的时段 id；rawWeekText 无法解释时作为“本周”兜底判断。 */
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
    private val remote: ScutScheduleRepository,
    private val jsonTimetableStore: com.example.awake.data.repository.JsonTimetableStore
) : ViewModel() {
    /** JSON 分享课表首次刷新前的确认请求（弹窗由界面展示）。 */
    private val _pendingSyncConfirm = MutableStateFlow(false)
    val pendingSyncConfirm: StateFlow<Boolean> = _pendingSyncConfirm.asStateFlow()
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
    /** 当前周的扁平时段行：一门课的一个时段占一行，多时段课程会出现多条。 */
    val courses: StateFlow<List<CourseSlotEntity>> = combine(selectedTimetableId, currentWeek) { id, week -> id to week }
        .flatMapLatest { (id, week) -> if (id == null) flowOf(emptyList()) else observe.courses(id, week) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    /**
     * 开启“显示非本周”时使用的时段列表：包含本周和未来仍有课的时段，
     * 这样开课前可以半透明预览，但最后一周结束后不会继续显示。
     */
    val coursesThroughEnd: StateFlow<List<CourseSlotEntity>> = combine(selectedTimetableId, currentWeek) { id, week -> id to week }
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
    /** 节次时间跟随当前选中课表（无独立配置时回退全局默认）。 */
    val periodConfigs: StateFlow<List<com.example.awake.data.local.PeriodConfigEntity>> =
        selectedTimetableId.flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else local.observePeriodConfigsFor(id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
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
                currentCourseIds = current.mapTo(mutableSetOf()) { it.sectionId }
            )
        }
    }

    init {
        // 升级旧版本后立即清除误混入正式课表的演示课程，保留独立的演示课表。
        viewModelScope.launch(Dispatchers.IO) { local.cleanupLegacyDemoCourses() }
        // 默认打开“当前周”：按选中课表的学期第一周日期推算本日所在周。
        viewModelScope.launch {
            selectedTimetable
                .filterNotNull()
                .distinctUntilChanged { old, new -> old.id == new.id && old.startDate == new.startDate }
                .collect { timetable ->
                    val startDate = timetable.startDate ?: return@collect
                    currentWeek.value = runCatching {
                        java.time.temporal.ChronoUnit.WEEKS.between(
                            java.time.LocalDate.parse(startDate), java.time.LocalDate.now()
                        ).toInt() + 1
                    }.getOrNull()?.coerceIn(1, 30) ?: 1
                }
        }
    }

    /** 新建一个空白课表并立即切换（xnm=0/xqm=manual 标识，不占用教务学期键）。 */
    fun createTimetable(label: String) {
        val normalized = label.trim()
        if (normalized.isEmpty()) {
            _message.value = "课表名称不能为空"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val profile = local.ensureProfile()
                local.createTimetable(profile.id, 0, "manual", normalized)
            }.onSuccess { created ->
                selectedId.value = created.id
                selection.setSelected(created.id)
                _syncState.value = TimetableSyncState.IDLE
                _message.value = "已创建课表“$normalized”"
            }.onFailure { error ->
                _message.value = error.message ?: "新建课表失败"
            }
        }
    }

    /** 导出当前课表完整 JSON（右上角分享按钮）。 */
    fun exportJson(onReady: (String) -> Unit) {
        val id = selectedTimetableId.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val json = runCatching { local.exportTimetableJson(id) }.getOrNull()
            if (json != null) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onReady(json)
                }
            } else {
                _message.value = "导出失败：课表不存在"
            }
        }
    }
    fun selectTimetable(id: Long) {
        selectedId.value = id
        selection.setSelected(id)
        _syncState.value = TimetableSyncState.IDLE
        viewModelScope.launch { reminderCoordinator.reschedule(id) }
    }

    /** 外部（导入页/演示创建）修改了全局选课存储后，回到主界面时对齐选择。 */
    fun syncSelectionFromStore() {
        selection.read()?.let { stored ->
            if (stored != selectedId.value) {
                selectedId.value = stored
                _syncState.value = TimetableSyncState.IDLE
            }
        }
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
            val deletingCurrent = selectedTimetableId.value == id
            local.deleteTimetable(id)
            if (deletingCurrent) {
                val next = timetables.first().firstOrNull { it.id != id }
                if (next != null) {
                    selectedId.value = next.id
                    selection.setSelected(next.id)
                    reminderCoordinator.reschedule(next.id)
                } else {
                    // 删除最后一份课表：清空选中，回到空态页，并清掉该课表残留提醒。
                    selectedId.value = null
                    selection.clear()
                    reminderCoordinator.rescheduleSelected()
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
        // JSON 分享课表保存的是别人的课程：首次刷新（含自动同步）前需要确认。
        if (jsonTimetableStore.isJsonImported(id) && !jsonTimetableStore.isSyncConfirmed(id)) {
            _pendingSyncConfirm.value = true
            return
        }
        doRefresh(id)
    }

    /** 用户确认后真正执行同步，并记住该课表已确认过（不再重复询问）。 */
    fun confirmSync() {
        val id = selectedTimetableId.value ?: return
        if (_syncState.value == TimetableSyncState.REFRESHING) return
        _pendingSyncConfirm.value = false
        jsonTimetableStore.confirmSync(id)
        doRefresh(id)
    }

    fun cancelSyncConfirm() {
        _pendingSyncConfirm.value = false
        _message.value = "已取消同步，分享课表保持不变"
    }

    private fun doRefresh(id: Long) {
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
    private val remote: ScutScheduleRepository,
    private val jsonTimetableStore: com.example.awake.data.repository.JsonTimetableStore
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        TimetableViewModel(observe, refresh, local, reminderCoordinator, selection, displaySettings, remote, jsonTimetableStore) as T
}
