package com.example.awake.ui.timetable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.awake.ui.components.CourseCard
import com.example.awake.ui.components.TimetableSelector
import com.example.awake.ui.components.WeekSelector

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(
    viewModel: TimetableViewModel,
    onLogin: () -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
    onCourse: (Long) -> Unit
) {
    val profile by viewModel.profile.collectAsState()
    val tables by viewModel.timetables.collectAsState()
    val selectedId by viewModel.selectedTimetableId.collectAsState()
    val week by viewModel.currentWeek.collectAsState()
    val courses by viewModel.courses.collectAsState()
    val message by viewModel.message.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    LaunchedEffect(message) { if (message != null) { kotlinx.coroutines.delay(2500); viewModel.clearMessage() } }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Awake 课表") },
                actions = {
                    IconButton(onClick = onImport) { Icon(Icons.Default.Add, contentDescription = "导入课表") }
                    IconButton(onClick = { viewModel.refresh() }, enabled = !refreshing) { Icon(Icons.Default.Refresh, contentDescription = "刷新") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "设置") }
                }, colors = TopAppBarDefaults.centerAlignedTopAppBarColors()
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            if (profile == null || profile?.displayName == "未登录") {
                Text("登录后可从华南理工官方教务系统导入课表。密码和 Cookie 不会保存到本地。", modifier = Modifier.padding(vertical = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onLogin) { Text("官方登录") }
                    Button(onClick = { viewModel.seedDemo() }) { Text("离线演示") }
                }
            }
            if (tables.isNotEmpty()) {
                TimetableSelector(tables, selectedId, viewModel::selectTimetable)
                WeekSelector(week, viewModel::selectWeek)
                if (refreshing) Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                if (courses.isEmpty()) {
                    Text("第 $week 周暂无课程", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 20.dp))
                } else {
                    (1..7).forEach { day ->
                        val dayCourses = courses.filter { it.dayOfWeek == day }
                        if (dayCourses.isNotEmpty()) {
                            Text(dayName(day), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                            dayCourses.forEach { course -> CourseCard(course, { onCourse(course.id) }, Modifier.padding(bottom = 8.dp)) }
                        }
                    }
                }
            } else if (profile != null) {
                Text("还没有课表，点击右上角导入，或使用离线演示。", modifier = Modifier.padding(vertical = 24.dp))
            }
            if (message != null) Text(message!!, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 10.dp))
        }
    }
}

private fun dayName(day: Int) = listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")[day]
