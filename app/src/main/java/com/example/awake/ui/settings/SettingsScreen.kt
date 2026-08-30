package com.example.awake.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.core.content.ContextCompat
import com.example.awake.data.export.IcsExporter
import com.example.awake.data.local.PeriodConfigEntity
import com.example.awake.data.notification.NotificationChannels
import com.example.awake.data.remote.ScutAuthRepository
import com.example.awake.data.repository.LocalTimetableRepository
import com.example.awake.data.repository.ReminderCoordinator
import com.example.awake.data.repository.ReminderSettingsStore
import com.example.awake.data.repository.TimetableSelectionStore
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

    Scaffold(topBar = {
        CenterAlignedTopAppBar(title = { Text("设置与隐私") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
        })
    }) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("当前版本坚持本地优先：不保存密码、CAS ticket 或 Cookie，不上传课表。")

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("课前提醒")
                        Text("通知只使用当前设备上的本地课表")
                    }
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
                Text("提醒时间")
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

            Text("节次时间", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            Text("时间会显示在课表左侧节次栏，也用于提醒和日历导出。请使用 HH:mm 格式。")
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

            Text("日历导出")
            Text("只导出当前选中的本地课表，不会上传到服务器。")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = ::shareCurrentIcs, modifier = Modifier.weight(1f)) { Text("系统分享") }
                Button(onClick = ::saveCurrentIcs, modifier = Modifier.weight(1f)) { Text("保存 .ics") }
            }

            Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text("重新登录官方 CAS") }
            Button(onClick = { auth.logout(); status = "已清除内存会话，请重新登录" }, modifier = Modifier.fillMaxWidth()) {
                Text("退出登录（保留本地课表）")
            }
            Button(onClick = {
                scope.launch {
                    reminderCoordinator.cancelAll()
                    local.deleteAll()
                    selection.clear()
                    auth.logout()
                    status = "已清除全部本地数据"
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Text("清除全部本地数据")
            }
            status?.let { Text(it) }
            Text("课表导出、通知和小组件只应消费当前选中的本地课表；网络失败时不会覆盖旧数据。")
        }
    }
}
