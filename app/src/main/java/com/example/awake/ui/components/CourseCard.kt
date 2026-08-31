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
import com.example.awake.data.local.CourseEntity

data class CoursePalette(
    val background: Color,
    val accent: Color
)

// 颜色按课程名称稳定计算：同名课程保持一致，同时扩大色板并提高边框对比度，
// 避免所有卡片看起来像一组相近的粉彩色。
private val CoursePalettes = listOf(
    CoursePalette(Color(0xFFD7E8FF), Color(0xFF4778E8)), // 蓝
    CoursePalette(Color(0xFFFFE0D8), Color(0xFFE56B55)), // 珊瑚
    CoursePalette(Color(0xFFD8F3E4), Color(0xFF2A9D74)), // 薄荷
    CoursePalette(Color(0xFFE9E0FF), Color(0xFF7654D6)), // 紫
    CoursePalette(Color(0xFFFFF0C2), Color(0xFFD99616)), // 琥珀
    CoursePalette(Color(0xFFFFDCE7), Color(0xFFD84F78)), // 玫红
    CoursePalette(Color(0xFFD9F1F5), Color(0xFF238FA3)), // 青
    CoursePalette(Color(0xFFE7F1C8), Color(0xFF719A2C)), // 黄绿
    CoursePalette(Color(0xFFFFE5C6), Color(0xFFD47722)), // 橙
    CoursePalette(Color(0xFFDDE7F7), Color(0xFF5874B8)), // 靛蓝
    CoursePalette(Color(0xFFF0DDF4), Color(0xFF9B4DAB)), // 兰紫
    CoursePalette(Color(0xFFD8EEE9), Color(0xFF258B7A)), // 蓝绿
    CoursePalette(Color(0xFFFFE0E4), Color(0xFFC94C65)), // 红
    CoursePalette(Color(0xFFE0E5FF), Color(0xFF5669D6)), // 紫蓝
    CoursePalette(Color(0xFFDDF4EF), Color(0xFF249B88)), // 海绿色
    CoursePalette(Color(0xFFFFE8D6), Color(0xFFCF7A3E)), // 杏色
    CoursePalette(Color(0xFFEADFF2), Color(0xFF8C5BAA)), // 藕紫
    CoursePalette(Color(0xFFD9EDFF), Color(0xFF3587C4))  // 天蓝
)

@Composable
fun CourseCard(course: CourseEntity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = coursePaletteFor(course.name)
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
    course: CourseEntity,
    rowHeight: Dp,
    columnWidth: Dp,
    laneIndex: Int = 0,
    laneCount: Int = 1,
    isCurrentWeek: Boolean = true,
    currentWeek: Int = 1,
    totalWeeks: Int = 30,
    palette: CoursePalette = coursePaletteFor(course.name),
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val span = (course.endPeriod - course.startPeriod + 1).coerceAtLeast(1)
    val height = heightForCourse(rowHeight, span)
    val accent = palette.accent
    val background = palette.background
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

internal fun coursePaletteFor(name: String): CoursePalette {
    val normalizedName = normalizeCourseName(name)
    val index = Math.floorMod(stablePaletteSeed(normalizedName), CoursePalettes.size)
    return CoursePalettes[index]
}

/**
 * 为当前课表中的不同课程名分配不重复的色板槽位，避免 hash 取模后撞色。
 * 同名课程使用同一个 key，因此不同星期和不同周次仍保持同色。
 */
internal fun buildCoursePaletteMap(courses: List<CourseEntity>): Map<String, CoursePalette> {
    val names = courses
        .map { normalizeCourseName(it.name) }
        .distinct()
        .sorted()
    if (names.isEmpty()) return emptyMap()

    // 按当前课表中“不同课程名”的数量均匀分布色相：不同名字不会撞到同一颜色，
    // 同一个名字无论星期或周次如何变化，都会从同一张映射表取到同一个颜色。
    return names.mapIndexed { index, name ->
        val hue = (20f + index * 360f / names.size) % 360f
        name to CoursePalette(
            background = Color.hsv(hue, saturation = 0.20f, value = 1.0f),
            accent = Color.hsv(hue, saturation = 0.72f, value = 0.78f)
        )
    }.toMap()
}

private fun normalizeCourseName(name: String): String = name.trim().ifBlank { "未命名" }

private fun stablePaletteSeed(name: String): Int {
    // 先对 String.hashCode 做一次混合，避免中文课程名的 hash 在取模后大量撞色。
    var mixed = name.hashCode()
    mixed = (mixed xor (mixed ushr 16)) * 0x045D9F3B
    mixed = (mixed xor (mixed ushr 16)) * 0x045D9F3B
    return mixed xor (mixed ushr 16)
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
