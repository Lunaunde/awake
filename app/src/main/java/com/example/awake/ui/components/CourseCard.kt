package com.example.awake.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.example.awake.data.local.CourseEntity

private val PastelBlue = Color(0xFFDCEBFA)
private val PastelPink = Color(0xFFF8DDE7)
private val PastelMint = Color(0xFFDDF1E5)
private val PastelLilac = Color(0xFFE8E1F8)
private val PastelYellow = Color(0xFFF9EDC8)
private val PastelPeach = Color(0xFFF8E0D0)

@Composable
fun CourseCard(course: CourseEntity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = Color(course.color)
    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .height(58.dp)
                    .background(accent, RoundedCornerShape(4.dp))
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(course.name.ifBlank { "未命名课程" }, style = MaterialTheme.typography.titleMedium)
                if (course.teacher.isNotBlank()) Text(course.teacher, style = MaterialTheme.typography.bodySmall)
                if (course.room.isNotBlank()) Text(course.room, style = MaterialTheme.typography.bodySmall)
                Text("第${course.startPeriod}-${course.endPeriod}节 · ${course.rawWeekText}", style = MaterialTheme.typography.labelSmall)
            }
            StatusPill(if (course.source == "MANUAL") "手动" else "同步", accent)
        }
    }
}

@Composable
fun WeekGridCourseCard(
    course: CourseEntity,
    rowHeight: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val span = (course.endPeriod - course.startPeriod + 1).coerceAtLeast(1)
    val height = rowHeight * span - 5.dp
    val accent = Color(course.color)
    val background = pastelFor(course)
    Box(
        modifier = modifier
            .offset(y = rowHeight * (course.startPeriod - 1))
            .height(height.coerceAtLeast(38.dp))
            .fillMaxWidth()
            .padding(horizontal = 5.dp, vertical = 2.dp)
            .background(background, RoundedCornerShape(8.dp))
            .border(1.dp, accent.copy(alpha = 0.62f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 4.dp)
            .semantics { contentDescription = "${course.name}，第${course.startPeriod}到${course.endPeriod}节" },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = course.name.ifBlank { "未命名" },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = if (span >= 3) 4 else 2,
                overflow = TextOverflow.Ellipsis
            )
            if (span >= 2 && course.room.isNotBlank()) {
                Text(
                    text = "@${course.room}",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (span >= 3) {
                StatusPill(if (course.source == "MANUAL") "手动" else weekLabel(course.rawWeekText), accent)
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, accent: Color) {
    Box(
        modifier = Modifier
            .background(accent.copy(alpha = 0.13f), RoundedCornerShape(8.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text(text, color = accent, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

private fun pastelFor(course: CourseEntity): Color {
    val colors = listOf(PastelBlue, PastelPink, PastelMint, PastelLilac, PastelYellow, PastelPeach)
    val index = kotlin.math.abs((course.name + course.dayOfWeek).hashCode()) % colors.size
    return colors[index]
}

private fun weekLabel(raw: String): String = when {
    raw.contains("单") -> "单周"
    raw.contains("双") -> "双周"
    else -> "同步"
}
