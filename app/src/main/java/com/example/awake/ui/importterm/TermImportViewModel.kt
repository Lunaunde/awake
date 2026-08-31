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
    val previewError: String? = null
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
    val previewingTermKey: String? = null
)

class TermImportViewModel(
    private val local: LocalTimetableRepository,
    private val importer: ImportTimetableUseCase,
    private val reminderCoordinator: ReminderCoordinator,
    private val selection: TimetableSelectionStore,
    private val remote: ScutScheduleRepository,
    private val academicTermsCache: AcademicTermsCache
) : ViewModel() {
    private val _uiState = MutableStateFlow(TermImportUiState())
    val uiState: StateFlow<TermImportUiState> = _uiState.asStateFlow()
    /** 防止初始化、重组或快速点击同时发起多个“实际课程列表”请求。 */
    private val previewMutex = Mutex()

    init {
        // 先读取教务查询页中的真实学年列表，再由用户选择学年和学期。
        // 不在初始化阶段自动请求课程，避免把“当前学年”误当成唯一选项。
        val cached = academicTermsCache.years
        if (cached.isNotEmpty()) applyAcademicYears(cached)
        // 即使有缓存也后台检查会话并更新学年列表，避免顶部状态与实际请求结果不一致。
        loadAcademicYears(force = true)
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
        _uiState.value = _uiState.value.copy(
            terms = _uiState.value.terms.map { term ->
                if (term.key == key) term.copy(selected = !term.selected) else term
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
        _uiState.value = _uiState.value.copy(
            terms = _uiState.value.terms + custom,
            status = "已添加“$label”，导入时会一起处理"
        )
        previewTerm(key)
        return true
    }

    fun removeCustomTerm(key: String) {
        if (_uiState.value.busy) return
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
        val empty = selected.firstOrNull { it.courses.none(ImportCourseOption::selected) }
        if (empty != null) {
            _uiState.value = _uiState.value.copy(status = "请至少选择“${empty.label}”中的一门课程")
            return
        }
        if (_uiState.value.busy) return
        runImportQueue(selected, firstPolicy = null, onDone = onDone)
    }

    fun dismissConflict() {
        if (_uiState.value.busy) return
        _uiState.value = _uiState.value.copy(
            conflictTimetable = null,
            conflictTerm = null,
            pendingTerms = emptyList(),
            importedCount = 0,
            status = "已取消剩余导入"
        )
    }

    fun overwriteExisting(onDone: () -> Unit) = resolveConflict(ExistingTimetablePolicy.OVERWRITE, onDone)

    fun createNew(onDone: () -> Unit) = resolveConflict(ExistingTimetablePolicy.CREATE_NEW, onDone)

    private fun resolveConflict(policy: ExistingTimetablePolicy, onDone: () -> Unit) {
        val state = _uiState.value
        val pending = state.pendingTerms
        if (state.busy || state.conflictTerm == null || pending.isEmpty()) return
        runImportQueue(pending, firstPolicy = policy, onDone = onDone, alreadyImported = state.importedCount)
    }

    /** 顺序导入多个学期；每遇到已存在的学期就暂停并让用户选择覆盖或新建。 */
    private fun runImportQueue(
        queue: List<ImportTermOption>,
        firstPolicy: ExistingTimetablePolicy?,
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
            var policyForFirst: ExistingTimetablePolicy? = firstPolicy

            while (remaining.isNotEmpty()) {
                val term = remaining.first()
                val existing = runCatching {
                    withContext(Dispatchers.IO) {
                        local.findTimetable(local.ensureProfile().id, term.xnm, term.xqm)
                    }
                }.getOrElse { error ->
                    _uiState.value = _uiState.value.copy(busy = false, status = error.message ?: "检查课表失败")
                    return@launch
                }

                if (existing != null && policyForFirst == null) {
                    _uiState.value = _uiState.value.copy(
                        busy = false,
                        status = if (imported == 0) "发现已有课表，请选择导入方式" else "已处理 $imported 个，下面的课表需要确认",
                        conflictTimetable = existing,
                        conflictTerm = term,
                        pendingTerms = remaining,
                        importedCount = imported
                    )
                    return@launch
                }

                val policy = policyForFirst ?: ExistingTimetablePolicy.CREATE_NEW
                policyForFirst = null
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val profile = local.ensureProfile()
                        importer(
                            profile.id,
                            term.xnm,
                            term.xqm,
                            term.label,
                            policy,
                            term.courses.filter { it.selected }.map { it.remoteKey }.toSet()
                        ).also { importedResult ->
                            local.updateTimetable(
                                importedResult.timetable.copy(
                                    label = if (policy == ExistingTimetablePolicy.OVERWRITE) term.label else importedResult.timetable.label,
                                    startDate = term.startDate
                                )
                            )
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
                selection.setSelected(importedResult.timetable.id)
                reminderCoordinator.reschedule(importedResult.timetable.id)
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
            onDone()
        }
    }

    fun seedDemo() {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true, status = "正在生成离线演示课表…")
            runCatching { local.seedDemoTimetable() }
                .onSuccess { timetable ->
                    selection.setSelected(timetable.id)
                    reminderCoordinator.reschedule(timetable.id)
                    _uiState.value = _uiState.value.copy(busy = false, status = "已生成离线演示课表")
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(busy = false, status = error.message ?: "生成失败")
                }
        }
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
    private val academicTermsCache: AcademicTermsCache
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        TermImportViewModel(local, importer, reminderCoordinator, selection, remote, academicTermsCache) as T
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





