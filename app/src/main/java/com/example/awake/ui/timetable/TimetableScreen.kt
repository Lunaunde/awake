package com.example.awake.ui.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.awake.data.local.TimetableEntity
import com.example.awake.ui.components.GridLegend
import com.example.awake.ui.components.WeeklyTimetableGrid
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(
    viewModel: TimetableViewModel,
    onLogin: () -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
    onCourse: (Long) -> Unit,
    onAddCourse: (timetableId: Long, dayOfWeek: Int, startPeriod: Int) -> Unit
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val tables by viewModel.timetables.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedTimetableId.collectAsStateWithLifecycle()
    val selectedTimetable by viewModel.selectedTimetable.collectAsStateWithLifecycle()
    val week by viewModel.currentWeek.collectAsStateWithLifecycle()
    val courses by viewModel.courses.collectAsStateWithLifecycle()
    val coursesThroughEnd by viewModel.coursesThroughEnd.collectAsStateWithLifecycle()
    val adjacentWeekPages by viewModel.adjacentWeekPages.collectAsStateWithLifecycle()
    val showOtherWeeks by viewModel.showOtherWeeks.collectAsStateWithLifecycle()
    val periodConfigs by viewModel.periodConfigs.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val isLoggedIn = profile?.displayName?.isNotBlank() == true && profile?.displayName != "未登录"
    var showControlSheet by remember { mutableStateOf(false) }

    // 进入主界面且已有登录档案时自动检查会话并同步一次，用户仍可通过顶部按钮手动刷新。
    LaunchedEffect(selectedId, isLoggedIn) {
        if (selectedId != null && isLoggedIn) viewModel.refresh()
    }

    LaunchedEffect(message) {
        if (message != null && syncState == TimetableSyncState.SUCCESS) {
            kotlinx.coroutines.delay(2600)
            viewModel.clearMessage()
        }
    }

    Scaffold(containerColor = Color(0xFFE9ECF8)) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding())
                .statusBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            CompactTimetableHeader(
                week = week,
                date = formatWeekDate(selectedTimetable, week),
                onImport = onImport,
                onRefresh = viewModel::refresh,
                refreshEnabled = syncState != TimetableSyncState.REFRESHING && selectedTimetable != null,
                onMore = { showControlSheet = true },
                onSettings = onSettings
            )

            if (tables.isEmpty()) {
                EmptyTimetableState(
                    loggedIn = isLoggedIn,
                    onLogin = onLogin,
                    onImport = onImport,
                    onDemo = viewModel::seedDemo,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                SyncBanner(syncState, message, onLogin, viewModel::refresh)
                val pageSet = adjacentWeekPages?.takeIf { it.current.week == week }
                val currentPage = pageSet?.current
                val previousPage = pageSet?.previous
                val nextPage = pageSet?.next
                WeeklyTimetableGrid(
                    // 当前页和相邻页同时渲染，拖动时下一页会跟手露出，而不是松手后突然切换。
                    courses = currentPage?.let { if (showOtherWeeks) it.coursesThroughEnd else it.currentCourses }
                        ?: if (showOtherWeeks) coursesThroughEnd else courses,
                    currentWeek = week,
                    totalWeeks = selectedTimetable?.totalWeeks ?: 30,
                    currentWeekCourseIds = currentPage?.currentCourseIds
                        ?: courses.mapTo(mutableSetOf()) { it.id },
                    previousCourses = previousPage?.let {
                        if (showOtherWeeks) it.coursesThroughEnd else it.currentCourses
                    }.orEmpty(),
                    previousWeek = previousPage?.week ?: (week - 1),
                    previousWeekCourseIds = previousPage?.currentCourseIds.orEmpty(),
                    nextCourses = nextPage?.let {
                        if (showOtherWeeks) it.coursesThroughEnd else it.currentCourses
                    }.orEmpty(),
                    nextWeek = nextPage?.week ?: (week + 1),
                    nextWeekCourseIds = nextPage?.currentCourseIds.orEmpty(),
                    periodConfigs = periodConfigs,
                    onCourseClick = onCourse,
                    onEmptyClick = { day, period ->
                        selectedId?.let { onAddCourse(it, day, period) }
                    },
                    onWeekSwipe = { delta -> viewModel.selectWeek(week + delta) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                Text(
                    "点击课程查看详情 · 点击空白时段添加本地课程",
                    modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    if (showControlSheet) {
        ModalBottomSheet(
            onDismissRequest = { showControlSheet = false },
            modifier = Modifier.navigationBarsPadding()
        ) {
            TimetableControlSheet(
                week = week,
                tables = tables,
                selectedId = selectedId,
                selectedTimetable = selectedTimetable,
                courseCount = courses.size,
                onWeekChange = viewModel::selectWeek,
                onTimetableChange = viewModel::selectTimetable,
                onRename = viewModel::renameTimetable,
                onDelete = viewModel::deleteTimetable,
                onClose = { showControlSheet = false }
            )
        }
    }
}

@Composable
private fun CompactTimetableHeader(
    week: Int,
    date: String?,
    onImport: () -> Unit,
    onRefresh: () -> Unit,
    refreshEnabled: Boolean,
    onMore: () -> Unit,
    onSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "第 $week 周",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                text = date ?: "本地优先 · 离线可看",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        IconButton(onClick = onImport) {
            Icon(Icons.Default.Add, contentDescription = "导入课表")
        }
        IconButton(onClick = onRefresh, enabled = refreshEnabled) {
            Icon(Icons.Default.Refresh, contentDescription = "刷新课表")
        }
        IconButton(onClick = onMore) {
            Icon(Icons.Default.MoreVert, contentDescription = "打开课表选项")
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Default.Settings, contentDescription = "设置")
        }
    }
}
@Composable
private fun TimetableControlSheet(
    week: Int,
    tables: List<TimetableEntity>,
    selectedId: Long?,
    selectedTimetable: TimetableEntity?,
    courseCount: Int,
    onWeekChange: (Int) -> Unit,
    onTimetableChange: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onClose: () -> Unit
) {
    var showWeekPicker by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<TimetableEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<TimetableEntity?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("课表选项", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFE6F0F3)),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("第 $week 周", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(selectedTimetable?.label ?: "未选择学期课表", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("本周 $courseCount 门课程", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }

        Text("切换周次", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedButton(
            onClick = { showWeekPicker = true },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text("当前为第${week}周 · 点击选择其他周次", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }

        Text("选择学期课表", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        tables.forEach { timetable ->
            val isSelected = timetable.id == selectedId
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isSelected) {
                    Button(
                        onClick = {},
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)
                    ) {
                        Text("${timetable.label} · 当前", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                } else {
                    OutlinedButton(
                        onClick = { onTimetableChange(timetable.id) },
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)
                    ) {
                        Text(timetable.label, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = { renameTarget = timetable; renameText = timetable.label }) {
                    Icon(Icons.Default.Edit, contentDescription = "重命名课表")
                }
                IconButton(onClick = { deleteTarget = timetable }, enabled = tables.size > 1) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "删除课表")
                }
            }
        }
        GridLegend()
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("完成") }
        Spacer(modifier = Modifier.height(8.dp))
    }

    if (showWeekPicker) {
        AlertDialog(
            onDismissRequest = { showWeekPicker = false },
            title = { Text("切换周次") },
            text = {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items((1..30).toList()) { item ->
                        val selected = item == week
                        if (selected) {
                            Button(
                                onClick = { showWeekPicker = false },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp)
                            ) { Text("第${item}周", maxLines = 1, softWrap = false) }
                        } else {
                            OutlinedButton(
                                onClick = { onWeekChange(item); showWeekPicker = false },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp)
                            ) { Text("第${item}周", maxLines = 1, softWrap = false) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showWeekPicker = false }) { Text("完成") } }
        )
    }
    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名课表") },
            text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, label = { Text("课表名称") }, singleLine = true) },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
            confirmButton = { TextButton(onClick = { onRename(target.id, renameText); renameTarget = null }) { Text("保存") } }
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除课表？") },
            text = { Text("将删除“${target.label}”及其中的课程数据，此操作不可撤销。") },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
            confirmButton = { TextButton(onClick = { onDelete(target.id); deleteTarget = null }) { Text("删除") } }
        )
    }
}

@Composable
private fun EmptyTimetableState(
    loggedIn: Boolean,
    onLogin: () -> Unit,
    onImport: () -> Unit,
    onDemo: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F1F3))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("还没有课表", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    if (loggedIn) "导入一个学期后，这里会变成你的周视图。"
                    else "登录学校官方页面导入课表，也可以先打开离线演示。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!loggedIn) {
                    Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Login, contentDescription = null)
                        Text("  官方登录")
                    }
                }
                Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("导入学期课表") }
                OutlinedButton(onClick = onDemo, modifier = Modifier.fillMaxWidth()) { Text("打开离线演示") }
            }
        }
    }
}

@Composable
private fun SyncBanner(
    state: TimetableSyncState,
    message: String?,
    onLogin: () -> Unit,
    onRetry: () -> Unit
) {
    when (state) {
        TimetableSyncState.IDLE -> Unit
        TimetableSyncState.REFRESHING -> StatusBanner("正在同步，旧课表仍可查看…", Color(0xFF557A8A), showProgress = true)
        TimetableSyncState.SUCCESS -> message?.let { StatusBanner(it, MaterialTheme.colorScheme.primary) }
        TimetableSyncState.OFFLINE -> StatusBanner(
            "当前显示本地课表，网络不可用；重试不会覆盖旧数据。",
            Color(0xFF9B6B2F),
            icon = Icons.Default.CloudOff,
            action = onRetry
        )
        TimetableSyncState.SESSION_EXPIRED -> StatusBanner(
            "登录会话已失效，请重新登录后再同步。",
            MaterialTheme.colorScheme.error,
            icon = Icons.Default.ErrorOutline,
            action = onLogin,
            actionLabel = "登录"
        )
        TimetableSyncState.ERROR -> StatusBanner(
            message ?: "同步失败，已保留旧课表。",
            MaterialTheme.colorScheme.error,
            icon = Icons.Default.ErrorOutline,
            action = onRetry
        )
    }
}

@Composable
private fun StatusBanner(
    text: String,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    showProgress: Boolean = false,
    action: (() -> Unit)? = null,
    actionLabel: String = "重试"
) {
    Surface(
        color = color.copy(alpha = 0.10f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (showProgress) CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
            icon?.let { Icon(it, contentDescription = null, tint = color) }
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = color)
            action?.let { TextButton(onClick = it) { Text(actionLabel, color = color) } }
        }
    }
}

private fun formatSyncTime(timestamp: Long?): String = timestamp?.let {
    SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(it))
} ?: "尚未同步"


private fun formatWeekDate(timetable: TimetableEntity?, week: Int): String? = runCatching {
    val startDate = timetable?.startDate ?: return null
    val parser = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply { isLenient = false }
    val date = parser.parse(startDate) ?: return null
    Calendar.getInstance().apply {
        time = date
        add(Calendar.DAY_OF_YEAR, (week - 1).coerceAtLeast(0) * 7)
    }.let { calendar ->
        SimpleDateFormat("yyyy/M/d", Locale.CHINA).format(calendar.time)
    }
}.getOrNull()
