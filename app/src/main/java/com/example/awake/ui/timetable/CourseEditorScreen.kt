package com.example.awake.ui.timetable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.awake.data.local.PeriodConfigDefaults

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseEditorScreen(viewModel: CourseEditorViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val title = when {
        state.sectionId > 0 -> "编辑时段"
        state.masterId > 0 -> "添加时段"
        else -> "添加课程"
    }
    val hint = when {
        state.sectionId > 0 -> "修改会更新该课程的主信息和这个时段；被修改的同步时段不会被下次同步无提示覆盖。"
        state.masterId > 0 -> "为当前课程添加一个时间/地点不同的时段。"
        else -> "添加到当前课表的本地课程；同名同教师的课程会自动归并入同一门课。"
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.save(onDone) },
                        enabled = !state.busy
                    ) {
                        if (state.busy) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text("保存")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 18.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = { Text("课程名称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = state.teacher,
                onValueChange = viewModel::setTeacher,
                label = { Text("教师（可选）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = state.room,
                onValueChange = viewModel::setRoom,
                label = { Text("教室（可选）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = state.dayOfWeek,
                onValueChange = viewModel::setDay,
                label = { Text("星期（1=周一，7=周日）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = state.startPeriod,
                onValueChange = viewModel::setStart,
                label = { Text("开始节次（1-${PeriodConfigDefaults.periodCount}）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = state.endPeriod,
                onValueChange = viewModel::setEnd,
                label = { Text("结束节次（1-${PeriodConfigDefaults.periodCount}）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedButton(
                onClick = { viewModel.setWeekDialogVisible(true) },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Text(
                    "上课周次：${state.weeks}（点击选择）",
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            if (state.showWeekDialog) {
                com.example.awake.ui.components.WeekPickerDialog(
                    initial = viewModel.selectedWeeks(),
                    onDismiss = { viewModel.setWeekDialogVisible(false) },
                    onConfirm = viewModel::applyWeekSelection
                )
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}