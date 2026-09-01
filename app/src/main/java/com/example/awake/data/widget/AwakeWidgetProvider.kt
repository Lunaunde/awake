package com.example.awake.data.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.awake.AwakeApplication
import com.example.awake.MainActivity
import com.example.awake.R
import com.example.awake.data.local.CourseSlotEntity
import com.example.awake.data.local.PeriodConfigEntity
import com.example.awake.data.local.TimetableEntity
import com.example.awake.domain.parser.WeekExpressionParser
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 轻量级经典 AppWidget：只从本地 Room 读取当前选中的课表，不访问网络。 */
class AwakeWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = goAsync()
        val app = context.applicationContext as? AwakeApplication
        if (app == null) {
            pendingResult.finish()
            return
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val data = loadData(app)
                appWidgetIds.forEach { updateWidget(context, manager, it, data) }
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) = Unit

    private suspend fun loadData(app: AwakeApplication): WidgetData {
        val local = app.container.localRepository
        val timetable = app.container.timetableSelectionStore.read()
            ?.let { local.getTimetableOrNull(it) }
            ?: local.getFirstTimetable()
        if (timetable == null) return WidgetData.empty()

        val startDate = timetable.startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val today = LocalDate.now()
        val week = startDate?.let { ChronoUnit.WEEKS.between(it, today).toInt() + 1 }
            ?.coerceIn(1, timetable.totalWeeks.coerceAtLeast(1))
            ?: 1
        val day = today.dayOfWeek.value
        val periods = local.getPeriodConfigsFor(timetable.id).associateBy { it.period }
        val courses = local.getAllSlots(timetable.id)
            .filter { it.dayOfWeek == day && WeekExpressionParser.parse(it.rawWeekText, timetable.totalWeeks).weeks.contains(week) }
            .sortedBy { it.startPeriod }
        return WidgetData(timetable, week, day, courses, periods)
    }

    private fun updateWidget(context: Context, manager: AppWidgetManager, id: Int, data: WidgetData) {
        val views = RemoteViews(context.packageName, R.layout.widget_timetable)
        views.setOnClickPendingIntent(
            R.id.widget_root,
            PendingIntent.getActivity(
                context,
                id,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        views.removeAllViews(R.id.widget_courses)
        if (data.timetable == null) {
            views.setTextViewText(R.id.widget_title, "Awake 课表")
            views.setTextViewText(R.id.widget_subtitle, "还没有本地课表")
            views.setTextViewText(R.id.widget_empty, "打开 App 导入或创建课表")
            manager.updateAppWidget(id, views)
            return
        }

        views.setTextViewText(R.id.widget_title, "第 ${data.week} 周 · ${dayLabel(data.day)}")
        views.setTextViewText(R.id.widget_subtitle, data.timetable.label)
        views.setTextViewText(R.id.widget_empty, if (data.courses.isEmpty()) "今天没有课程" else "")
        data.courses.take(MAX_COURSES).forEach { course ->
            val item = RemoteViews(context.packageName, R.layout.widget_course_item)
            item.setTextViewText(R.id.widget_course_name, course.name)
            item.setTextViewText(R.id.widget_course_meta, buildMeta(course, data.periods))
            views.addView(R.id.widget_courses, item)
        }
        if (data.courses.size > MAX_COURSES) {
            views.setTextViewText(R.id.widget_empty, "还有 ${data.courses.size - MAX_COURSES} 门课程 · 点击查看")
        }
        manager.updateAppWidget(id, views)
    }

    private fun buildMeta(course: CourseSlotEntity, periods: Map<Int, PeriodConfigEntity>): String = buildString {
        append("第 ").append(course.startPeriod)
        if (course.endPeriod != course.startPeriod) append("-").append(course.endPeriod)
        append(" 节")
        periods[course.startPeriod]?.startTime?.let { append(" · ").append(it) }
        if (course.room.isNotBlank()) append(" · ").append(course.room)
    }

    private fun dayLabel(day: Int): String = listOf("一", "二", "三", "四", "五", "六", "日").getOrElse(day - 1) { "?" }

    private data class WidgetData(
        val timetable: TimetableEntity?,
        val week: Int = 1,
        val day: Int = 1,
        val courses: List<CourseSlotEntity> = emptyList(),
        val periods: Map<Int, PeriodConfigEntity> = emptyMap()
    ) {
        companion object { fun empty() = WidgetData(null) }
    }

    private companion object { const val MAX_COURSES = 4 }
}
