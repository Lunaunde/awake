package com.example.awake.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.unit.sp
import com.example.awake.data.local.CourseSlotEntity
import com.example.awake.data.local.PeriodConfigDefaults
import com.example.awake.data.local.PeriodConfigEntity
import com.example.awake.domain.parser.WeekExpressionParser
import com.example.awake.ui.theme.LocalDarkTheme

private val GridLine = Color(0xFFD8E2E8)
private val GridBackground = Color.Transparent
private val TimeColumnWidth = 34.dp
private val HeaderHeight = 40.dp
private val DefaultRowHeight = 54.dp
private val MinRowHeight = 54.dp
private val MaxRowHeight = 72.dp
private val PeriodCount = PeriodConfigDefaults.periodCount

/** 紧凑周视图：7 个星期列始终铺满屏幕，避免默认横向滚动导致一次只能看见 2~3 天。 */
@Composable
fun WeeklyTimetableGrid(
    courses: List<CourseSlotEntity>,
    currentWeek: Int,
    currentWeekCourseIds: Set<Long> = courses.map { it.sectionId }.toSet(),
    totalWeeks: Int = 30,
    previousCourses: List<CourseSlotEntity> = emptyList(),
    previousWeek: Int = currentWeek - 1,
    previousWeekCourseIds: Set<Long> = emptySet(),
    nextCourses: List<CourseSlotEntity> = emptyList(),
    nextWeek: Int = currentWeek + 1,
    nextWeekCourseIds: Set<Long> = emptySet(),
    periodConfigs: List<PeriodConfigEntity> = emptyList(),
    onCourseClick: (Long) -> Unit,
    onEmptyClick: (dayOfWeek: Int, startPeriod: Int) -> Unit,
    onWeekSwipe: (Int) -> Unit = {},
    /** 当前周为本周时传入今天（周一=1…周日=7），用于把今天的表头加深刻画；查看其他周时为 null。 */
    todayDayOfWeek: Int? = null,
    modifier: Modifier = Modifier
) {
    val vertical = rememberScrollState()
    val pageOffset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dayNames = listOf("一", "二", "三", "四", "五", "六", "日")
    val periodByNumber = periodConfigs.associateBy { it.period }
    val darkTheme = LocalDarkTheme.current
    val paletteByCourse = remember(courses, previousCourses, nextCourses, darkTheme) {
        buildCoursePaletteMap(courses + previousCourses + nextCourses, darkTheme)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(GridBackground, MaterialTheme.shapes.large)
            .clipToBounds()
            .padding(horizontal = 2.dp, vertical = 4.dp)
    ) {
        // 课表区域由外层 weight 提供可用高度。优先把 11 个节次均匀拉伸到视口底部，
        // 这样小屏仍保持紧凑，大屏也不会在第 11 节之后留下大块空白；内容超出时仍可上下滚动。
        // 显式引用 BoxWithConstraintsScope，规避 UnusedBoxWithConstraintsScope 的 lint 误报。
        val rowHeight = if (this@BoxWithConstraints.maxHeight != Dp.Infinity && this@BoxWithConstraints.maxHeight > 0.dp) {
            ((this@BoxWithConstraints.maxHeight - HeaderHeight - 8.dp) / PeriodCount)
                .coerceIn(MinRowHeight, MaxRowHeight)
        } else {
            DefaultRowHeight
        }
        val pageWidth = this@BoxWithConstraints.maxWidth
        val pageWidthPx = with(density) { pageWidth.toPx() }
        val gridHeight = HeaderHeight + rowHeight * PeriodCount
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(vertical)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(gridHeight)
                    .pointerInput(currentWeek, pageWidthPx) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            scope.launch { pageOffset.stop() }
                        },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            val bounded = (pageOffset.value + amount)
                                .coerceIn(-pageWidthPx, pageWidthPx)
                            scope.launch { pageOffset.snapTo(bounded) }
                        },
                        onDragEnd = {
                            val threshold = pageWidthPx * 0.22f
                            val direction = when {
                                pageOffset.value <= -threshold && currentWeek < 30 -> 1
                                pageOffset.value >= threshold && currentWeek > 1 -> -1
                                else -> 0
                            }
                            scope.launch {
                                if (direction == 0) {
                                    pageOffset.animateTo(0f, tween(durationMillis = 180))
                                } else {
                                    // 松手后继续滑到屏幕外，随后切换周次并把新页面归位。
                                    pageOffset.animateTo(
                                        -direction * pageWidthPx,
                                        tween(durationMillis = 220)
                                    )
                                    // 先把动画页归位，再更新周次；否则更新状态的重组可能在
                                    // pageOffset 仍为 -pageWidth 时把“下一周页面”留在当前视口。
                                    pageOffset.snapTo(0f)
                                    onWeekSwipe(direction)
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                pageOffset.animateTo(0f, tween(durationMillis = 180))
                            }
                        }
                    )
                }
        ) {
            // 使用显式偏移放置三页，避免 Row 在重组或不同约束下让相邻页发生重叠。
            // 当前页始终位于 x=0，左右滑动只改变三页的共同偏移量。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(gridHeight)
                    .clipToBounds()
            ) {
                WeekGridPage(
                    modifier = Modifier
                        .requiredWidth(pageWidth)
                        .height(gridHeight)
                        .offset { IntOffset((-pageWidthPx + pageOffset.value).roundToInt(), 0) },
                    courses = previousCourses,
                    currentWeek = previousWeek,
                    currentWeekCourseIds = previousWeekCourseIds,
                    totalWeeks = totalWeeks,
                    rowHeight = rowHeight,
                    periodByNumber = periodByNumber,
                    dayNames = dayNames,
                    paletteByCourse = paletteByCourse,
                    todayDayOfWeek = null,
                    onCourseClick = onCourseClick,
                    onEmptyClick = onEmptyClick
                )
                WeekGridPage(
                    modifier = Modifier
                        .requiredWidth(pageWidth)
                        .height(gridHeight)
                        .offset { IntOffset(pageOffset.value.roundToInt(), 0) },
                    courses = courses,
                    currentWeek = currentWeek,
                    currentWeekCourseIds = currentWeekCourseIds,
                    totalWeeks = totalWeeks,
                    rowHeight = rowHeight,
                    periodByNumber = periodByNumber,
                    dayNames = dayNames,
                    paletteByCourse = paletteByCourse,
                    todayDayOfWeek = todayDayOfWeek,
                    onCourseClick = onCourseClick,
                    onEmptyClick = onEmptyClick
                )
                WeekGridPage(
                    modifier = Modifier
                        .requiredWidth(pageWidth)
                        .height(gridHeight)
                        .offset { IntOffset((pageWidthPx + pageOffset.value).roundToInt(), 0) },
                    courses = nextCourses,
                    currentWeek = nextWeek,
                    currentWeekCourseIds = nextWeekCourseIds,
                    totalWeeks = totalWeeks,
                    rowHeight = rowHeight,
                    periodByNumber = periodByNumber,
                    dayNames = dayNames,
                    paletteByCourse = paletteByCourse,
                    todayDayOfWeek = null,
                    onCourseClick = onCourseClick,
                    onEmptyClick = onEmptyClick
                )
            }
        }
    }
    }

}
@Composable
private fun WeekGridPage(
    modifier: Modifier,
    courses: List<CourseSlotEntity>,
    currentWeek: Int,
    currentWeekCourseIds: Set<Long>,
    totalWeeks: Int,
    rowHeight: Dp,
    periodByNumber: Map<Int, PeriodConfigEntity>,
    dayNames: List<String>,
    paletteByCourse: Map<Long, CoursePalette>,
    todayDayOfWeek: Int?,
    onCourseClick: (Long) -> Unit,
    onEmptyClick: (dayOfWeek: Int, startPeriod: Int) -> Unit
) {
    BoxWithConstraints(modifier = modifier) {
        val dayColumnWidth = ((this@BoxWithConstraints.maxWidth - TimeColumnWidth) / dayNames.size).coerceAtLeast(1.dp)
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.width(TimeColumnWidth)) {
                Box(
                    modifier = Modifier.height(HeaderHeight).fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        "节次",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                (1..PeriodCount).forEach { period ->
                    Box(
                        modifier = Modifier.height(rowHeight).fillMaxWidth(),
                        contentAlignment = Alignment.TopStart
                    ) {
                        Column(
                            modifier = Modifier.padding(start = 2.dp, top = 4.dp),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                text = period.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            periodByNumber[period]?.let { config ->
                                Text(
                                    text = config.startTime,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = config.endTime,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            dayNames.forEachIndexed { index, name ->
                val day = index + 1
                val dayCourses = selectVisibleVariants(
                    courses.filter { it.dayOfWeek == day },
                    currentWeek = currentWeek
                )
                Column(modifier = Modifier.width(dayColumnWidth)) {
                    Box(
                        modifier = Modifier.height(HeaderHeight).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // 今天所在列用主题主色加深刻画（仅本周页传入 todayDayOfWeek，
                            // 相邻周页固定为 null，不会在其他周出现）；其余列加深为 onSurface。
                            val todayColor = if (todayDayOfWeek == day) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                            Text(
                                text = name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = todayColor
                            )
                            Text(
                                text = day.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = todayColor
                            )
                            if (todayDayOfWeek == day) {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "今",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            } else {
                                Spacer(modifier = Modifier.height(14.dp))
                            }
                        }
                    }
                    Box(
                        modifier = Modifier.height(rowHeight * PeriodCount).fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            (1..PeriodCount).forEach { period ->
                                Box(
                                    modifier = Modifier
                                        .height(rowHeight)
                                        .fillMaxWidth()
                                        .clickable { onEmptyClick(day, period) }
                                        .semantics {
                                            contentDescription = "周${name}第${period}节空白时段，点击添加课程"
                                        }
                                )
                            }
                        }
                        layoutDayCourses(dayCourses).forEach { positioned ->
                            WeekGridCourseCard(
                                course = positioned.course,
                                rowHeight = rowHeight,
                                columnWidth = dayColumnWidth,
                                laneIndex = positioned.laneIndex,
                                laneCount = positioned.laneCount,
                                isCurrentWeek = isCourseInWeek(
                                    course = positioned.course,
                                    week = currentWeek,
                                    totalWeeks = totalWeeks,
                                    fallbackSectionIds = currentWeekCourseIds
                                ),
                                currentWeek = currentWeek,
                                totalWeeks = totalWeeks,
                                palette = paletteByCourse[positioned.course.courseId]
                                    ?: paletteForAccent(positioned.course.color, LocalDarkTheme.current),
                                onClick = { onCourseClick(positioned.course.courseId) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 同一天、同一节次如果只是不同周次的同一组时段，不同时显示多个版本。
 * 例如第 4～13 周和第 14 周的同一门课：第 1～13 周只显示前者，
 * 到第 14 周前者结课后，再显示第 14 周的时段。
 */
private fun selectVisibleVariants(
    courses: List<CourseSlotEntity>,
    currentWeek: Int
): List<CourseSlotEntity> = courses.filter { course ->
    courses.none { other ->
        other.sectionId != course.sectionId &&
            periodsOverlap(other, course) &&
            preferredVariant(other, course) &&
            latestWeek(other.rawWeekText) >= currentWeek
    }
}

private fun preferredVariant(other: CourseSlotEntity, course: CourseSlotEntity): Boolean {
    val otherStart = earliestWeek(other.rawWeekText)
    val courseStart = earliestWeek(course.rawWeekText)
    return otherStart < courseStart ||
        (otherStart == courseStart && latestWeek(other.rawWeekText) > latestWeek(course.rawWeekText))
}

private fun isCourseInWeek(
    course: CourseSlotEntity,
    week: Int,
    totalWeeks: Int,
    fallbackSectionIds: Set<Long>
): Boolean {
    // rawWeekText 是导入结果的唯一可解释来源。优先重新解析它，避免旧版本
    // 或异步迁移留下的 course_weeks 关系把开课前课程误判成“本周”。
    val parsedWeeks = WeekExpressionParser.parse(course.rawWeekText, totalWeeks.coerceAtLeast(1)).weeks
    return if (parsedWeeks.isNotEmpty()) week in parsedWeeks else course.sectionId in fallbackSectionIds
}

private fun periodsOverlap(first: CourseSlotEntity, second: CourseSlotEntity): Boolean =
    first.startPeriod <= second.endPeriod && second.startPeriod <= first.endPeriod

private data class PositionedCourse(
    val course: CourseSlotEntity,
    val laneIndex: Int,
    val laneCount: Int
)

/**
 * 把同一天中节次重叠的课程分到不同横向列，避免不同周次的课程互相覆盖。
 * 同一组内按最早开课周次排序，最早开始的课程放在左侧第一列。
 */
private fun layoutDayCourses(courses: List<CourseSlotEntity>): List<PositionedCourse> {
    if (courses.isEmpty()) return emptyList()

    val ordered = courses.sortedWith(
        compareBy<CourseSlotEntity>(
            { it.startPeriod },
            { earliestWeek(it.rawWeekText) },
            { it.endPeriod },
            { it.name }
        )
    )
    val result = mutableListOf<PositionedCourse>()
    var group = mutableListOf<CourseSlotEntity>()
    var groupEnd = 0

    fun flushGroup() {
        if (group.isEmpty()) return
        val laneEnds = mutableListOf<Int>()
        val assignments = mutableListOf<Pair<CourseSlotEntity, Int>>()
        group.forEach { course ->
            val lane = laneEnds.indexOfFirst { endPeriod -> endPeriod < course.startPeriod }
                .let { existing ->
                    if (existing >= 0) existing
                    else {
                        laneEnds += 0
                        laneEnds.lastIndex
                    }
                }
            laneEnds[lane] = course.endPeriod
            assignments += course to lane
        }
        val laneCount = laneEnds.size.coerceAtLeast(1)
        assignments.forEach { (course, lane) ->
            result += PositionedCourse(course, lane, laneCount)
        }
        group = mutableListOf()
        groupEnd = 0
    }

    ordered.forEach { course ->
        if (group.isNotEmpty() && course.startPeriod > groupEnd) flushGroup()
        group += course
        groupEnd = maxOf(groupEnd, course.endPeriod)
    }
    flushGroup()
    return result
}

private fun earliestWeek(raw: String): Int =
    Regex("\\d+").find(raw)?.value?.toIntOrNull() ?: Int.MAX_VALUE

private fun latestWeek(raw: String): Int =
    Regex("\\d+").findAll(raw).mapNotNull { it.value.toIntOrNull() }.maxOrNull() ?: 0

@Composable
fun GridLegend(modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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