package com.example.awake.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.awake.domain.parser.WeekSelection

/**
 * 1..30 周的周次选择对话框。
 * 支持点击切换，也支持按住下滑动拖选：按下第一格决定是“加选”还是“减选”，
 * 手指划过的格子会按该模式连续选中/取消。
 */
@Composable
fun WeekPickerDialog(
    initial: Set<Int>,
    onDismiss: () -> Unit,
    onConfirm: (Set<Int>) -> Unit
) {
    val totalWeeks = 30
    val cols = 8
    val rows = (totalWeeks + cols - 1) / cols
    var draft by remember { mutableStateOf(initial.filter { it in 1..totalWeeks }.toSet()) }
    /** 锚点拖选：起点周 + 当前周确定区间，而不是涂选路径经过的格子。 */
    var dragAnchor by remember { mutableStateOf<Int?>(null) }
    var dragBase by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var dragAdd by remember { mutableStateOf(true) }
    var gridSize by remember { mutableStateOf(IntSize.Zero) }
    val accent = MaterialTheme.colorScheme.primary

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择上课周") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { draft = (1..totalWeeks).toSet() }) { Text("全选") }
                    TextButton(onClick = { draft = (1..totalWeeks).filter { it % 2 == 1 }.toSet() }) { Text("单周") }
                    TextButton(onClick = { draft = (1..totalWeeks).filter { it % 2 == 0 }.toSet() }) { Text("双周") }
                    TextButton(onClick = { draft = emptySet() }) { Text("清空") }
                }
                Box(
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxWidth()
                        .height(46.dp * rows)
                        .onSizeChanged { gridSize = it }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val week = cellWeekAt(offset.x, offset.y, gridSize, cols, rows, totalWeeks)
                                    if (week != null) {
                                        // 锚点区间拖选：以按下第一格的状态决定本轮是加选还是减选，
                                        // 松手前拖到任意格子，选中/取消的都是“起点-当前”整段区间。
                                        dragAnchor = week
                                        dragBase = draft
                                        dragAdd = week !in draft
                                        draft = if (dragAdd) draft + week else draft - week
                                    } else {
                                        dragAnchor = null
                                    }
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val anchor = dragAnchor ?: return@detectDragGestures
                                    val current = cellWeekAt(
                                        change.position.x, change.position.y, gridSize, cols, rows, totalWeeks
                                    ) ?: return@detectDragGestures
                                    val range = minOf(anchor, current)..maxOf(anchor, current)
                                    draft = if (dragAdd) dragBase + range else dragBase - range
                                },
                                onDragEnd = {
                                    dragAnchor = null
                                    dragBase = emptySet()
                                },
                                onDragCancel = {
                                    dragAnchor = null
                                    dragBase = emptySet()
                                }
                            )
                        }
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val week = cellWeekAt(offset.x, offset.y, gridSize, cols, rows, totalWeeks)
                                if (week != null) {
                                    draft = if (week in draft) draft - week else draft + week
                                }
                            }
                        }
                ) {
                    Column(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                        repeat(rows) { row ->
                            androidx.compose.foundation.layout.Row(
                                modifier = androidx.compose.ui.Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                            ) {
                                repeat(cols) { col ->
                                    val week = row * cols + col + 1
                                    val selected = week in draft
                                    Box(
                                        modifier = androidx.compose.ui.Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .padding(2.dp)
                                            .background(
                                                if (selected) accent.copy(alpha = 0.92f) else Color.Transparent,
                                                RoundedCornerShape(10.dp)
                                            )
                                            .border(
                                                1.dp,
                                                if (selected) accent else MaterialTheme.colorScheme.outlineVariant,
                                                RoundedCornerShape(10.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (week <= totalWeeks) {
                                            Text(
                                                week.toString(),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Text(
                    text = draft.sorted()
                        .takeIf { it.isNotEmpty() }
                        ?.let { "已选 ${it.size} 周 · ${WeekSelection.format(draft)}" }
                        ?: "未选择任何周",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(draft) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 由容器内坐标换算出格子对应的周号；超出 1..totalWeeks 返回 null。 */
private fun cellWeekAt(x: Float, y: Float, size: IntSize, cols: Int, rows: Int, totalWeeks: Int): Int? {
    if (size.width <= 0 || size.height <= 0) return null
    val col = (x / (size.width.toFloat() / cols)).toInt().coerceIn(0, cols - 1)
    val row = (y / (size.height.toFloat() / rows)).toInt().coerceIn(0, rows - 1)
    val week = row * cols + col + 1
    return week.takeIf { it in 1..totalWeeks }
}