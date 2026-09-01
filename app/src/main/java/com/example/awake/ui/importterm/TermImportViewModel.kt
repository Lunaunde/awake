package com.example.awake.ui.importterm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.awake.data.local.TimetableEntity
import com.example.awake.data.repository.LocalTimetableRepository
import com.example.awake.data.repository.ReminderCoordinator
import com.example.awake.data.repository.ScutScheduleRepository
import com.example.awake.data.repository.TimetableSelectionStore
import com.example.awake.data.remote.RemoteAcademicYear
import com.example.awake.data.remote.AcademicTermsCache
import com.example.awake.data.remote.RemoteSemester
import com.example.awake.data.remote.ScutHttpException
import com.example.awake.data.remote.SessionAvailability
import com.example.awake.data.remote.SessionAvailabilityState
import com.example.awake.data.remote.ScutCourseDto
import com.example.awake.domain.usecase.ExistingTimetablePolicy
import com.example.awake.domain.usecase.ImportTimetableUseCase
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 一个可供用户勾选的远程学期课表。key 在当前页面内唯一。 */
data class ImportTermOption(
    val key: String,
    val xnm: Int,
    val xqm: String,
    val title: String,
    val subtitle: String,
    val label: String,
    val startDate: String,
    val selected: Boolean = false,
    val builtIn: Boolean = true,
    val courses: List<ImportCourseOption> = emptyList(),
    val previewed: Boolean = false,
    val previewError: String? = null,
    /** JSON 分享文本导入的暂存项；不经过教务网络，导入时走本地解析。 */
    val isJson: Boolean = false,
    /** 空白手动课表暂存项；导入时创建空表（覆盖模式下清空当前课表）。 */
    val isBlank: Boolean = false
)

/** 由教务接口实际返回的课程选项，不使用课程名称硬编码。 */
data class ImportCourseOption(
    val remoteKey: String,
    val name: String,
    val day: Int,
    val periodText: String,
    val subtitle: String,
    val selected: Boolean = true
)

enum class SessionUiStatus {
    CHECKING,
    AVAILABLE,
    UNAVAILABLE,
    NETWORK_ERROR,
    SERVER_ERROR
}

/** 进入导入页时选择的模式：添加新课表（多选，一律新建）或 覆盖当前课表（单选）。 */
enum class ImportMode {
    ADD,
    OVERWRITE
}

data class TermImportUiState(
    // 网络列表加载前保留本地兜底选项，避免页面因网络失败而无法进入。
    val terms: List<ImportTermOption> = defaultTermOptions(),
    val academicYears: List<RemoteAcademicYear> = emptyList(),
    val selectedAcademicYear: Int? = null,
    val selectedSemester: String? = null,
    val loadingAcademicYears: Boolean = false,
    val sessionStatus: SessionUiStatus = SessionUiStatus.CHECKING,
    val sessionDetail: String? = null,
    val busy: Boolean = false,
    val status: String? = null,
    val conflictTimetable: TimetableEntity? = null,
    val conflictTerm: ImportTermOption? = null,
    val pendingTerms: List<ImportTermOption> = emptyList(),
    val importedCount: Int = 0,
    val previewingTermKey: String? = null,
    /** 本次导入模式；覆盖模式下执行前会再次确认目标课表。 */
    val mode: ImportMode = ImportMode.ADD,
    /** 覆盖模式的目标课表名称（进入页面时就近读取，用于横幅提示）。 */
    val overwriteTargetLabel: String? = null,
    /** 是否已有登录档案（用于教务系统导入区显示“去登录”）。 */
    val isLoggedIn: Boolean = false
)

class TermImportViewModel(
    private val local: LocalTimetableRepository,
    private val importer: ImportTimetableUseCase,
    private val reminderCoordinator: ReminderCoordinator,
    private val selection: TimetableSelectionStore,
    private val remote: ScutScheduleRepository,
    private val academicTermsCache: AcademicTermsCache,
    private val importMode: ImportMode = ImportMode.ADD,
    private val jsonTimetableStore: com.example.awake.data.repository.JsonTimetableStore? = null
) : ViewModel() {
    private val _uiState = MutableStateFlow(TermImportUiState(mode = importMode))
    val uiState: StateFlow<TermImportUiState> = _uiState.asStateFlow()
    /** 防止初始化、重组或快速点击同时发起多个“实际课程列表”请求。 */
    private val previewMutex = Mutex()

    /** 导入预检期间用户对每个同学期学期选择的处理方式（覆盖/新建），导入开始后按此执行。 */
    private val conflictPolicies = mutableMapOf<String, ExistingTimetablePolicy>()

    /** 用户选择「覆盖当前课表」时的目标课表 id（= 确认时的当前选中课表）。 */
    private var pendingOverwriteTargetId: Long? = null

    /** JSON 分享文本解析结果，按暂存项 key 关联；统一导入时取出落库。 */
    private val jsonPayloads = mutableMapOf<String, com.example.awake.data.export.TimetableJson.JsonTimetableData>()

    init {
        // 先读取教务查询页中的真实学年列表，再由用户选择学年和学期。
        // 不在初始化阶段自动请求课程，避免把“当前学年”误当成唯一选项。
        val cached = academicTermsCache.years
        if (cached.isNotEmpty()) applyAcademicYears(cached)
        // 即使有缓存也后台检查会话并更新学年列表，避免顶部状态与实际请求结果不一致。
        loadAcademicYears(force = true)
        // 覆盖模式：就近读取当前课表名称用于顶部提示；没有当前课表时无法覆盖。
        if (importMode == ImportMode.OVERWRITE) {
            viewModelScope.launch {
                val label = withContext(Dispatchers.IO) {
                    selection.read()?.let { local.getTimetableOrNull(it) }?.label
                }
                _uiState.value = _uiState.value.copy(overwriteTargetLabel = label)
                if (label == null) {
                    _uiState.value = _uiState.value.copy(status = "当前没有可覆盖的课表，请先新建或导入课表")
                }
            }
        }
        // 登录状态用于「教务系统导入」区的去登录按钮。
        viewModelScope.launch {
            local.activeProfile.collect { profile ->
                val loggedIn = profile?.displayName?.isNotBlank() == true && profile.displayName != "未登录"
                _uiState.value = _uiState.value.copy(isLoggedIn = loggedIn)
            }
        }
    }

    /** 根据当前日期优先定位学年：8 月起为当年-次年，1~7 月为上一年-当年。 */
    private fun preferredAcademicYear(years: List<RemoteAcademicYear>): RemoteAcademicYear? {
        if (years.isEmpty()) return null
        val today = LocalDate.now()
        val expectedXnm = if (today.monthValue >= 8) today.year else today.year - 1
        return years.firstOrNull { it.xnm == expectedXnm } ?: years.firstOrNull()
    }

    private fun applyAcademicYears(years: List<RemoteAcademicYear>) {
        val normalized = years.sortedByDescending(RemoteAcademicYear::xnm).map { year -> year.copy(semesters = year.semesters.distinctBy(RemoteSemester::xqm)) }
        val preferredYear = preferredAcademicYear(normalized)
        val selectedYear = preferredYear?.xnm
        val selectedSemester = preferredYear?.semesters?.firstOrNull()?.xqm
        val terms = normalized.flatMap { year -> year.semesters.map { semester -> year.toImportTermOption(semester, false) } }
        _uiState.value = _uiState.value.copy(academicYears = normalized, selectedAcademicYear = selectedYear, selectedSemester = selectedSemester, terms = terms, loadingAcademicYears = false, status = "已读取 ${normalized.size} 个学年，可选择后获取课程")
    }

    /** 读取真实学年/学期，并自动重试；失败时保留已有缓存和兜底选项。 */
    fun loadAcademicYears(force: Boolean = false) {
        val current = _uiState.value
        if (current.loadingAcademicYears || current.busy) return
        if (!force && current.academicYears.isNotEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                loadingAcademicYears = true,
                sessionStatus = SessionUiStatus.CHECKING,
                sessionDetail = null,
                status = "正在检查教务会话并读取学年列表…"
            )
            var lastError: Throwable? = null
            repeat(3) { attempt ->
                try {
                    val sessions = withContext(Dispatchers.IO) { remote.probeSessions() }
                    updateSessionStatus(sessions)
                    val years = withContext(Dispatchers.IO) { remote.academicTerms() }
                    applyAcademicYears(years)
                    _uiState.value = _uiState.value.copy(
                        loadingAcademicYears = false,
                        sessionStatus = SessionUiStatus.AVAILABLE,
                        sessionDetail = sessionSummary(sessions),
                        status = if (years.isEmpty()) "教务系统暂未返回可用学年，请点击刷新重试" else "已读取 ${years.size} 个学年，可选择后获取课程"
                    )
                    return@launch
                } catch (error: Throwable) {
                    lastError = error
                    val uiStatus = sessionStatusFor(error)
                    _uiState.value = _uiState.value.copy(
                        sessionStatus = uiStatus,
                        sessionDetail = error.message
                    )
                    if (attempt < 2) {
                        _uiState.value = _uiState.value.copy(status = "读取失败，${attempt + 1} 秒后自动重试（${attempt + 1}/2）…")
                        delay((attempt + 1) * 1000L)
                    }
                }
            }
            _uiState.value = _uiState.value.copy(
                loadingAcademicYears = false,
                status = when (sessionStatusFor(lastError)) {
                    SessionUiStatus.UNAVAILABLE -> "教务会话已失效，请重新登录后再试"
                    SessionUiStatus.NETWORK_ERROR -> "网络暂时不可用，已保留当前数据；请检查网络后刷新"
                    SessionUiStatus.SERVER_ERROR -> "教务系统暂时不可用，已保留当前数据；请稍后刷新"
                    else -> "暂时无法读取教务系统的学年列表，请点击刷新重试"
                }
            )
        }
    }

    private fun updateSessionStatus(sessions: List<SessionAvailability>) {
        val available = sessions.any { it.state == SessionAvailabilityState.AVAILABLE }
        val status = when {
            available -> SessionUiStatus.AVAILABLE
            sessions.any { it.state == SessionAvailabilityState.NETWORK_ERROR } -> SessionUiStatus.NETWORK_ERROR
            sessions.any { it.state == SessionAvailabilityState.SERVER_ERROR } -> SessionUiStatus.SERVER_ERROR
            else -> SessionUiStatus.UNAVAILABLE
        }
        _uiState.value = _uiState.value.copy(sessionStatus = status, sessionDetail = sessionSummary(sessions))
    }

    private fun sessionStatusFor(error: Throwable?): SessionUiStatus = when ((error as? ScutHttpException)?.kind) {
        ScutHttpException.Kind.NETWORK -> SessionUiStatus.NETWORK_ERROR
        ScutHttpException.Kind.SERVER, ScutHttpException.Kind.RATE_LIMITED, ScutHttpException.Kind.MAINTENANCE, ScutHttpException.Kind.INVALID_RESPONSE -> SessionUiStatus.SERVER_ERROR
        ScutHttpException.Kind.SESSION_EXPIRED -> SessionUiStatus.UNAVAILABLE
        else -> SessionUiStatus.SERVER_ERROR
    }

    private fun sessionSummary(sessions: List<SessionAvailability>): String? {
        if (sessions.isEmpty()) return "未配置登录会话"
        return sessions.joinToString(" · ") { session ->
            val name = if (session.accessMode.name == "DIRECT") "直连" else "VPN"
            val state = when (session.state) {
                SessionAvailabilityState.AVAILABLE -> "可用"
                SessionAvailabilityState.EXPIRED -> "已失效"
                SessionAvailabilityState.NETWORK_ERROR -> "网络异常"
                SessionAvailabilityState.SERVER_ERROR -> "系统异常"
                SessionAvailabilityState.NOT_CONFIGURED -> "未配置"
            }
            "$name $state"
        }
    }

    /** 阶段3界面使用：切换当前待查询的学年，不会立即请求课程。 */
    fun selectAcademicYear(xnm: Int) {
        if (_uiState.value.busy || _uiState.value.loadingAcademicYears) return
        val year = _uiState.value.academicYears.firstOrNull { it.xnm == xnm } ?: return
        val semester = year.semesters.firstOrNull() ?: return
        _uiState.value = _uiState.value.copy(
            selectedAcademicYear = xnm,
            selectedSemester = semester.xqm,
            // 这里只改变远程查询条件，不把课表加入本地列表；获取实际课程成功后才加入。
            terms = _uiState.value.terms,
            status = "已选择 ${year.label}，请选择学期后获取实际课程"
        )
    }

    /** 阶段3界面使用：切换当前待查询的学期，不会立即请求课程。 */
    fun selectSemester(xqm: String) {
        if (_uiState.value.busy || _uiState.value.loadingAcademicYears) return
        val year = _uiState.value.academicYears.firstOrNull { it.xnm == _uiState.value.selectedAcademicYear } ?: return
        val semester = year.semesters.firstOrNull { it.xqm == xqm } ?: return
        _uiState.value = _uiState.value.copy(
            selectedSemester = semester.xqm,
            // 这里只改变远程查询条件，不把课表加入本地列表；获取实际课程成功后才加入。
            terms = _uiState.value.terms,
            status = "已选择 ${year.label} ${semester.label}，点击获取实际课程"
        )
    }

    /** 获取当前学年/学期的真实课程；仅选中目标学期，不影响已加入的其他学期。 */
    fun previewSelectedTerm() {
        val state = _uiState.value
        val key = state.terms.firstOrNull { it.xnm == state.selectedAcademicYear && it.xqm == state.selectedSemester }?.key
        if (key == null) {
            _uiState.value = state.copy(status = "请先选择有效的学年和学期")
            return
        }
        previewTerm(key)
    }

    fun previewTerm(key: String) {
        val current = _uiState.value
        if (current.busy || current.previewingTermKey != null || current.terms.none { it.key == key }) return
        viewModelScope.launch {
            previewMutex.withLock {
                // 另一个相同请求可能已经在 Mutex 外先一步标记了状态，丢弃本次重复触发。
                val state = _uiState.value
                if (state.busy || state.previewingTermKey != null) return@withLock
                val term = state.terms.firstOrNull { it.key == key } ?: return@withLock

                _uiState.value = state.copy(
                    previewingTermKey = key,
                    status = "正在从教务系统获取实际课程列表…",
                    terms = state.terms.map {
                        if (it.key == key) it.copy(previewed = false, previewError = null) else it
                    }
                )
                runCatching { withContext(Dispatchers.IO) { remote.preview(term.xnm, term.xqm) } }
                    .onSuccess { payload ->
                        val courses = payload.courses
                            .distinctBy(ScutCourseDto::remoteKey)
                            .map { dto -> dto.toImportOption() }
                            .sortedWith(compareBy({ it.day.takeIf { day -> day in 1..7 } ?: 8 }, { firstPeriod(it.periodText) }, { it.name }, { it.subtitle }))
                        _uiState.value = _uiState.value.copy(
                            previewingTermKey = null,
                            status = if (courses.isEmpty()) "该学期暂未获取到课程" else "已获取 ${courses.size} 门实际课程，请选择要导入的课程",
                            terms = _uiState.value.terms.map {
                                if (it.key == key) it.copy(selected = true, courses = courses, previewed = true, previewError = null) else it
                            }
                        )
                    }
                    .onFailure { error ->
                        _uiState.value = _uiState.value.copy(
                            previewingTermKey = null,
                            status = "获取“${term.label}”课程失败：${error.message ?: "请稍后重试"}",
                            terms = _uiState.value.terms.map {
                                if (it.key == key) it.copy(previewed = false, previewError = error.message ?: "获取失败") else it
                            }
                        )
                    }
            }
        }
    }

    fun toggleTerm(key: String) {
        if (_uiState.value.busy || _uiState.value.previewingTermKey != null) return
        val currentSelected = _uiState.value.terms.firstOrNull { it.key == key }?.selected == true
        _uiState.value = _uiState.value.copy(
            terms = _uiState.value.terms.map { term ->
                when {
                    importMode == ImportMode.OVERWRITE ->
                        // 覆盖模式统一单选：选中的是目标课表，其它全部取消。
                        term.copy(selected = term.key == key && !currentSelected)
                    term.key == key -> term.copy(selected = !term.selected)
                    else -> term
                }
            },
            status = null
        )
        val selected = _uiState.value.terms.firstOrNull { it.key == key }?.selected == true
        val term = _uiState.value.terms.firstOrNull { it.key == key }
        if (selected && term != null && !term.previewed) previewTerm(key)
    }

    fun toggleCourse(termKey: String, remoteKey: String) {
        if (_uiState.value.busy || _uiState.value.previewingTermKey != null) return
        _uiState.value = _uiState.value.copy(
            terms = _uiState.value.terms.map { term ->
                if (term.key != termKey) term else term.copy(
                    courses = term.courses.map { course ->
                        if (course.remoteKey == remoteKey) course.copy(selected = !course.selected) else course
                    }
                )
            },
            status = null
        )
    }

    fun toggleAllCourses(termKey: String) {
        if (_uiState.value.busy || _uiState.value.previewingTermKey != null) return
        val term = _uiState.value.terms.firstOrNull { it.key == termKey } ?: return
        val shouldSelect = term.courses.any { !it.selected }
        _uiState.value = _uiState.value.copy(
            terms = _uiState.value.terms.map {
                if (it.key == termKey) it.copy(courses = it.courses.map { course -> course.copy(selected = shouldSelect) }) else it
            },
            status = null
        )
    }

    fun setTermLabel(key: String, value: String) {
        if (_uiState.value.busy) return
        _uiState.value = _uiState.value.copy(
            terms = _uiState.value.terms.map { term ->
                if (term.key == key) term.copy(label = value) else term
            }
        )
    }

    fun setTermStartDate(key: String, value: String) {
        if (_uiState.value.busy) return
        _uiState.value = _uiState.value.copy(
            terms = _uiState.value.terms.map { term ->
                if (term.key == key) term.copy(startDate = value) else term
            }
        )
    }

    /** 添加一个不在快捷列表中的学期，仍然使用学校教务系统的 xnm/xqm 参数。 */
    fun addCustomTerm(xnmText: String, xqmText: String, label: String, startDate: String): Boolean {
        if (_uiState.value.busy) return false
        val xnm = xnmText.toIntOrNull()
        val xqm = xqmText.trim()
        if (xnm == null || xnm <= 0 || xqm.isBlank() || label.isBlank()) {
            _uiState.value = _uiState.value.copy(status = "请填写有效的学年、学期码和课表名称")
            return false
        }
        if (runCatching { LocalDate.parse(startDate) }.isFailure) {
            _uiState.value = _uiState.value.copy(status = "第一周日期格式应为 yyyy-MM-dd")
            return false
        }
        val baseKey = "$xnm-$xqm"
        val key = generateSequence(baseKey) { previous -> "$previous-1" }
            .first { candidate -> _uiState.value.terms.none { it.key == candidate } }
        val custom = ImportTermOption(
            key = key,
            xnm = xnm,
            xqm = xqm,
            title = label,
            subtitle = "自定义学期 · xnm=$xnm，xqm=$xqm",
            label = label,
            startDate = startDate,
            selected = true,
            builtIn = false
        )
        if (importMode == ImportMode.OVERWRITE) {
            // 覆盖模式统一单选：新自定义学期顶掉之前的 JSON/空课表暂存，并取消其它选中。
            val staleKeys = _uiState.value.terms.filter { it.isJson || it.isBlank }.map { it.key }
            staleKeys.forEach { jsonPayloads.remove(it) }
            _uiState.value = _uiState.value.copy(
                terms = _uiState.value.terms
                    .filterNot { it.isJson || it.isBlank }
                    .map { it.copy(selected = false) } + custom,
                status = "已添加“$label”，覆盖模式下仅保留这一份"
            )
        } else {
            _uiState.value = _uiState.value.copy(
                terms = _uiState.value.terms + custom,
                status = "已添加“$label”，导入时会一起处理"
            )
        }
        previewTerm(key)
        return true
    }

    fun removeCustomTerm(key: String) {
        if (_uiState.value.busy) return
        jsonPayloads.remove(key)
        _uiState.value = _uiState.value.copy(terms = _uiState.value.terms.filterNot { it.key == key })
    }

    fun import(onDone: () -> Unit) {
        val selected = _uiState.value.terms.filter { it.selected }
        if (selected.isEmpty()) {
            _uiState.value = _uiState.value.copy(status = "请至少选择一个要导入的学期课表")
            return
        }
        val notReady = selected.firstOrNull { !it.previewed }
        if (notReady != null) {
            _uiState.value = _uiState.value.copy(status = "请先获取“${notReady.label}”的实际课程列表")
            previewTerm(notReady.key)
            return
        }
        val empty = selected.firstOrNull { !it.isBlank && it.courses.none(ImportCourseOption::selected) }
        if (empty != null) {
            _uiState.value = _uiState.value.copy(status = "请至少选择“${empty.label}”中的一门课程")
            return
        }
        if (_uiState.value.busy) return
        conflictPolicies.clear()
        pendingOverwriteTargetId = null
        if (importMode == ImportMode.ADD) {
            // 添加模式：一律新建，不弹任何覆盖相关选择。
            selected.forEach { conflictPolicies[it.key] = ExistingTimetablePolicy.CREATE_NEW }
            runImportQueue(selected, onDone = onDone)
        } else {
            // 覆盖模式：先弹一次“确认替换当前课表”。
            viewModelScope.launch {
                val current = withContext(Dispatchers.IO) {
                    selection.read()?.let { local.getTimetableOrNull(it) }
                }
                if (current == null) {
                    _uiState.value = _uiState.value.copy(status = "当前没有可覆盖的课表，请先新建或导入课表")
                    return@launch
                }
                _uiState.value = _uiState.value.copy(
                    status = "确认后将替换当前课表",
                    conflictTimetable = current,
                    conflictTerm = null,
                    pendingTerms = selected,
                    importedCount = 0
                )
            }
        }
    }

    fun dismissConflict() {
        if (_uiState.value.busy) return
        conflictPolicies.clear()
        pendingOverwriteTargetId = null
        _uiState.value = _uiState.value.copy(
            conflictTimetable = null,
            conflictTerm = null,
            pendingTerms = emptyList(),
            importedCount = 0,
            status = "已取消导入"
        )
    }

    /** 覆盖模式确认执行：用待导入列表中的唯一一份替换当前课表。 */
    fun confirmOverwrite(onDone: () -> Unit) {
        val state = _uiState.value
        val pending = state.pendingTerms
        val target = state.conflictTimetable
        if (state.busy || pending.isEmpty() || target == null) return
        pending.forEach { conflictPolicies[it.key] = ExistingTimetablePolicy.OVERWRITE }
        pendingOverwriteTargetId = target.id
        _uiState.value = _uiState.value.copy(conflictTimetable = null, conflictTerm = null)
        runImportQueue(pending, onDone = onDone, alreadyImported = state.importedCount)
    }

    /** 顺序导入多个学期；每个学期按预确认的方式（覆盖当前课表/新建）执行。 */
    private fun runImportQueue(
        queue: List<ImportTermOption>,
        onDone: () -> Unit,
        alreadyImported: Int = 0
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                busy = true,
                status = if (alreadyImported == 0) "正在准备导入 ${queue.size} 个课表…" else "已处理 $alreadyImported 个，继续导入…",
                conflictTimetable = null,
                conflictTerm = null,
                pendingTerms = queue,
                importedCount = alreadyImported
            )
            var remaining = queue
            var imported = alreadyImported

            while (remaining.isNotEmpty()) {
                val term = remaining.first()
                // 每个学期在确认导入方式时已取得覆盖/新建决定。
                val policy = conflictPolicies[term.key] ?: ExistingTimetablePolicy.CREATE_NEW
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val profile = local.ensureProfile()
                        when {
                            term.isJson -> {
                                // JSON 暂存项：从本地解析结果落库，不访问教务网络。
                                val data = jsonPayloads[term.key] ?: error("JSON 课表数据已失效，请重新粘贴")
                                val selectedIndices = term.courses
                                    .filter { it.selected }
                                    .mapNotNull { it.remoteKey.removePrefix("json-course-").toIntOrNull() }
                                    .toSet()
                                val filtered = data.copy(
                                    meta = data.meta.copy(label = term.label, startDate = term.startDate),
                                    courses = data.courses.filterIndexed { index, _ -> index in selectedIndices }
                                )
                                local.importTimetableFromJson(
                                    profileId = profile.id,
                                    data = filtered,
                                    overrideTargetId = if (policy == ExistingTimetablePolicy.OVERWRITE) pendingOverwriteTargetId else null
                                )
                            }
                            term.isBlank -> {
                                // 空课表暂存项：新建空表（添加模式）或清空当前课表（覆盖模式）。
                                val empty = com.example.awake.data.export.TimetableJson.JsonTimetableData(
                                    meta = com.example.awake.data.export.TimetableJson.JsonTimetableMeta(
                                        label = term.label,
                                        xnm = 0,
                                        xqm = "manual",
                                        startDate = term.startDate,
                                        totalWeeks = 30
                                    ),
                                    courses = emptyList()
                                )
                                local.importTimetableFromJson(
                                    profileId = profile.id,
                                    data = empty,
                                    overrideTargetId = if (policy == ExistingTimetablePolicy.OVERWRITE) pendingOverwriteTargetId else null
                                )
                            }
                            else -> {
                                importer(
                                    profile.id,
                                    term.xnm,
                                    term.xqm,
                                    term.label,
                                    policy,
                                    term.courses.filter { it.selected }.map { it.remoteKey }.toSet(),
                                    overrideTargetId = if (policy == ExistingTimetablePolicy.OVERWRITE) pendingOverwriteTargetId else null
                                ).timetable.also { importedResult ->
                                    local.updateTimetable(
                                        importedResult.copy(
                                            label = if (policy == ExistingTimetablePolicy.OVERWRITE) term.label else importedResult.label,
                                            startDate = term.startDate
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                val importedResult = result.getOrElse { error ->
                    _uiState.value = _uiState.value.copy(
                        busy = false,
                        status = "“${term.label}”导入失败：${error.message ?: "未知错误"}",
                        pendingTerms = remaining,
                        importedCount = imported
                    )
                    return@launch
                }
                selection.setSelected(importedResult.id)
                // JSON 分享导入的课表在首次刷新（含自动同步）前需要用户确认。
                if (term.isJson) jsonTimetableStore?.markJsonImported(importedResult.id)
                reminderCoordinator.reschedule(importedResult.id)
                imported += 1
                remaining = remaining.drop(1)
                _uiState.value = _uiState.value.copy(
                    status = "正在导入：$imported/${queue.size} · ${term.label}",
                    pendingTerms = remaining,
                    importedCount = imported
                )
            }

            _uiState.value = _uiState.value.copy(
                busy = false,
                status = "已成功导入 $imported 个课表",
                conflictTimetable = null,
                conflictTerm = null,
                pendingTerms = emptyList(),
                importedCount = imported
            )
            conflictPolicies.clear()
            pendingOverwriteTargetId = null
            jsonPayloads.clear()
            onDone()
        }
    }

    /** 暂存一个空白手动课表到「待导入课表」列表（与 JSON 一样统一导入执行）。 */
    fun stageBlankTable(label: String) {
        val normalized = label.trim()
        if (normalized.isEmpty()) {
            _uiState.value = _uiState.value.copy(status = "课表名称不能为空")
            return
        }
        if (_uiState.value.busy) return
        val key = "blank-${System.currentTimeMillis()}"
        val option = ImportTermOption(
            key = key,
            xnm = 0,
            xqm = "manual",
            title = normalized,
            subtitle = "空课表 · 手动添加课程",
            label = normalized,
            startDate = mondayOnOrAfter(LocalDate.now()).toString(),
            selected = true,
            builtIn = false,
            courses = emptyList(),
            previewed = true,
            isBlank = true
        )
        if (importMode == ImportMode.OVERWRITE) {
            // 覆盖模式统一单选：新空课表顶掉之前的 JSON/空课表暂存，并取消教务学期选中。
            val staleKeys = _uiState.value.terms.filter { it.isJson || it.isBlank }.map { it.key }
            staleKeys.forEach { jsonPayloads.remove(it) }
            _uiState.value = _uiState.value.copy(
                terms = _uiState.value.terms
                    .filterNot { it.isJson || it.isBlank }
                    .map { it.copy(selected = false) } + option,
                status = "已暂存空课表“${normalized}”（覆盖模式仅保留这一份）"
            )
        } else {
            _uiState.value = _uiState.value.copy(
                terms = _uiState.value.terms + option,
                status = "已暂存空课表“${normalized}”，可继续添加，最后统一导入"
            )
        }
    }

    /**
     * 解析分享文本并暂存到「待导入课表」列表（不立即导入）。
     * 由统一的「导入已选课表」按钮连同教务学期一起执行。
     * 解析在 IO 线程执行，避免大文本卡住主线程。
     */
    fun stageJsonText(raw: String): Boolean {
        if (_uiState.value.busy) return false
        _uiState.value = _uiState.value.copy(busy = true, status = "正在解析课表 JSON…")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val data = com.example.awake.data.export.TimetableJson.parse(raw)
                    val key = "json-${System.currentTimeMillis()}"
                    val courses = data.courses.mapIndexed { index, course ->
                        ImportCourseOption(
                            remoteKey = "json-course-$index",
                            name = course.name.ifBlank { "未命名课程" },
                            day = course.sections.firstOrNull()?.dayOfWeek ?: 1,
                            periodText = course.sections.firstOrNull()?.let { "${it.startPeriod}-${it.endPeriod}" } ?: "",
                            subtitle = buildList {
                                if (course.teacher.isNotBlank()) add(course.teacher)
                                if (course.sections.size > 1) add("${course.sections.size} 个时段") else add("1 个时段")
                            }.joinToString(" · "),
                            selected = true
                        )
                    }
                    val option = ImportTermOption(
                        key = key,
                        xnm = data.meta.xnm,
                        xqm = data.meta.xqm,
                        title = data.meta.label,
                        subtitle = "JSON 导入 · ${data.courses.size} 门课程",
                        label = data.meta.label,
                        startDate = data.meta.startDate ?: mondayOnOrAfter(LocalDate.now()).toString(),
                        selected = true,
                        builtIn = false,
                        courses = courses,
                        previewed = true,
                        isJson = true
                    )
                    key to (data to option)
                }
            }
            result.onSuccess { (key, pair) ->
                val (data, option) = pair
                jsonPayloads[key] = data
                if (importMode == ImportMode.OVERWRITE) {
                    // 覆盖模式统一单选：新 JSON 顶掉之前的 JSON 暂存项，并取消教务学期选中。
                    val oldJsonKeys = _uiState.value.terms.filter { it.isJson }.map { it.key }
                    oldJsonKeys.forEach { jsonPayloads.remove(it) }
                    _uiState.value = _uiState.value.copy(
                        busy = false,
                        terms = _uiState.value.terms
                            .filterNot { it.isJson }
                            .map { it.copy(selected = false) } + option,
                        status = "已暂存 JSON 课表“${data.meta.label}”（覆盖模式仅保留这一份）"
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        busy = false,
                        terms = _uiState.value.terms + option,
                        status = "已暂存 JSON 课表“${data.meta.label}”，可继续添加，最后统一导入"
                    )
                }
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    busy = false,
                    status = error.message ?: "JSON 解析失败"
                )
            }
        }
        return true
    }
}

private fun RemoteAcademicYear.toImportTermOption(semester: RemoteSemester, selected: Boolean): ImportTermOption {
    val academicYear = label.ifBlank { "$xnm-${xnm + 1}" }
    val termLabel = "$academicYear ${semester.label}"
    val startDate = when (semester.xqm) {
        "3", "1" -> mondayOnOrAfter(LocalDate.of(xnm, 8, 25))
        "12", "2" -> mondayOnOrAfter(LocalDate.of(xnm + 1, 2, 15))
        "16" -> mondayOnOrAfter(LocalDate.of(xnm + 1, 7, 1))
        else -> mondayOnOrAfter(LocalDate.of(xnm, 8, 25))
    }
    return ImportTermOption(
        key = "$xnm-${semester.xqm}",
        xnm = xnm,
        xqm = semester.xqm,
        title = termLabel,
        subtitle = "${semester.label} · 学期码 ${semester.xqm}",
        label = termLabel,
        startDate = startDate.toString(),
        selected = selected
    )
}

private fun defaultTermOptions(): List<ImportTermOption> {
    val today = LocalDate.now()
    val startYear = if (today.monthValue >= 8) today.year else today.year - 1
    val firstDate = mondayOnOrAfter(LocalDate.of(startYear, 8, 25))
    val secondDate = mondayOnOrAfter(LocalDate.of(startYear + 1, 2, 15))
    val summerDate = mondayOnOrAfter(LocalDate.of(startYear + 1, 7, 1))
    val academicYear = "$startYear-${startYear + 1}"
    return listOf(
        ImportTermOption(
            key = "$startYear-3",
            xnm = startYear,
            xqm = "3",
            title = "$academicYear 第一学期",
            subtitle = "秋季学期 · 学期码 3",
            label = "$academicYear 第一学期",
            startDate = firstDate.toString(),
            selected = false
        ),
        ImportTermOption(
            key = "$startYear-12",
            xnm = startYear,
            xqm = "12",
            title = "$academicYear 第二学期",
            subtitle = "春季学期 · 学期码 12",
            label = "$academicYear 第二学期",
            startDate = secondDate.toString()
        ),
        ImportTermOption(
            key = "$startYear-16",
            xnm = startYear,
            xqm = "16",
            title = "$academicYear 暑期学期",
            subtitle = "暑期学期 · 学期码 16",
            label = "$academicYear 暑期学期",
            startDate = summerDate.toString()
        )
    )
}

private fun mondayOnOrAfter(date: LocalDate): LocalDate =
    date.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))

class TermImportViewModelFactory(
    private val local: LocalTimetableRepository,
    private val importer: ImportTimetableUseCase,
    private val reminderCoordinator: ReminderCoordinator,
    private val selection: TimetableSelectionStore,
    private val remote: ScutScheduleRepository,
    private val academicTermsCache: AcademicTermsCache,
    private val importMode: ImportMode = ImportMode.ADD,
    private val jsonTimetableStore: com.example.awake.data.repository.JsonTimetableStore? = null
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        TermImportViewModel(local, importer, reminderCoordinator, selection, remote, academicTermsCache, importMode, jsonTimetableStore) as T
}

private fun ScutCourseDto.toImportOption(): ImportCourseOption = ImportCourseOption(
    remoteKey = remoteKey(),
    name = name.ifBlank { "未命名课程" },
    day = day,
    periodText = periods,
    subtitle = buildList {
        add(if (dayName.isNotBlank()) dayName else "星期${dayNameFor(day)}")
        if (periods.isNotBlank()) add("第${periods}节")
        if (weeks.isNotBlank()) add(weeks)
        if (teacher.isNotBlank()) add(teacher)
        if (room.isNotBlank()) add(room)
    }.joinToString(" · ")
)

private fun firstPeriod(text: String): Int = Regex("\\d+").find(text)?.value?.toIntOrNull() ?: 99

private fun dayNameFor(day: Int): String = when (day) {
    1 -> "一"
    2 -> "二"
    3 -> "三"
    4 -> "四"
    5 -> "五"
    6 -> "六"
    7 -> "日"
    else -> "未知"
}





