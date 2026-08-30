package com.example.awake.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.awake.data.local.CourseEntity
import com.example.awake.data.local.PeriodConfigDefaults
import com.example.awake.data.local.PeriodConfigEntity

private val GridLine = Color(0xFFD8E2E8)
private val HeaderBlue = Color(0xFFE4EFF4)
private val GridBackground = Color(0xFFF9FBFC)
private val TimeColumnWidth = 62.dp
private val HeaderHeight = 46.dp
private val RowHeight = 53.dp
private val PeriodCount = PeriodConfigDefaults.periodCount

/** 紧凑周视图：7 个星期列始终铺满屏幕，避免默认横向滚动导致一次只能看见 2~3 天。 */
@Composable
fun WeeklyTimetableGrid(
    courses: List<CourseEntity>,
    periodConfigs: List<PeriodConfigEntity> = emptyList(),
    onCourseClick: (Long) -> Unit,
    onEmptyClick: (dayOfWeek: Int, startPeriod: Int) -> Unit,
    onWeekSwipe: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val vertical = rememberScrollState()
    val dayNames = listOf("一", "二", "三", "四", "五", "六", "日")

    val periodByNumber = periodConfigs.associateBy { it.period }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(GridBackground, MaterialTheme.shapes.large)
            .border(1.dp, GridLine, MaterialTheme.shapes.large)
            .verticalScroll(vertical)
            .pointerInput(onWeekSwipe) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, amount -> totalDrag += amount },
                    onDragEnd = {
                        when {
                            totalDrag <= -72f -> onWeekSwipe(1)
                            totalDrag >= 72f -> onWeekSwipe(-1)
                        }
                    }
                )
            }
            .padding(4.dp)
    ) {
        val dayColumnWidth = ((maxWidth - TimeColumnWidth) / dayNames.size).coerceAtLeast(1.dp)
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.width(TimeColumnWidth)) {
                Box(
                    modifier = Modifier
                        .height(HeaderHeight)
                        .fillMaxWidth()
                        .border(0.5.dp, GridLine),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "节次",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                (1..PeriodCount).forEach { period ->
                    Box(
                        modifier = Modifier
                            .height(RowHeight)
                            .fillMaxWidth()
                            .border(0.5.dp, GridLine),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Column(
                            modifier = Modifier.padding(top = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = period.toString().padStart(2, '0'),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            periodByNumber[period]?.let { config ->
                                Text(
                                    text = config.startTime,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = config.endTime,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            dayNames.forEachIndexed { index, name ->
                val day = index + 1
                val dayCourses = courses.filter { it.dayOfWeek == day }
                Column(modifier = Modifier.width(dayColumnWidth)) {
                    Box(
                        modifier = Modifier
                            .height(HeaderHeight)
                            .fillMaxWidth()
                            .background(HeaderBlue)
                            .border(0.5.dp, GridLine),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = day.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .height(RowHeight * PeriodCount)
                            .fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            (1..PeriodCount).forEach { period ->
                                Box(
                                    modifier = Modifier
                                        .height(RowHeight)
                                        .fillMaxWidth()
                                        .border(0.5.dp, GridLine)
                                        .clickable { onEmptyClick(day, period) }
                                        .semantics {
                                            contentDescription = "周${name}第${period}节空白时段，点击添加课程"
                                        }
                                )
                            }
                        }
                        dayCourses.forEach { course ->
                            WeekGridCourseCard(
                                course = course,
                                rowHeight = RowHeight,
                                onClick = { onCourseClick(course.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GridLegend(modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LegendItem(Color(0xFF4F6BED), "同步")
        LegendItem(Color(0xFFE78FB3), "手动")
        LegendItem(Color(0xFF8C79C9), "单/双周")
    }
}

@Composable
private fun LegendItem(color: Color, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.width(8.dp).height(8.dp).background(color, MaterialTheme.shapes.small))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
