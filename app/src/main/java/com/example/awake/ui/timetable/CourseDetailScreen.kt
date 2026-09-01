package com.example.awake.ui.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private fun dayLabel(day: Int): String = listOf("一", "二", "三", "四", "五", "六", "日").getOrElse(day - 1) { "?" }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CourseDetailScreen(
    viewModel: CourseDetailViewModel,
    onBack: () -> Unit,
    onEditSection: (sectionId: Long) -> Unit = {},
    onAddSection: (timetableId: Long, masterId: Long) -> Unit = { _, _ -> }
) {
    val original by viewModel.course.collectAsStateWithLifecycle()
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    var name by remember(original?.id) { mutableStateOf(original?.name.orEmpty()) }
    var teacher by remember(original?.id) { mutableStateOf(original?.teacher.orEmpty()) }
    // 颜色单一事实来源 = 数据库主记录；任何修改立即写库并由 Flow 回流刷新。
    val pickedColor = ((original?.color ?: 0) or 0xFF000000.toInt())
    // 颜色候选：默认色板（已去除过近色相）。
    val colorChoices = remember { com.example.awake.domain.model.DefaultCourseColors }

    Scaffold(topBar = {
        CenterAlignedTopAppBar(
            title = { Text("课程详情") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
            },
            actions = {
                // 名称/教师修改的保存入口固定在右上角，不再占用主内容区。
                TextButton(
                    onClick = {
                        original?.let { current ->
                            viewModel.saveMaster(current.copy(name = name.trim(), teacher = teacher.trim())) {}
                        }
                    }
                ) { Text("保存") }
            }
        )
    }) { padding ->
        val current = original
        if (current == null) {
            Text("课程不存在", modifier = Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("课程名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(teacher, { teacher = it }, label = { Text("教师") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

            Text("课程颜色", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                colorChoices.forEach { color ->
                    val selected = color == pickedColor
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .background(
                                androidx.compose.ui.graphics.Color(color),
                                androidx.compose.foundation.shape.CircleShape
                            )
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.outlineVariant,
                                shape = androidx.compose.foundation.shape.CircleShape
                            )
                            .clickable { viewModel.updateColor(current.id, color) }
                            .semantics { contentDescription = "选择课程颜色（立即保存）" }
                    )
                }
                // 随机取色：生成保持当前马卡龙风格的 RGB 颜色，点选即写库。
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(
                            androidx.compose.ui.graphics.Color(pickedColor).copy(alpha = 0.85f),
                            CircleShape
                        )
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .clickable { viewModel.updateColor(current.id, com.example.awake.domain.model.randomCourseColor()) }
                        .semantics { contentDescription = "随机取色" },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            // RGB 精确设置：三个 0-255 数字输入框；预设/随机/输入互通（都以数据库主记录为准），
            // 任何输入立即写库，随 Room Flow 回流刷新预览。
            val redChannel = (pickedColor shr 16) and 0xFF
            val greenChannel = (pickedColor shr 8) and 0xFF
            val blueChannel = pickedColor and 0xFF
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                var redText by remember(redChannel) { mutableStateOf(redChannel.toString()) }
                var greenText by remember(greenChannel) { mutableStateOf(greenChannel.toString()) }
                var blueText by remember(blueChannel) { mutableStateOf(blueChannel.toString()) }
                RgbChannelField("R", redText, Modifier.weight(1f)) { value ->
                    redText = value
                    val parsed = value.toIntOrNull() ?: return@RgbChannelField
                    viewModel.updateColor(current.id, withRgbChannel(pickedColor, 16, parsed.coerceIn(0, 255)))
                }
                RgbChannelField("G", greenText, Modifier.weight(1f)) { value ->
                    greenText = value
                    val parsed = value.toIntOrNull() ?: return@RgbChannelField
                    viewModel.updateColor(current.id, withRgbChannel(pickedColor, 8, parsed.coerceIn(0, 255)))
                }
                RgbChannelField("B", blueText, Modifier.weight(1f)) { value ->
                    blueText = value
                    val parsed = value.toIntOrNull() ?: return@RgbChannelField
                    viewModel.updateColor(current.id, withRgbChannel(pickedColor, 0, parsed.coerceIn(0, 255)))
                }
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(
                            androidx.compose.ui.graphics.Color(pickedColor),
                            CircleShape
                        )
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .semantics { contentDescription = "当前颜色预览" }
                )
            }
            Text(
                "色点/随机/RGB 输入都会立即保存；「右上角保存」用于保存名称与教师。颜色随主课程生效，课表所有时段一起变。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("上课时段（${sections.size}）", style = MaterialTheme.typography.titleMedium)
            sections.forEach { section ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "周${dayLabel(section.dayOfWeek)} · 第${section.startPeriod}-${section.endPeriod}节" +
                                (section.room.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        val weeksText = remember(section.rawWeekText) {
                            val parsed = com.example.awake.domain.parser.WeekExpressionParser.parse(
                                section.rawWeekText, maxWeek = 30
                            )
                            if (parsed.warning == null && parsed.weeks.isNotEmpty()) {
                                com.example.awake.domain.parser.WeekSelection.format(parsed.weeks)
                            } else {
                                section.rawWeekText
                            }
                        }
                        if (section.rawWeekText.isNotBlank()) {
                            Text("周次：$weeksText", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { onEditSection(section.id) }) { Text("编辑时段") }
                            TextButton(onClick = { viewModel.deleteSection(section.id) {} }) { Text("删除时段") }
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = { onAddSection(current.timetableId, current.id) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("添加时段") }
            androidx.compose.material3.Button(
                onClick = { viewModel.deleteMaster(onBack) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("删除课程") }
            Text(
                "删除课程会同时删除它的全部时段；删除单个时段请使用上面的“删除时段”。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 单个 RGB 通道输入框：仅允许数字，最多 3 位；为空时不改通道值。 */
@Composable
private fun RgbChannelField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw -> onChange(raw.filter(Char::isDigit).take(3)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

/** 替换 ARGB 颜色中某个信道（shift=16 红 / 8 绿 / 0 蓝），保留其余位（含透明度）。 */
private fun withRgbChannel(color: Int, shift: Int, value: Int): Int =
    (color and (0xFF shl shift).inv()) or (value shl shift)