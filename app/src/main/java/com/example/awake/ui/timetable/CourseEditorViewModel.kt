package com.example.awake.ui.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.awake.data.local.CourseEntity
import com.example.awake.data.local.CourseSectionEntity
import com.example.awake.data.local.PeriodConfigDefaults
import com.example.awake.data.repository.LocalTimetableRepository
import com.example.awake.data.repository.ReminderCoordinator
import com.example.awake.domain.model.CourseIdentity
import com.example.awake.domain.parser.WeekExpressionParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class CourseEditorUiState(
    val name: String = "",
    val teacher: String = "",
    val room: String = "",
    val dayOfWeek: String,
    val startPeriod: String,
    val endPeriod: String,
    /** 规范化的周次区间文本，如 “1-6,9,12-16”；来源为周选择网格的选中集合。 */
    val weeks: String = "1-30",
    /** >0 表示编辑已有时段；否则按 masterId 决定是“挂到已有课程”还是“新建课程”。 */
    val sectionId: Long = -1L,
    val masterId: Long = -1L,
    val showWeekDialog: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null
)

/**
 * 三种模式：
 * 1. 新建课程（默认空白时段入口）：创建 MANUAL 主课程 + 第一个时段；
 * 2. 添加时段（来自详情页“添加时段”）：在 masterId 课程下新增一个时段；
 * 3. 编辑时段（sectionId > 0）：更新该时段的时间/教室/周次，并同步主记录名称/教师。
 */
class CourseEditorViewModel(
    private val local: LocalTimetableRepository,
    private val reminderCoordinator: ReminderCoordinator,
    private val timetableId: Long,
    initialDay: Int,
    initialStartPeriod: Int,
    private val initialSectionId: Long = -1L,
    private val initialMasterId: Long = -1L
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        CourseEditorUiState(
            dayOfWeek = initialDay.coerceIn(1, 7).toString(),
            startPeriod = initialStartPeriod.coerceIn(1, PeriodConfigDefaults.periodCount).toString(),
            endPeriod = initialStartPeriod.coerceIn(1, PeriodConfigDefaults.periodCount).toString(),
            sectionId = initialSectionId,
            masterId = initialMasterId
        )
    )
    val uiState: StateFlow<CourseEditorUiState> = _uiState.asStateFlow()

    /** 从路由传入的课表 id；从详情页进入时由课程主记录推断。 */
    private var effectiveTimetableId: Long = timetableId

    init {
        loadContext()
    }

    private fun loadContext() {
        viewModelScope.launch {
            try {
                when {
                    initialSectionId > 0 -> {
                        val section = local.getSectionOrNull(initialSectionId) ?: error("时段不存在或已被删除")
                        val master = local.observeCourse(section.courseId).first()
                        effectiveTimetableId = master?.timetableId ?: effectiveTimetableId
                        _uiState.value = _uiState.value.copy(
                            name = master?.name.orEmpty(),
                            teacher = section.teacher.ifBlank { master?.teacher.orEmpty() },
                            room = section.room,
                            dayOfWeek = section.dayOfWeek.toString(),
                            startPeriod = section.startPeriod.toString(),
                            endPeriod = section.endPeriod.toString(),
                            weeks = canonicalWeeks(section.rawWeekText)
                        )
                    }
                    initialMasterId > 0 -> {
                        val master = local.observeCourse(initialMasterId).first()
                        effectiveTimetableId = master?.timetableId ?: effectiveTimetableId
                        _uiState.value = _uiState.value.copy(
                            name = master?.name.orEmpty(),
                            teacher = master?.teacher.orEmpty()
                        )
                    }
                }
            } catch (error: Throwable) {
                _uiState.value = _uiState.value.copy(error = error.message ?: "无法加载课程信息")
            }
        }
    }

    fun setName(value: String) = update { copy(name = value, error = null) }
    fun setTeacher(value: String) = update { copy(teacher = value, error = null) }
    fun setRoom(value: String) = update { copy(room = value, error = null) }
    fun setDay(value: String) = update { copy(dayOfWeek = value.filter(Char::isDigit), error = null) }
    fun setStart(value: String) = update { copy(startPeriod = value.filter(Char::isDigit), error = null) }
    fun setEnd(value: String) = update { copy(endPeriod = value.filter(Char::isDigit), error = null) }

    fun setWeekDialogVisible(visible: Boolean) = update { copy(showWeekDialog = visible, error = null) }

    /** 周选择网格确认：把选中集合写回规范化区间文本。 */
    fun applyWeekSelection(weeks: Set<Int>) = update {
        copy(
            weeks = com.example.awake.domain.parser.WeekSelection.format(weeks).ifBlank { "1-30" },
            showWeekDialog = false,
            error = null
        )
    }

    /** 当前周次文本对应的选中集合，供周选择网格作为初始状态。 */
    fun selectedWeeks(): Set<Int> =
        com.example.awake.domain.parser.WeekExpressionParser.parse(_uiState.value.weeks, maxWeek = 30)
            .takeIf { it.warning == null }?.weeks ?: emptySet()

    /** 把任意周次表达式归一为合并区间的展示文本；无法解析时保留原文交给保存时的校验提示。 */
    private fun canonicalWeeks(raw: String): String {
        val parsed = WeekExpressionParser.parse(raw, maxWeek = 30)
        return if (parsed.warning == null && parsed.weeks.isNotEmpty()) {
            com.example.awake.domain.parser.WeekSelection.format(parsed.weeks)
        } else {
            raw
        }
    }

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
            startValue == null || endValue == null ||
                startValue !in 1..PeriodConfigDefaults.periodCount || endValue !in 1..PeriodConfigDefaults.periodCount ->
                "节次必须是 1-${PeriodConfigDefaults.periodCount}"
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
                persist(
                    name = state.name.trim(),
                    sectionTeacher = state.teacher.trim(),
                    room = state.room.trim(),
                    day = day,
                    start = start,
                    end = end,
                    rawWeeks = state.weeks.trim(),
                    weeks = weekResult.weeks,
                    state = state
                )
                reminderCoordinator.reschedule(effectiveTimetableId)
            }.onSuccess { onDone() }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        busy = false,
                        error = error.message ?: "保存课程失败"
                    )
                }
        }
    }

    private suspend fun persist(
        name: String,
        sectionTeacher: String,
        room: String,
        day: Int,
        start: Int,
        end: Int,
        rawWeeks: String,
        weeks: Set<Int>,
        state: CourseEditorUiState
    ) {
        val periods = "$start-$end"
        if (state.sectionId > 0) {
            // 编辑已有时段：名称/教师写入主记录，时间/教室/周次写入时段。
            val section = local.getSectionOrNull(state.sectionId) ?: error("时段不存在或已被删除")
            val master = local.observeCourse(section.courseId).first() ?: error("课程不存在")
            local.updateCourse(master.copy(name = name, teacher = sectionTeacher.ifBlank { master.teacher }))
            local.updateSection(
                section.copy(
                    source = master.source,
                    remoteKey = CourseIdentity.sectionKey(master.source, name, sectionTeacher, room, day, periods, rawWeeks, master.className),
                    dayOfWeek = day,
                    startPeriod = start,
                    endPeriod = end,
                    room = room,
                    teacher = sectionTeacher,
                    rawWeekText = rawWeeks,
                    // 同步课程被用户改过的时段打锁；未来差量同步据此跳过。
                    locked = master.source != "MANUAL"
                ),
                weeks
            )
            return
        }
        if (state.masterId > 0) {
            // 在已有课程下新增一个时段；主记录名称/教师一并更新。
            val master = local.observeCourse(state.masterId).first() ?: error("课程不存在")
            val updatedMaster = master.copy(name = name, teacher = sectionTeacher.ifBlank { master.teacher })
            local.updateCourse(updatedMaster)
            local.insertManualCourse(
                course = updatedMaster,
                section = CourseSectionEntity(
                    courseId = 0,
                    source = master.source,
                    remoteKey = CourseIdentity.sectionKey(master.source, name, sectionTeacher, room, day, periods, rawWeeks, master.className),
                    dayOfWeek = day,
                    startPeriod = start,
                    endPeriod = end,
                    room = room,
                    teacher = sectionTeacher,
                    rawWeekText = rawWeeks
                ),
                weeks = weeks
            )
            return
        }
        // 全新本地课程：主课程 + 单个时段，同一身份再次保存时收敛到同一主课程/时段。
        // 颜色：优先使用当前课表里未被占用的预设色，用尽再随机生成。
        val usedColors = local.getAllSlots(effectiveTimetableId).map { it.color }
        local.insertManualCourse(
            course = CourseEntity(
                timetableId = effectiveTimetableId,
                source = "MANUAL",
                remoteKey = CourseIdentity.masterKey("MANUAL", name, null, sectionTeacher),
                name = name,
                teacher = sectionTeacher,
                color = com.example.awake.domain.model.pickNewCourseColor(usedColors)
            ),
            section = CourseSectionEntity(
                courseId = 0,
                source = "MANUAL",
                remoteKey = CourseIdentity.sectionKey("MANUAL", name, sectionTeacher, room, day, periods, rawWeeks, null),
                dayOfWeek = day,
                startPeriod = start,
                endPeriod = end,
                room = room,
                teacher = sectionTeacher,
                rawWeekText = rawWeeks
            ),
            weeks = weeks
        )
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
    private val startPeriod: Int,
    private val sectionId: Long = -1L,
    private val masterId: Long = -1L
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        CourseEditorViewModel(local, reminderCoordinator, timetableId, dayOfWeek, startPeriod, sectionId, masterId) as T
}