package com.example.awake.ui.timetable

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailScreen(viewModel: CourseDetailViewModel, onBack: () -> Unit) {
    val original by viewModel.course.collectAsStateWithLifecycle()
    var name by remember(original?.id) { mutableStateOf(original?.name.orEmpty()) }
    var teacher by remember(original?.id) { mutableStateOf(original?.teacher.orEmpty()) }
    var room by remember(original?.id) { mutableStateOf(original?.room.orEmpty()) }
    var start by remember(original?.id) { mutableStateOf(original?.startPeriod?.toString().orEmpty()) }
    var end by remember(original?.id) { mutableStateOf(original?.endPeriod?.toString().orEmpty()) }
    var weeks by remember(original?.id) { mutableStateOf(original?.rawWeekText.orEmpty()) }

    Scaffold(topBar = {
        CenterAlignedTopAppBar(title = { Text("课程详情") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
        })
    }) { padding ->
        val current = original
        if (current == null) {
            Text("课程不存在", modifier = Modifier.padding(padding).padding(16.dp))
        } else {
            Column(modifier = Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("课程名称") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(teacher, { teacher = it }, label = { Text("教师") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(room, { room = it }, label = { Text("教室") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(start, { start = it.filter(Char::isDigit) }, label = { Text("开始节次") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(end, { end = it.filter(Char::isDigit) }, label = { Text("结束节次") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = weeks,
                    onValueChange = { weeks = it },
                    label = { Text("上课周次") },
                    placeholder = { Text("如：1-16周、单周") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = {
                    viewModel.save(current.copy(
                        name = name,
                        teacher = teacher,
                        room = room,
                        startPeriod = start.toIntOrNull() ?: current.startPeriod,
                        endPeriod = end.toIntOrNull() ?: current.endPeriod,
                        rawWeekText = weeks.trim()
                    ), onBack)
                }, modifier = Modifier.fillMaxWidth()) { Text("保存修改") }
                Button(onClick = { viewModel.delete(current.id, onBack) }, modifier = Modifier.fillMaxWidth()) { Text("删除课程") }
            }
        }
    }
}
