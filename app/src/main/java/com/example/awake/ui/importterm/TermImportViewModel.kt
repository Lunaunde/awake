package com.example.awake.ui.importterm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.awake.data.local.TimetableEntity
import com.example.awake.data.repository.LocalTimetableRepository
import com.example.awake.data.repository.ReminderCoordinator
import com.example.awake.data.repository.TimetableSelectionStore
import com.example.awake.domain.usecase.ExistingTimetablePolicy
import com.example.awake.domain.usecase.ImportTimetableUseCase
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TermImportUiState(
    val xnm: String = "2026",
    val xqm: String = "3",
    val label: String = "2026-2027 第一学期",
    val startDate: String = LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)).toString(),
    val busy: Boolean = false,
    val status: String? = null,
    val conflictTimetable: TimetableEntity? = null
)

class TermImportViewModel(
    private val local: LocalTimetableRepository,
    private val importer: ImportTimetableUseCase,
    private val reminderCoordinator: ReminderCoordinator,
    private val selection: TimetableSelectionStore
) : ViewModel() {
    private val _uiState = MutableStateFlow(TermImportUiState())
    val uiState: StateFlow<TermImportUiState> = _uiState.asStateFlow()

    fun setXnm(value: String) { _uiState.value = _uiState.value.copy(xnm = value.filter(Char::isDigit)) }
    fun setXqm(value: String) { _uiState.value = _uiState.value.copy(xqm = value.filter(Char::isDigit)) }
    fun setLabel(value: String) { _uiState.value = _uiState.value.copy(label = value) }
    fun setStartDate(value: String) { _uiState.value = _uiState.value.copy(startDate = value) }

    fun import(onDone: () -> Unit) {
        val state = _uiState.value
        if (!validate(state) || state.busy) return
        viewModelScope.launch {
            _uiState.value = state.copy(busy = true, status = "正在检查是否已有同学期课表…")
            val existing = runCatching {
                withContext(Dispatchers.IO) {
                    local.findTimetable(ensureProfileId(), state.xnm.toInt(), state.xqm)
                }
            }.getOrElse { error ->
                _uiState.value = _uiState.value.copy(busy = false, status = error.message ?: "检查课表失败")
                return@launch
            }
            if (existing != null) {
                _uiState.value = _uiState.value.copy(
                    busy = false,
                    status = null,
                    conflictTimetable = existing
                )
            } else {
                executeImport(state, ExistingTimetablePolicy.CREATE_NEW, onDone)
            }
        }
    }

    fun dismissConflict() {
        if (!_uiState.value.busy) {
            _uiState.value = _uiState.value.copy(conflictTimetable = null)
        }
    }

    fun overwriteExisting(onDone: () -> Unit) {
        resolveConflict(ExistingTimetablePolicy.OVERWRITE, onDone)
    }

    fun createNew(onDone: () -> Unit) {
        resolveConflict(ExistingTimetablePolicy.CREATE_NEW, onDone)
    }

    private fun resolveConflict(policy: ExistingTimetablePolicy, onDone: () -> Unit) {
        val state = _uiState.value
        if (state.busy || state.conflictTimetable == null) return
        _uiState.value = state.copy(conflictTimetable = null)
        viewModelScope.launch { executeImport(state, policy, onDone) }
    }

    private suspend fun executeImport(
        state: TermImportUiState,
        policy: ExistingTimetablePolicy,
        onDone: () -> Unit
    ) {
        _uiState.value = _uiState.value.copy(busy = true, status = "正在导入课表…")
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val profile = local.ensureProfile()
                importer(profile.id, state.xnm.toInt(), state.xqm, state.label, policy).also { imported ->
                    val saved = imported.timetable.copy(
                        label = if (policy == ExistingTimetablePolicy.OVERWRITE) state.label else imported.timetable.label,
                        startDate = state.startDate
                    )
                    local.updateTimetable(saved)
                }
            }
        }
        val message = result.fold(
            onSuccess = { value ->
                selection.setSelected(value.timetable.id)
                reminderCoordinator.reschedule(value.timetable.id)
                if (value.warnings.isEmpty()) "导入成功" else "导入成功，跳过 ${value.warnings.size} 条无法解析的记录"
            },
            onFailure = { error -> error.message ?: "导入失败，旧数据未被覆盖" }
        )
        _uiState.value = _uiState.value.copy(busy = false, status = message)
        if (result.isSuccess) onDone()
    }

    private suspend fun ensureProfileId(): Long = withContext(Dispatchers.IO) { local.ensureProfile().id }

    private fun validate(state: TermImportUiState): Boolean {
        if (state.xnm.isBlank() || state.xqm.isBlank() || state.label.isBlank()) return false
        if (runCatching { LocalDate.parse(state.startDate) }.isFailure) {
            _uiState.value = state.copy(status = "学期开始日期格式应为 yyyy-MM-dd")
            return false
        }
        return true
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
                .onFailure { error -> _uiState.value = _uiState.value.copy(busy = false, status = error.message ?: "生成失败") }
        }
    }
}

class TermImportViewModelFactory(
    private val local: LocalTimetableRepository,
    private val importer: ImportTimetableUseCase,
    private val reminderCoordinator: ReminderCoordinator,
    private val selection: TimetableSelectionStore
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        TermImportViewModel(local, importer, reminderCoordinator, selection) as T
}
