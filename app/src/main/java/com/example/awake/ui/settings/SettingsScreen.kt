package com.example.awake.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.example.awake.data.export.IcsExporter
import com.example.awake.data.local.PeriodConfigEntity
import com.example.awake.data.notification.NotificationChannels
import com.example.awake.data.remote.ScutAuthRepository
import com.example.awake.data.remote.ScutAccessMode
import com.example.awake.data.remote.ScutJwClient
import com.example.awake.data.remote.SessionAvailability
import com.example.awake.data.remote.SessionAvailabilityState
import com.example.awake.data.repository.LocalTimetableRepository
import com.example.awake.data.repository.ReminderCoordinator
import com.example.awake.data.repository.ReminderSettingsStore
import com.example.awake.data.repository.TimetableSelectionStore
import com.example.awake.data.repository.TimetableDisplaySettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    local: LocalTimetableRepository,
    auth: ScutAuthRepository,
    reminderCoordinator: ReminderCoordinator,
    selection: TimetableSelectionStore,
    displaySettings: TimetableDisplaySettingsStore,
    remote: ScutJwClient,
    onBack: () -> Unit,
    onLogin: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val store = remember(context) { ReminderSettingsStore(context) }
    val initial = remember(store) { store.read() }
    var reminderEnabled by remember { mutableStateOf(initial.enabled) }
    var minutesBefore by remember { mutableStateOf(initial.minutesBefore) }
    var periodConfigs by remember { mutableStateOf<List<PeriodConfigEntity>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var sessionStates by remember { mutableStateOf<Map<ScutAccessMode, SessionAvailability>>(emptyMap()) }
    var checkingSessions by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        periodConfigs = withContext(Dispatchers.IO) { local.getPeriodConfigs() }
    }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var pendingFileName by remember { mutableStateOf("awake-timetable.ics") }
    val scope = rememberCoroutineScope()
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/calendar")
    ) { uri ->
        val content = pendingExport
        if (uri == null || content == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(content.toByteArray(Charsets.UTF_8))
                    } ?: error("无法打开目标文件")
                }
            }.onSuccess {
                status = "已保存 $pendingFileName"
            }.onFailure { error ->
                status = "保存失败：${error.message ?: "未知错误"}"
            }
            pendingExport = null
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            reminderEnabled = true
            store.setEnabled(true)
            scope.launch { reminderCoordinator.rescheduleSelected() }
            status = "通知权限已开启，课前提醒设置已保存"
        } else {
            reminderEnabled = false
            store.setEnabled(false)
            reminderCoordinator.cancelAll()
            status = "未获得通知权限，课前提醒仍保持关闭"
        }
    }

    fun enableReminder() {
        NotificationChannels.ensureReminderChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            if (activity is ComponentActivity) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                status = "请在系统设置中允许通知权限后再开启提醒"
            }
        } else {
            reminderEnabled = true
            store.setEnabled(true)
            scope.launch { reminderCoordinator.rescheduleSelected() }
            status = "课前提醒已开启"
        }
    }

    suspend fun loadCurrentIcs(): Pair<String, String>? {
        val timetable = selection.read()?.let { local.getTimetableOrNull(it) } ?: local.getFirstTimetable()
        if (timetable == null) {
            status = "当前没有可导出的本地课表"
            return null
        }
        val exportTimetable = if (timetable.startDate.isNullOrBlank() && timetable.label.contains("演示")) {
            timetable.copy(
                startDate = LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)).toString()
            ).also { local.updateTimetable(it) }
        } else timetable
        if (exportTimetable.startDate.isNullOrBlank()) {
            status = "当前课表缺少学期第一周日期，暂不能导出日历"
            return null
        }
        val ics = IcsExporter.export(
            timetable = exportTimetable,
            courses = local.getAllCourses(timetable.id),
            periodConfigs = local.getPeriodConfigs()
        )
        val safeLabel = exportTimetable.label.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").trim('_').ifBlank { "awake-timetable" }
        return ics to "$safeLabel.ics"
    }

    fun shareCurrentIcs() {
        scope.launch {
            val result = withContext(Dispatchers.IO) { loadCurrentIcs() } ?: return@launch
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/calendar"
                putExtra(Intent.EXTRA_SUBJECT, result.second.removeSuffix(".ics"))
                putExtra(Intent.EXTRA_TEXT, result.first)
            }
            context.startActivity(Intent.createChooser(send, "分享课表日历"))
            status = "已打开系统分享面板"
        }
    }

    fun saveCurrentIcs() {
        scope.launch {
            val result = withContext(Dispatchers.IO) { loadCurrentIcs() } ?: return@launch
            pendingExport = result.first
            pendingFileName = result.second
            saveLauncher.launch(result.second)
        }
    }

    var section by remember { mutableStateOf(SettingsSection.OVERVIEW) }
    val showOtherWeeks by displaySettings.showOtherWeeks.collectAsStateWithLifecycle()

    fun checkSessions() {
        if (checkingSessions) return
        scope.launch {
            checkingSessions = true
            sessionStates = ScutAccessMode.values().associateWith {
                SessionAvailability(it, SessionAvailabilityState.NOT_CONFIGURED)
            }
            val results = withContext(Dispatchers.IO) {
                ScutAccessMode.values().associateWith { mode -> remote.probeSession(mode) }
            }
            sessionStates = results
            checkingSessions = false
        }
    }

    LaunchedEffect(section) {
        if (section == SettingsSection.ACCOUNT) checkSessions()
    }

    Scaffold(topBar = {
        CenterAlignedTopAppBar(
            title = { Text(section.title) },
            navigationIcon = {
                IconButton(onClick = {
                    if (section == SettingsSection.OVERVIEW) onBack() else section = SettingsSection.OVERVIEW
                }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
            }
        )
    }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (section) {
                SettingsSection.OVERVIEW -> {
                    Text("当前版本坚持本地优先：不保存密码、CAS ticket 或 Cookie，不上传课表。")
                    Text("课表与提醒", style = MaterialTheme.typography.titleMedium)
                    SettingsOption(
                        title = "课前提醒",
                        subtitle = if (reminderEnabled) "已开启 · 提前 ${minutesBefore} 分钟提醒" else "已关闭 · 仅使用本设备本地课表",
                        onClick = { section = SettingsSection.REMINDERS }
                    )
                    SettingsOption(
                        title = "节次时间",
                        subtitle = if (periodConfigs.isEmpty()) "正在读取节次配置…" else "${periodConfigs.size} 个时间段 · 可自行调整",
                        onClick = { section = SettingsSection.PERIODS }
                    )
                    SettingsOption(
                        title = "课表显示",
                        subtitle = if (showOtherWeeks) "非本周课程半透明显示 · 已开启" else "只显示本周课程 · 已关闭",
                        onClick = { section = SettingsSection.DISPLAY }
                    )
                    SettingsOption(
                        title = "日历导出",
                        subtitle = "分享或保存当前选中的本地课表",
                        onClick = { section = SettingsSection.CALENDAR }
                    )
                    Text("教务账号和数据", style = MaterialTheme.typography.titleMedium)
                    SettingsOption(
                        title = "账号与本地数据",
                        subtitle = "重新登录、退出登录或清除本地数据",
                        onClick = { section = SettingsSection.ACCOUNT }
                    )
                    Text(
                        "课表导出、通知和小组件只消费当前选中的本地课表；网络失败时不会覆盖旧数据。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                SettingsSection.REMINDERS -> {
                    Text("课前提醒", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "通知只使用当前设备上的本地课表。开启后，课表更新或提醒时间变化会自动重新安排通知。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(if (reminderEnabled) "已开启" else "已关闭")
                        Switch(
                            checked = reminderEnabled,
                            onCheckedChange = { checked ->
                                if (checked) enableReminder()
                                else {
                                    reminderEnabled = false
                                    store.setEnabled(false)
                                    reminderCoordinator.cancelAll()
                                    status = "课前提醒已关闭"
                                }
                            }
                        )
                    }
                    Text("提醒时间", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReminderSettingsStore.ALLOWED_MINUTES.sorted().forEach { option ->
                            OutlinedButton(
                                onClick = {
                                    minutesBefore = option
                                    store.setMinutesBefore(option)
                                    if (reminderEnabled) scope.launch { reminderCoordinator.rescheduleSelected() }
                                    status = "已设置为提前 ${option} 分钟提醒"
                                },
                                enabled = reminderEnabled || option == minutesBefore
                            ) {
                                Text("${option} 分钟")
                            }
                        }
                    }
                }

                SettingsSection.PERIODS -> {
                    Text("节次时间", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "时间会显示在课表左侧节次栏，也用于提醒和日历导出。请使用 HH:mm 格式。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    periodConfigs.forEach { config ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(config.period.toString().padStart(2, '0'), modifier = Modifier.padding(end = 2.dp))
                            OutlinedTextField(
                                value = config.startTime,
                                onValueChange = { value ->
                                    periodConfigs = periodConfigs.map {
                                        if (it.period == config.period) it.copy(startTime = value.take(5)) else it
                                    }
                                },
                                label = { Text("开始") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = config.endTime,
                                onValueChange = { value ->
                                    periodConfigs = periodConfigs.map {
                                        if (it.period == config.period) it.copy(endTime = value.take(5)) else it
                                    }
                                },
                                label = { Text("结束") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) { local.savePeriodConfigs(periodConfigs) }
                                }.onSuccess {
                                    status = "节次时间已保存"
                                }.onFailure { error ->
                                    status = error.message ?: "节次时间保存失败"
                                }
                            }
                        },
                        enabled = periodConfigs.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("保存节次时间") }
                }

                SettingsSection.DISPLAY -> {
                    Text("课表显示", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "开启后，当前课表中不属于所选周次的课程会保留在课表中，并以半透明显示。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(if (showOtherWeeks) "非本周课程半透明显示" else "仅显示本周课程")
                        Switch(
                            checked = showOtherWeeks,
                            onCheckedChange = { enabled ->
                                displaySettings.setShowOtherWeeks(enabled)
                                status = if (enabled) "已开启非本周课程半透明显示" else "已关闭非本周课程显示"
                            }
                        )
                    }
                }

                SettingsSection.CALENDAR -> {
                    Text("日历导出", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "只导出当前选中的本地课表，不会上传到服务器。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = ::shareCurrentIcs, modifier = Modifier.weight(1f)) { Text("系统分享") }
                        Button(onClick = ::saveCurrentIcs, modifier = Modifier.weight(1f)) { Text("保存 .ics") }
                    }
                }

                SettingsSection.ACCOUNT -> {
                    Text(
                        "登录信息只用于访问学校官方系统；本地课表可以在退出登录后继续查看。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SessionStatusCard(
                        states = sessionStates,
                        checking = checkingSessions,
                        onRefresh = ::checkSessions
                    )
                    Text("登录操作", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text("重新登录官方 CAS") }
                    OutlinedButton(
                        onClick = { auth.logout(); sessionStates = emptyMap(); status = "已退出登录，本地课表仍保留" },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("退出登录（保留本地课表）")
                    }
                    Text("本地数据", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(
                        onClick = { showClearDataDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null)
                        Spacer(modifier = Modifier.padding(horizontal = 3.dp))
                        Text("清除全部本地数据")
                    }
                }
            }
            status?.let {
                Text(it, color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    if (showClearDataDialog) {
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("清除全部本地数据？") },
            text = { Text("这会删除所有本地课表、提醒和登录会话，且无法恢复。") },
            dismissButton = { TextButton(onClick = { showClearDataDialog = false }) { Text("取消") } },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDataDialog = false
                        scope.launch {
                            reminderCoordinator.cancelAll()
                            withContext(Dispatchers.IO) { local.deleteAll() }
                            selection.clear()
                            auth.logout()
                            sessionStates = emptyMap()
                            status = "已清除全部本地数据"
                        }
                    },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("确认清除") }
            }
        )
    }
}

@Composable
private fun SessionStatusCard(
    states: Map<ScutAccessMode, SessionAvailability>,
    checking: Boolean,
    onRefresh: () -> Unit
) {
    val canImport = states.values.any { it.state == SessionAvailabilityState.AVAILABLE }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("会话状态", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (checking) "正在检查直连和 VPN 会话…"
                        else if (canImport) "至少一种会话可用，可以导入新课表"
                        else "暂未检测到可用会话，请重新登录",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (canImport) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRefresh, enabled = !checking) {
                    Icon(Icons.Default.Refresh, contentDescription = "重新检查会话")
                }
            }
            SessionStatusRow(ScutAccessMode.DIRECT, states[ScutAccessMode.DIRECT], checking)
            SessionStatusRow(ScutAccessMode.WEB_VPN, states[ScutAccessMode.WEB_VPN], checking)
            Text(
                "导入时按直连优先、VPN 备用尝试；任意一种会话成功即可完成导入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SessionStatusRow(
    mode: ScutAccessMode,
    availability: SessionAvailability?,
    checking: Boolean
) {
    val icon = if (mode == ScutAccessMode.DIRECT) Icons.Default.Wifi else Icons.Default.Cloud
    val state = availability?.state
    val (label, color, detail) = when {
        checking -> Triple("检查中…", MaterialTheme.colorScheme.onSurfaceVariant, null)
        state == SessionAvailabilityState.AVAILABLE -> Triple("可用", MaterialTheme.colorScheme.primary, null)
        state == SessionAvailabilityState.EXPIRED -> Triple("已失效", MaterialTheme.colorScheme.error, availability?.detail)
        state == SessionAvailabilityState.NETWORK_ERROR -> Triple("网络不可达", MaterialTheme.colorScheme.error, availability?.detail)
        state == SessionAvailabilityState.SERVER_ERROR -> Triple("暂不可用", MaterialTheme.colorScheme.error, availability?.detail)
        else -> Triple("未登录", MaterialTheme.colorScheme.onSurfaceVariant, null)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color)
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(mode.title, style = MaterialTheme.typography.bodyLarge)
                Text("  ·  $label", color = color, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                if (detail.isNullOrBlank()) mode.description else detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state == SessionAvailabilityState.AVAILABLE) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = color)
        }
    }
}

private enum class SettingsSection(val title: String) {
    OVERVIEW("设置与隐私"),
    REMINDERS("课前提醒"),
    PERIODS("节次时间"),
    DISPLAY("课表显示"),
    CALENDAR("日历导出"),
    ACCOUNT("账号与本地数据")
}

@Composable
private fun SettingsOption(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
