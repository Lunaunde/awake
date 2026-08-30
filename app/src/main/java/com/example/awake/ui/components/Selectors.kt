package com.example.awake.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.example.awake.data.local.TimetableEntity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun WeekSelector(week: Int, onWeekChange: (Int) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = { onWeekChange(week - 1) }, enabled = week > 1) { Text("‹") }
        Button(onClick = { showPicker = true }, modifier = Modifier.weight(1f)) { Text("第 $week 周 · 点击选择") }
        OutlinedButton(onClick = { onWeekChange(week + 1) }, enabled = week < 30) { Text("›") }
    }
    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("选择周次") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    (1..30).chunked(3).forEach { row ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { item ->
                                TextButton(
                                    onClick = { onWeekChange(item); showPicker = false },
                                    modifier = Modifier.weight(1f)
                                ) { Text(if (item == week) "第${item}周 · 当前" else "第${item}周") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text("取消") } }
        )
    }
}

@Composable
fun TimetableSelector(
    timetables: List<TimetableEntity>,
    selectedId: Long?,
    onTimetableChange: (Long) -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    val selected = timetables.firstOrNull { it.id == selectedId }
    OutlinedButton(
        onClick = { showPicker = true },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(selected?.label ?: "选择课表")
    }
    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("选择课表") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    timetables.forEach { timetable ->
                        TextButton(
                            onClick = {
                                onTimetableChange(timetable.id)
                                showPicker = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (timetable.id == selectedId) "${timetable.label} · 当前"
                                else timetable.label
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text("取消") } }
        )
    }
}
