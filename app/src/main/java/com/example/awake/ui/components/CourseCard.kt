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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.example.awake.data.local.CourseSlotEntity
import com.example.awake.ui.theme.LocalDarkTheme

data class CoursePalette(
    val background: Color,
    val accent: Color
)

@Composable
fun CourseCard(course: CourseSlotEntity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = paletteForAccent(course.color, LocalDarkTheme.current)
    val accent = palette.accent
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
            if (course.source == "MANUAL") {
                StatusPill("手动", accent)
            }
        }
    }
}

@Composable
fun WeekGridCourseCard(
    course: CourseSlotEntity,
    rowHeight: Dp,
    columnWidth: Dp,
    laneIndex: Int = 0,
    laneCount: Int = 1,
    isCurrentWeek: Boolean = true,
    currentWeek: Int = 1,
    totalWeeks: Int = 30,
    palette: CoursePalette? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val resolvedPalette = palette ?: paletteForAccent(course.color, LocalDarkTheme.current)
    val span = (course.endPeriod - course.startPeriod + 1).coerceAtLeast(1)
    val height = heightForCourse(rowHeight, span)
    val accent = resolvedPalette.accent
    val background = resolvedPalette.background
    val safeLaneCount = laneCount.coerceAtLeast(1)
    val laneWidth = (columnWidth / safeLaneCount).coerceAtLeast(1.dp)
    val laneStart = laneWidth * laneIndex.coerceIn(0, safeLaneCount - 1)
    // 外层占满整个课表网格，内层再把课程放到对应节次和横向列。
    // 这样不会因为 offset 子布局的测量顺序导致第 3 节及以后课程出现 0 尺寸。
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(rowHeight * 11)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = laneStart, top = rowHeight * (course.startPeriod - 1))
        ) {
            Box(
                modifier = Modifier
                    .alpha(if (isCurrentWeek) 1f else 0.42f)
                    .width(laneWidth)
                    .height(height)
                    .padding(horizontal = 3.dp, vertical = 2.dp)
                    .background(background, RoundedCornerShape(8.dp))
                    .border(1.dp, accent.copy(alpha = 0.62f), RoundedCornerShape(8.dp))
                    .clickable(onClick = onClick)
                    .padding(horizontal = 3.dp, vertical = 4.dp)
                    .semantics {
                        contentDescription = buildString {
                            append(course.name)
                            append("，第${course.startPeriod}到${course.endPeriod}节")
                            if (!isCurrentWeek) append("，非本周课程")
                        }
                    },
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
                    if (course.source == "MANUAL") {
                        StatusPill("手动", accent)
                    } else {
                        weekParityLabel(course.rawWeekText, currentWeek, totalWeeks)?.let {
                            StatusPill(it, accent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, accent: Color) {
    Box(
        modifier = Modifier
            .background(accent.copy(alpha = 0.13f), RoundedCornerShape(8.dp))
            .padding(horizontal = 3.dp, vertical = 2.dp)
    ) {
        Text(text, color = accent, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/**
 * 由课程主记录存储的颜色（accent）派生卡片配色。
 * 浅色模式：背景取同一色相的低饱和浅色；深色模式：取低明度深色，
 * 保证主题文字色（浅色下深字 / 深色下白字）在两种背景上都可读。
 */
internal fun paletteForAccent(color: Int, dark: Boolean = false): CoursePalette {
    val (hue, _, _) = rgbToHsv(color)
    return if (dark) {
        CoursePalette(
            background = Color.hsv(hue, saturation = 0.28f, value = 0.36f),
            accent = Color.hsv(hue, saturation = 0.80f, value = 0.90f)
        )
    } else {
        CoursePalette(
            background = Color.hsv(hue, saturation = 0.20f, value = 1.0f),
            accent = Color.hsv(hue, saturation = 0.72f, value = 0.78f)
        )
    }
}

/**
 * 为当前课表中的课程构建颜色映射：键为课程主记录 id。
 * 同一门课（所有时段）天然共享同一份存储颜色。
 */
internal fun buildCoursePaletteMap(courses: List<CourseSlotEntity>, dark: Boolean = false): Map<Long, CoursePalette> =
    courses.associate { it.courseId to paletteForAccent(it.color, dark) }

private fun rgbToHsv(color: Int): Triple<Float, Float, Float> {
    val r = (color shr 16 and 0xFF) / 255f
    val g = (color shr 8 and 0xFF) / 255f
    val b = (color and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val hue = when {
        delta <= 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * ((b - r) / delta + 2f)
        else -> 60f * ((r - g) / delta + 4f)
    }.let { if (it < 0f) it + 360f else it }
    val saturation = if (max <= 0f) 0f else delta / max
    return Triple(hue, saturation, max)
}

private fun heightForCourse(rowHeight: Dp, span: Int): Dp =
    (rowHeight * span - 5.dp).coerceAtLeast(38.dp)

/**
 * 只在当前周落入带单双周标记的那一段时显示标签。
 *
 * 例如“1-3周(单),4-14周”在第 1～3 周显示“单周”，进入第 4 周后不再显示标签，
 * 不把整门课笼统地标成“分段”或“教务同步”。这里按区间判断，不按单双周过滤，
 * 因此第 2 周看到第 1～3 周的半透明课程时仍能看到“单周”提示。
 */
internal fun weekParityLabel(raw: String, currentWeek: Int, totalWeeks: Int = 30): String? {
    val normalized = raw
        .replace('（', '(')
        .replace('）', ')')
        .replace('—', '-')
        .replace('~', '-')
        .replace('至', '-')
        .replace('到', '-')
        .lowercase()
    val tokens = normalized.split(',', '，', '、', ';', '；', '|', '/')
    return tokens.firstNotNullOfOrNull { token ->
        val parity = when {
            token.contains("单") || token.contains("odd") -> "单周"
            token.contains("双") || token.contains("even") -> "双周"
            else -> null
        } ?: return@firstNotNullOfOrNull null

        val weeks = Regex("\\d+").findAll(token).mapNotNull { it.value.toIntOrNull() }.toList()
        val start = weeks.firstOrNull() ?: 1
        val end = weeks.getOrNull(1) ?: start.takeIf { weeks.isNotEmpty() } ?: totalWeeks
        if (currentWeek in start..end) parity else null
    }
}
