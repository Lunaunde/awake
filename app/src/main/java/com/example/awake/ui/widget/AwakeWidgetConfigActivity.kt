package com.example.awake.ui.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.awake.AwakeApplication
import com.example.awake.data.local.TimetableEntity
import com.example.awake.data.repository.LocalTimetableRepository
import com.example.awake.data.widget.AwakeWidgetPrefs
import com.example.awake.data.widget.AwakeWidgetUpdater
import com.example.awake.ui.theme.AwakeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 小组件配置页：选择组件绑定的课表。
 *
 * 两种入口共用：
 * - 添加组件时由系统以 APPWIDGET_CONFIGURE 启动（携带 EXTRA_APPWIDGET_ID）；
 * - 组件上的「课表」按钮重新选择（同样携带 EXTRA_APPWIDGET_ID）。
 * 选「跟随当前课表」则组件随时跟随 App 主界面选中的课表。
 */
class AwakeWidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
        val local = (application as AwakeApplication).container.localRepository
        setContent {
            AwakeTheme {
                WidgetConfigScreen(
                    local = local,
                    onPicked = { timetableId ->
                        if (widgetId >= 0) {
                            AwakeWidgetPrefs(this).setTimetableId(widgetId, timetableId)
                            AwakeWidgetUpdater.requestUpdate(this)
                            finishConfigure(widgetId)
                        } else {
                            finish()
                        }
                    }
                )
            }
        }
    }

    private fun finishConfigure(widgetId: Int) {
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        setResult(Activity.RESULT_OK, result)
        finish()
    }
}

@Composable
private fun WidgetConfigScreen(
    local: LocalTimetableRepository,
    onPicked: (Long?) -> Unit
) {
    var timetables by remember { mutableStateOf<List<TimetableEntity>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        timetables = withContext(Dispatchers.IO) {
            local.ensureProfile()?.let { local.getTimetables(it.id) } ?: emptyList()
        }
        loaded = true
    }
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "选择小组件显示的课表",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            Text(
                "选择后组件显示该课表；选「跟随当前课表」则跟随 App 主界面当前选中的课表。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPicked(null) },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                )
            ) {
                Text(
                    "跟随当前课表",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(timetables, key = { it.id }) { timetable ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPicked(timetable.id) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                    ) {
                        Row(modifier = Modifier.padding(14.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(timetable.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${timetable.xnm} 学年第 ${timetable.xqm} 学期 · 起始 ${timetable.startDate}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                if (loaded && timetables.isEmpty()) {
                    item {
                        Text(
                            "还没有本地课表，请先打开 App 导入或创建课表。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                }
            }
        }
    }
}