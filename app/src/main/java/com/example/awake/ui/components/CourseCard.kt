package com.example.awake.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.awake.data.local.CourseEntity

@Composable
fun CourseCard(course: CourseEntity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = Color(course.color)
    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(course.name, style = MaterialTheme.typography.titleMedium)
                if (course.teacher.isNotBlank()) Text(course.teacher, style = MaterialTheme.typography.bodySmall)
                if (course.room.isNotBlank()) Text(course.room, style = MaterialTheme.typography.bodySmall)
                Text("第${course.startPeriod}-${course.endPeriod}节 · ${course.rawWeekText}", style = MaterialTheme.typography.labelSmall)
            }
            Column(modifier = Modifier.background(accent.copy(alpha = .16f), RoundedCornerShape(8.dp)).padding(8.dp)) {
                Text(if (course.source == "MANUAL") "手动" else "同步", color = accent, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun WeekSelector(week: Int, onWeekChange: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.material3.OutlinedButton(onClick = { onWeekChange(week - 1) }, enabled = week > 1) { Text("‹") }
        Text("第 $week 周", modifier = Modifier.weight(1f).padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
        androidx.compose.material3.OutlinedButton(onClick = { onWeekChange(week + 1) }, enabled = week < 30) { Text("›") }
    }
}
