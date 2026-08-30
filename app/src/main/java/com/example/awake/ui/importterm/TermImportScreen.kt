package com.example.awake.ui.importterm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermImportScreen(viewModel: TermImportViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        CenterAlignedTopAppBar(title = { Text("导入学期课表") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
        })
    }) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("SCUT 学年起始年（xnm）与学期码（xqm）")
            OutlinedTextField(
                state.xnm,
                viewModel::setXnm,
                label = { Text("学年起始年，例如 2026") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                state.xqm,
                viewModel::setXqm,
                label = { Text("学期码：3 / 12 / 16") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                state.label,
                viewModel::setLabel,
                label = { Text("课表名称") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                state.startDate,
                viewModel::setStartDate,
                label = { Text("学期第一周周一（yyyy-MM-dd）") },
                supportingText = { Text("用于计算课前提醒和日历导出日期") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                enabled = !state.busy && state.xnm.isNotBlank() && state.xqm.isNotBlank() && state.label.isNotBlank(),
                onClick = { viewModel.import(onDone) }
            ) { Text(if (state.busy) "导入中…" else "开始导入") }
            Button(enabled = !state.busy, onClick = viewModel::seedDemo) { Text("生成离线演示课表") }
            state.status?.let { Text(it) }
        }
    }

    state.conflictTimetable?.let { existing ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) viewModel.dismissConflict() },
            title = { Text("发现同学期课表") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("已存在“${existing.label}”。请选择导入方式：")
                    Text(
                        "覆盖：替换该课表中的教务同步课程，手动添加的课程仍会保留。\n新建：保留原课表，另存为一份独立课表。"
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissConflict, enabled = !state.busy) { Text("取消") }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { viewModel.createNew(onDone) },
                        enabled = !state.busy
                    ) { Text("新建") }
                    Button(
                        onClick = { viewModel.overwriteExisting(onDone) },
                        enabled = !state.busy
                    ) { Text("覆盖") }
                }
            }
        )
    }
}
