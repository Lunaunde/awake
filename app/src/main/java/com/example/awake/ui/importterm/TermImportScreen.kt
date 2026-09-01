package com.example.awake.ui.importterm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermImportScreen(
    viewModel: TermImportViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onLogin: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showCustomTermDialog by remember { mutableStateOf(false) }
    var jsonText by remember { mutableStateOf("") }
    var blankLabel by remember { mutableStateOf("") }
    var importSectionExpanded by remember { mutableStateOf(true) }
    var jsonSectionExpanded by remember { mutableStateOf(false) }
    var blankSectionExpanded by remember { mutableStateOf(false) }
    var termMenuExpanded by remember { mutableStateOf(false) }
    var academicYearMenuExpanded by remember { mutableStateOf(false) }
    var semesterMenuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("选择导入课表") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !state.busy) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showCustomTermDialog = true }, enabled = !state.busy) {
                        Icon(Icons.Default.Add, contentDescription = "添加自定义学期")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ImportSection(
                    title = "教务系统导入",
                    expanded = importSectionExpanded,
                    onExpandedChange = { importSectionExpanded = it }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val sessionColor = when (state.sessionStatus) {
                            SessionUiStatus.AVAILABLE -> MaterialTheme.colorScheme.primary
                            SessionUiStatus.CHECKING -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.error
                        }
                        val sessionIcon = when (state.sessionStatus) {
                            SessionUiStatus.AVAILABLE -> Icons.Default.Check
                            SessionUiStatus.CHECKING -> Icons.Default.Refresh
                            SessionUiStatus.NETWORK_ERROR -> Icons.Default.CloudOff
                            else -> Icons.Default.ErrorOutline
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = sessionColor.copy(alpha = 0.12f),
                            shape = MaterialTheme.shapes.large
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(sessionIcon, contentDescription = null, tint = sessionColor)
                                Column {
                                    Text(
                                        when (state.sessionStatus) {
                                            SessionUiStatus.CHECKING -> "正在检查教务会话…"
                                            SessionUiStatus.AVAILABLE -> "教务系统会话可用"
                                            SessionUiStatus.UNAVAILABLE -> "教务会话已失效"
                                            SessionUiStatus.NETWORK_ERROR -> "教务系统网络不可用"
                                            SessionUiStatus.SERVER_ERROR -> "教务系统暂时不可用"
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = sessionColor
                                    )
                                    Text(
                                        state.sessionDetail ?: "选择学期并获取课程，获取成功后才会加入待导入列表",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        if (!state.isLoggedIn) {
                            OutlinedButton(
                                onClick = onLogin,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("未登录 · 前往官方页面登录") }
                        }
                        AcademicTermSelector(
                            state = state,
                            academicYearExpanded = academicYearMenuExpanded,
                            semesterExpanded = semesterMenuExpanded,
                            enabled = !state.busy && !state.loadingAcademicYears,
                            onAcademicYearExpandedChange = { academicYearMenuExpanded = it },
                            onSemesterExpandedChange = { semesterMenuExpanded = it },
                            onAcademicYearSelected = {
                                academicYearMenuExpanded = false
                                viewModel.selectAcademicYear(it)
                            },
                            onSemesterSelected = {
                                semesterMenuExpanded = false
                                viewModel.selectSemester(it)
                            },
                            onFetch = viewModel::previewSelectedTerm,
                            onRefresh = { viewModel.loadAcademicYears(force = true) }
                        )
                    }
                }
            }
            item {
                ImportSection(
                    title = "JSON 导入",
                    expanded = jsonSectionExpanded,
                    onExpandedChange = { jsonSectionExpanded = it }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "粘贴其他设备分享的课表 JSON 文本，解析后暂存到下方「待导入课表」列表，统一执行导入。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = jsonText,
                            onValueChange = { jsonText = it },
                            label = { Text("课表 JSON") },
                            minLines = 4,
                            maxLines = 8,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                // 异步解析+暂存；结果通过状态栏展示。
                                viewModel.stageJsonText(jsonText)
                                jsonText = ""
                            },
                            enabled = !state.busy && jsonText.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("暂存到待导入") }
                    }
                }
            }
            item {
                ImportSection(
                    title = "新建空课表",
                    expanded = blankSectionExpanded,
                    onExpandedChange = { blankSectionExpanded = it }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "创建一份空白手动课表（暂存到「待导入课表」后统一导入；可在周视图点击空白时段添加课程）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = blankLabel,
                            onValueChange = { blankLabel = it },
                            label = { Text("课表名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                viewModel.stageBlankTable(blankLabel)
                                blankLabel = ""
                            },
                            enabled = !state.busy && blankLabel.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("暂存到待导入") }
                    }
                }
            }
            if (state.terms.any { it.selected }) {
                item {
                    TermDropdownPicker(
                        terms = state.terms.filter { it.selected },
                        expanded = termMenuExpanded,
                        enabled = !state.busy && !state.loadingAcademicYears,
                        onExpandedChange = { termMenuExpanded = it },
                        onSelect = viewModel::focusTerm,
                        onRemove = viewModel::removeCustomTerm,
                        title = "待导入课表"
                    )
                }
                item {
                    val selectedTerms = state.terms.filter { it.selected }
                    // 批量导入时底部展示所有已选课表的课程总和（按“门”统计），
                    // 空课表无课程，JSON/教务按已勾选的课程统计。
                    val totalPendingCourses = selectedTerms.sumOf { term ->
                        if (term.isBlank) 0 else term.courses
                            .filter { it.selected }
                            .distinctBy { it.masterKey }
                            .size
                    }
                    Text(
                        when {
                            state.mode == ImportMode.OVERWRITE ->
                                "覆盖模式：只保留一份待导入课表，导入时将替换当前课表" +
                                    (state.overwriteTargetLabel?.let { "“$it”" } ?: "") +
                                    "；已保留 ${selectedTerms.size} 份 · 共 $totalPendingCourses 门课程"
                            else -> "已选 ${selectedTerms.size} 个学期课表 · 共 $totalPendingCourses 门课程；可继续查询并添加其他学期"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                // 导入多个课表时只展示聚焦选中的一份（默认最近添加的），
                // 通过上方列表切换查看，避免一次性堆叠全部编辑卡片。
                val focusedTerm = state.terms.firstOrNull { it.key == state.focusedTermKey }
                    ?.takeIf { it.selected }
                    ?: state.terms.filter { it.selected }.lastOrNull()
                focusedTerm?.let { term ->
                    item(key = "selected-${term.key}") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SelectedTermEditor(
                                term = term,
                                enabled = !state.busy && state.previewingTermKey == null,
                                onLabelChange = { viewModel.setTermLabel(term.key, it) },
                                onStartDateChange = { viewModel.setTermStartDate(term.key, it) },
                                onDelete = { viewModel.removeCustomTerm(term.key) }
                            )
                            if (term.isBlank) {
                                Text(
                                    "空课表：无需选择课程，导入即创建/清空课表。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                CourseSelectionSection(
                                    term = term,
                                    enabled = !state.busy && state.previewingTermKey == null,
                                    onRetry = { viewModel.previewTerm(term.key) },
                                    onToggleAll = { viewModel.toggleAllCourses(term.key) },
                                    onToggleCourse = { viewModel.toggleCourse(term.key, it) }
                                )
                            }
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = { viewModel.import(onDone) },
                    enabled = !state.busy && state.terms.any { it.selected },
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Text(if (state.busy) "正在导入…" else "导入已选课表")
                }
            }
            item {
                state.status?.let {
                    Text(
                        it,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    if (showCustomTermDialog) {
        CustomTermDialog(
            onDismiss = { showCustomTermDialog = false },
            onConfirm = { xnm, xqm, label, startDate ->
                if (viewModel.addCustomTerm(xnm, xqm, label, startDate)) showCustomTermDialog = false
            }
        )
    }

    // 覆盖模式：执行前的“确认替换当前课表”弹窗（添加模式不再出现任何覆盖相关弹窗）。
    state.conflictTimetable?.let { existing ->
        val pendingLabel = state.pendingTerms.firstOrNull()?.label
        AlertDialog(
            onDismissRequest = { if (!state.busy) viewModel.dismissConflict() },
            title = { Text("确认替换当前课表") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("将用“${pendingLabel ?: existing.label}”替换当前课表“${existing.label}”，学期信息一并更新。")
                    if (state.pendingTerms.any { it.isJson }) {
                        Text(
                            "包含 JSON 导入项：按分享文本整表重建（不区分手动/同步课程）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (state.pendingTerms.any { it.isBlank }) {
                        Text(
                            "空课表：将当前课表清空并改为新名称。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "教务导入：只替换教务同步课程，手动添加的课程会保留。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                // 取消与确认同列排布，避免与底部按钮重叠。
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = { viewModel.confirmOverwrite(onDone) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("确认替换") }
                    TextButton(
                        onClick = viewModel::dismissConflict,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("取消") }
                }
            }
        )
    }
}

@Composable
private fun AcademicTermSelector(
    state: TermImportUiState,
    academicYearExpanded: Boolean,
    semesterExpanded: Boolean,
    enabled: Boolean,
    onAcademicYearExpandedChange: (Boolean) -> Unit,
    onSemesterExpandedChange: (Boolean) -> Unit,
    onAcademicYearSelected: (Int) -> Unit,
    onSemesterSelected: (String) -> Unit,
    onFetch: () -> Unit,
    onRefresh: () -> Unit
) {
    val year = state.academicYears.firstOrNull { it.xnm == state.selectedAcademicYear }
    val semester = year?.semesters?.firstOrNull { it.xqm == state.selectedSemester }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("添加学期课表", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "选择学年和学期，获取后加入待导入列表",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRefresh, enabled = enabled) {
                    Icon(Icons.Default.Refresh, contentDescription = "重新读取学年列表")
                }
            }
            SelectorField(
                label = "学年",
                value = year?.label ?: if (state.loadingAcademicYears) "正在读取…" else "暂未读取到学年",
                expanded = academicYearExpanded,
                enabled = enabled && state.academicYears.isNotEmpty(),
                onClick = { onAcademicYearExpandedChange(!academicYearExpanded) },
                onDismiss = { onAcademicYearExpandedChange(false) }
            ) {
                state.academicYears.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = { onAcademicYearSelected(option.xnm) }
                    )
                }
            }
            SelectorField(
                label = "学期",
                value = semester?.label ?: "请选择学期",
                expanded = semesterExpanded,
                enabled = enabled && year != null,
                onClick = { onSemesterExpandedChange(!semesterExpanded) },
                onDismiss = { onSemesterExpandedChange(false) }
            ) {
                year?.semesters?.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = { onSemesterSelected(option.xqm) }
                    )
                }
            }
            Button(
                onClick = onFetch,
                enabled = enabled && year != null && semester != null && state.previewingTermKey == null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.previewingTermKey != null) "正在获取课程…" else "获取并加入课程")
            }
            if (state.academicYears.isEmpty() && !state.loadingAcademicYears) {
                Text(
                    "暂时无法读取学年列表，请点击右上角刷新重试；也可使用 + 添加自定义学期。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun SelectorField(
    label: String,
    value: String,
    expanded: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    menuContent: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled, onClick = onClick),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        value,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起${label}列表" else "展开${label}列表"
                    )
                }
            }
            if (expanded) {
                Dialog(onDismissRequest = onDismiss) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .heightIn(max = 420.dp),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 6.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(vertical = 8.dp)
                                .heightIn(max = 404.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            menuContent()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TermDropdownPicker(
    terms: List<ImportTermOption>,
    expanded: Boolean,
    enabled: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    title: String = "选择课表"
) {
    val selected = terms.filter { it.selected }
    val summary = when {
        selected.isEmpty() -> "暂未获取课表"
        selected.size == 1 -> selected.first().label
        else -> "已获取 ${selected.size} 个学期课表"
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("管理", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Box {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { onExpandedChange(!expanded) },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        summary,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (selected.isEmpty()) FontWeight.Normal else FontWeight.Medium,
                        color = if (selected.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起课表列表" else "展开课表列表"
                    )
                }
            }
            if (expanded) {
                Dialog(onDismissRequest = { onExpandedChange(false) }) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .heightIn(max = 420.dp),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 6.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(vertical = 8.dp)
                                .heightIn(max = 404.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            if (terms.isEmpty()) {
                                Text(
                                    "还没有加入课表，请先在上方查询并获取实际课程",
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                terms.forEach { term ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(term.title, style = MaterialTheme.typography.bodyLarge)
                                                Text(
                                                    term.subtitle,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        trailingIcon = {
                                            // 只有垃圾桶图标触发删除；点击行其余位置只切换查看焦点。
                                            IconButton(onClick = { onRemove(term.key) }) {
                                                Icon(Icons.Default.DeleteOutline, contentDescription = "删除暂存课表")
                                            }
                                        },
                                        onClick = { onSelect(term.key); onExpandedChange(false) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Text(
            if (selected.isEmpty()) "查询并获取实际课程后，课表会出现在这里" else "点击展开可切换查看或移除已暂存的课表",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SelectedTermEditor(
    term: ImportTermOption,
    enabled: Boolean,
    onLabelChange: (String) -> Unit,
    onStartDateChange: (String) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(term.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(term.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDelete, enabled = enabled) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "删除暂存课表")
                }
            }
            OutlinedTextField(
                value = term.label,
                onValueChange = onLabelChange,
                label = { Text("课表名称") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = term.startDate,
                onValueChange = onStartDateChange,
                label = { Text("第一周周一（yyyy-MM-dd）") },
                supportingText = { Text("用于周次计算、提醒和日历导出") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun CourseSelectionSection(
    term: ImportTermOption,
    enabled: Boolean,
    onRetry: () -> Unit,
    onToggleAll: () -> Unit,
    onToggleCourse: (String) -> Unit
) {
    var expanded by remember(term.key) { mutableStateOf(false) }
    // 教务返回同一门课的多个时段时会有多条选项；按主课程键统计“门”数，
    // 避免把“时段条数（List 数量）”误当成课程数量。
    val totalMasters = term.courses.distinctBy { it.masterKey }.size
    val selectedMasters = term.courses.filter { it.selected }.distinctBy { it.masterKey }
    // 星期无法解析的课程（day 不在 1..7）默认不勾选，这里按“门”提示数量。
    val unresolvedMasters = term.courses
        .filter { it.day !in 1..7 }
        .distinctBy { it.masterKey }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("实际课程列表", fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            term.previewError != null -> "获取失败"
                            !term.previewed -> "正在获取教务系统数据…"
                            else -> "已选 ${selectedMasters.size} / $totalMasters 门"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (term.previewed && term.courses.isNotEmpty()) {
                    TextButton(onClick = onToggleAll, enabled = enabled) {
                        Text(if (term.courses.all { it.selected }) "取消全选" else "全选")
                    }
                } else if (term.previewError != null) {
                    TextButton(onClick = onRetry, enabled = enabled) { Text("重试") }
                }
            }
            if (term.previewed && unresolvedMasters.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        "存在 ${unresolvedMasters.size} 门无法解析的课程（已默认不勾选，可手动勾选）",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            when {
                !term.previewed && term.previewError == null -> {
                    Text("正在根据教务系统实际返回结果生成选项，请稍候…", style = MaterialTheme.typography.bodySmall)
                }
                term.previewError != null -> {
                    Text(
                        term.previewError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                term.courses.isEmpty() -> {
                    Text("这个学期没有获取到课程。", style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled) { expanded = true },
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.medium,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                when (val count = selectedMasters.size) {
                                    0 -> "请选择课程"
                                    1 -> selectedMasters.first().name
                                    else -> "已选择 $count 门课程"
                                },
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (selectedMasters.isNotEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Icon(
                                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (expanded) "收起课程列表" else "展开课程列表"
                            )
                        }
                    }
                    if (expanded) {
                        CourseSelectionDialog(
                            courses = term.courses,
                            onDismiss = { expanded = false },
                            onToggleCourse = onToggleCourse
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseSelectionDialog(
    courses: List<ImportCourseOption>,
    onDismiss: () -> Unit,
    onToggleCourse: (String) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(min = 220.dp, max = 620.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)) {
                Text(
                    "选择课程",
                    modifier = Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "勾选需要导入的课程",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp, max = 420.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(courses, key = { it.remoteKey }) { course ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleCourse(course.remoteKey) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = course.selected,
                                onCheckedChange = { onToggleCourse(course.remoteKey) }
                            )
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text(course.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    course.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End).padding(end = 12.dp)
                ) { Text("完成") }
            }
        }
    }
}

@Composable
private fun ImportTermCard(
    term: ImportTermOption,
    enabled: Boolean,
    onToggle: () -> Unit,
    onLabelChange: (String) -> Unit,
    onStartDateChange: (String) -> Unit,
    onRemove: (() -> Unit)?
) {
    // 保留旧组件签名，便于后续测试或预览引用；主界面已改用下拉选择器。
    SelectedTermEditor(term, enabled, onLabelChange, onStartDateChange, onDelete = onRemove ?: {})
}

@Composable
private fun CustomTermDialog(
    onDismiss: () -> Unit,
    onConfirm: (xnm: String, xqm: String, label: String, startDate: String) -> Unit
) {
    var xnm by remember { mutableStateOf(LocalDateHolder.defaultYear()) }
    var xqm by remember { mutableStateOf("3") }
    var label by remember { mutableStateOf("") }
    var startDate by remember { mutableStateOf(LocalDateHolder.defaultMonday()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加自定义学期") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("填写学校教务系统对应的学年起始年和学期码。")
                OutlinedTextField(xnm, { xnm = it.filter(Char::isDigit) }, label = { Text("学年起始年（xnm）") }, singleLine = true)
                OutlinedTextField(xqm, { xqm = it.filter(Char::isDigit) }, label = { Text("学期码（xqm）") }, singleLine = true)
                OutlinedTextField(label, { label = it }, label = { Text("课表名称") }, singleLine = true)
                OutlinedTextField(startDate, { startDate = it }, label = { Text("第一周周一") }, supportingText = { Text("格式：yyyy-MM-dd") }, singleLine = true)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = { Button(onClick = { onConfirm(xnm, xqm, label, startDate) }) { Text("添加") } }
    )
}

private object LocalDateHolder {
    fun defaultYear(): String = LocalDate.now().let { if (it.monthValue >= 8) it.year else it.year - 1 }.toString()
    fun defaultMonday(): String = LocalDate.now().with(TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY)).toString()
}

/** 可展开的导入区域：标题 + 展开/收起箭头；用于「教务系统导入」「JSON 导入」「新建空课表」。 */
@Composable
private fun ImportSection(
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起$title" else "展开$title"
                )
            }
            if (expanded) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    content()
                }
            }
        }
    }
}

