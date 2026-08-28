package com.example.awake.ui.importterm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.awake.data.repository.LocalTimetableRepository
import com.example.awake.data.repository.ScutScheduleRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermImportScreen(local: LocalTimetableRepository, remote: ScutScheduleRepository, onBack: () -> Unit, onDone: () -> Unit) {
    var xnm by remember { mutableStateOf("2026") }
    var xqm by remember { mutableStateOf("3") }
    var label by remember { mutableStateOf("2026-2027 第一学期") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Scaffold(topBar = {
        CenterAlignedTopAppBar(title = { Text("导入学期课表") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
        })
    }) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("SCUT 学年起始年（xnm）与学期码（xqm）")
            OutlinedTextField(xnm, { xnm = it.filter(Char::isDigit) }, label = { Text("学年起始年，例如 2026") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(xqm, { xqm = it.filter(Char::isDigit) }, label = { Text("学期码：3 / 12 / 16") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(label, { label = it }, label = { Text("课表名称") }, modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy && xnm.isNotBlank() && xqm.isNotBlank(), onClick = {
                scope.launch {
                    busy = true; status = "正在创建课表并请求教务接口…"
                    val result = runCatching {
                        val profile = local.ensureProfile()
                        val table = local.findOrCreateTimetable(profile.id, xnm.toInt(), xqm, label)
                        val warnings = remote.import(table.id)
                        if (warnings.isEmpty()) "导入成功" else "导入成功，跳过 ${warnings.size} 条无法解析的记录"
                    }
                    status = result.getOrElse { it.message ?: "导入失败，旧数据未被覆盖" }
                    busy = false
                    if (result.isSuccess) onDone()
                }
            }) { Text(if (busy) "导入中…" else "开始导入") }
            Button(enabled = !busy, onClick = { scope.launch { local.seedDemoTimetable(); status = "已生成离线演示课表" } }) { Text("生成离线演示课表") }
            status?.let { Text(it) }
        }
    }
}
