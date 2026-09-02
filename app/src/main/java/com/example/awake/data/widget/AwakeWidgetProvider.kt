package com.example.awake.data.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.example.awake.AwakeApplication
import com.example.awake.MainActivity
import com.example.awake.R
import com.example.awake.data.repository.LocalTimetableRepository
import com.example.awake.data.repository.TimetableSelectionStore
import com.example.awake.ui.widget.AwakeWidgetConfigActivity
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 周课表小组件：只读本地 Room，不访问网络。
 *
 * - 头部固定：【课表】【‹】【›】按钮 + 课表名/周次 + 7 天表头（星期字 + 日期号，
 *   查看本周时今天列用主题主色加深并显示圆点徽标，与 App 内一致）；
 * - 主体为 ListView 集合组件（RemoteViewsService 提供每节一行），
 *   上下滑动浏览全部节次是系统级原生支持；
 * - 【‹】【›】切换周次（增量刷新头部 + 集合视图重载，并延时重断言，
 *   防御个别桌面用旧快照重绘导致的周次回跳）；【课表】重新选择绑定课表；
 * - 7 列等宽自适应，组件宽高均可调整；
 * - 配色与深浅模式全部经 [WidgetPalette] 与 App 内同源（含 A12+ 动态取色）。
 */
class AwakeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // 串行化渲染：避免与其它组件/周次切换的渲染交错（读-改-写竞争导致周信息错乱）。
                updateMutex.withLock {
                    appWidgetIds.forEach { widgetId -> buildAndUpdate(context, manager, widgetId) }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val prefs = AwakeWidgetPrefs(context)
        appWidgetIds.forEach(prefs::clear)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val delta = when (intent.action) {
            ACTION_WEEK_PREV -> -1
            ACTION_WEEK_NEXT -> 1
            else -> null
        } ?: run {
            super.onReceive(context, intent)
            return
        }
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        if (widgetId < 0) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // 周次自增与渲染整体串行化：连续快速点击 ‹ › 时每一步都基于最新周次，
                // 不会出现 setWeek 与 buildAndUpdate 交错造成的“标题与格子不一致”。
                updateMutex.withLock {
                    val app = context.applicationContext as? AwakeApplication
                    val local = app?.container?.localRepository
                    val prefs = AwakeWidgetPrefs(context)
                    val timetable = resolveTimetable(local, app?.container?.timetableSelectionStore, prefs, widgetId)
                    val totalWeeks = timetable?.totalWeeks?.coerceIn(1, 30) ?: 30
                    val baseWeek = prefs.week(widgetId).takeIf { it in 1..30 } ?: currentWeekOf(timetable)
                    prefs.setWeek(widgetId, (baseWeek + delta).coerceIn(1, totalWeeks))
                    buildAndUpdate(context, AppWidgetManager.getInstance(context), widgetId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun buildAndUpdate(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int
    ) {
        val app = context.applicationContext as? AwakeApplication ?: return
        val local = app.container.localRepository
        val prefs = AwakeWidgetPrefs(context)
        val timetable = resolveTimetable(local, app.container.timetableSelectionStore, prefs, widgetId)

        // 深浅判断与 App 内 ThemeModeStore 同源；主色/onSurface 在 A12+ 用动态取色。
        val dark = WidgetPalette.isDark(context)
        val onSurface = WidgetPalette.onSurface(context, dark)
        val primary = WidgetPalette.primary(context, dark)

        // 头部视图构建（周切换与系统更新共用同一份内容；setRemoteAdapter 始终携带，
        // 意图稳定不变——桌面按 intent 判等去重，不会引起列表重绑闪烁）。
        // adapter 绑定：data 携带 widgetId——RemoteViewsService 按 intent（filterEquals，含 data、
        // 不含 extras）缓存 Factory，不加 data 时多个组件会共用第一个 Factory（都显示第一个课表）。
        fun attachAdapter(views: RemoteViews) {
            views.setRemoteAdapter(
                R.id.widget_list,
                Intent(context, AwakeWidgetListService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    data = Uri.parse("awake://widget/$widgetId")
                }
            )
        }

        // 显式按深浅模式设置背景与文字颜色：XML 资源只在桌面 inflate 视图时解析一次，
        // App 内切换主题后不会跟随，必须由代码在每次更新时重新着色。
        fun applyChrome(views: RemoteViews) {
            val chipBg = if (dark) R.drawable.widget_chip_bg_night else R.drawable.widget_chip_bg_light
            views.setInt(
                R.id.widget_card,
                "setBackgroundResource",
                if (dark) R.drawable.widget_bg_night else R.drawable.widget_bg_light
            )
            views.setTextColor(R.id.widget_title, WidgetPalette.textPrimary(dark))
            views.setTextColor(R.id.widget_subtitle, WidgetPalette.textSecondary(dark))
            views.setTextColor(R.id.widget_rl_head, WidgetPalette.textSecondary(dark))
            views.setTextColor(R.id.widget_empty, WidgetPalette.textSecondary(dark))
            listOf(R.id.widget_btn_timetable, R.id.widget_btn_prev, R.id.widget_btn_next).forEach { id ->
                views.setTextColor(id, WidgetPalette.chipText(dark))
                views.setInt(id, "setBackgroundResource", chipBg)
            }
        }

        fun buildViews(): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_timetable)
            attachRootAndNavIntents(context, views, widgetId)

            if (timetable == null) {
                views.setTextViewText(R.id.widget_title, "Awake 课表")
                views.setTextViewText(R.id.widget_subtitle, "还没有本地课表")
                views.setTextViewText(R.id.widget_empty, "打开 App 导入或创建课表")
                views.setViewVisibility(R.id.widget_btn_prev, View.GONE)
                views.setViewVisibility(R.id.widget_btn_next, View.GONE)
                views.setViewVisibility(R.id.widget_btn_timetable, View.GONE)
                applyChrome(views)
                views.setEmptyView(R.id.widget_list, R.id.widget_empty)
                attachAdapter(views)
                return views
            }

            val totalWeeks = timetable.totalWeeks.coerceIn(1, 30)
            val storedWeek = prefs.week(widgetId)
            val week = if (storedWeek in 1..totalWeeks) storedWeek else {
                currentWeekOf(timetable).also { prefs.setWeek(widgetId, it) }
            }
            val isCurrentWeek = week == currentWeekOf(timetable)
            views.setTextViewText(R.id.widget_title, "第 $week 周 · ${timetable.label}")
            views.setTextViewText(
                R.id.widget_subtitle,
                weekRangeText(timetable, week) + if (isCurrentWeek) " · 本周" else ""
            )
            views.setTextViewText(R.id.widget_empty, "本周暂无课程")

            // 表头：星期字 + 当天日期号；查看本周时今天的列用主题主色加深，并显示圆点徽标
            // （对应 App 内表头的主题色文字 + 「今」圆形徽标）。查看其他周时全部为常规色。
            val todayDay = if (isCurrentWeek) LocalDate.now().dayOfWeek.value else 0
            val weekStart = runCatching { LocalDate.parse(timetable.startDate) }.getOrNull()
            headerIds.forEachIndexed { index, id ->
                val day = index + 1
                val isToday = day == todayDay
                views.setTextViewText(id, DAY_CHARS[index])
                views.setTextColor(id, if (isToday) primary else onSurface)
                if (weekStart != null) {
                    val date = weekStart.plusWeeks((week - 1).toLong()).plusDays((day - 1).toLong())
                    views.setTextViewText(headerNumIds[index], date.dayOfMonth.toString())
                } else {
                    views.setTextViewText(headerNumIds[index], "")
                }
                views.setTextColor(
                    headerNumIds[index],
                    if (isToday) primary else WidgetPalette.textSecondary(dark)
                )
                if (isToday) {
                    views.setViewVisibility(headerDotIds[index], View.VISIBLE)
                    views.setInt(headerDotIds[index], "setColorFilter", primary)
                } else {
                    views.setViewVisibility(headerDotIds[index], View.GONE)
                }
            }

            applyChrome(views)
            views.setEmptyView(R.id.widget_list, R.id.widget_empty)
            attachAdapter(views)
            return views
        }

        // 顺序很关键：先触发集合视图重载，再应用最新头部快照。
        // 部分桌面处理 viewDataChanged 时会用旧缓存重绘组件——若它发生在头部应用之后，
        // 刚显示的新周次会被旧周次覆盖（闪烁回跳/滞后一周）。
        // 把 notify 放在最前，这次“旧缓存重绘”先于头部应用发生且不可见，
        // 随后的头部应用就是最后一次变更，用户只看到一次干净的周次切换。
        manager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_list)
        manager.updateAppWidget(widgetId, buildViews())
    }

    private suspend fun resolveTimetable(
        local: LocalTimetableRepository?,
        selectionStore: TimetableSelectionStore?,
        prefs: AwakeWidgetPrefs,
        widgetId: Int
    ): com.example.awake.data.local.TimetableEntity? {
        val repository = local ?: return null
        return prefs.timetableId(widgetId)?.let { repository.getTimetableOrNull(it) }
            ?: selectionStore?.read()?.let { repository.getTimetableOrNull(it) }
            ?: repository.getFirstTimetable()
    }

    private fun attachRootAndNavIntents(context: Context, views: RemoteViews, widgetId: Int) {
        views.setOnClickPendingIntent(
            R.id.widget_root,
            PendingIntent.getActivity(
                context,
                widgetId,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        views.setOnClickPendingIntent(
            R.id.widget_btn_timetable,
            PendingIntent.getActivity(
                context,
                REQUEST_OFFSET + widgetId,
                Intent(context, AwakeWidgetConfigActivity::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        listOf(ACTION_WEEK_PREV to R.id.widget_btn_prev, ACTION_WEEK_NEXT to R.id.widget_btn_next)
            .forEachIndexed { index, (action, viewId) ->
                val intent = Intent(context, AwakeWidgetProvider::class.java).apply {
                    this.action = action
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                }
                views.setOnClickPendingIntent(
                    viewId,
                    PendingIntent.getBroadcast(
                        context,
                        REQUEST_OFFSET + 100 + widgetId * 2 + index,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }
    }

    /** 组件副标题：本周的起止日期，格式与 App 内头部日期一致（M/d）。 */
    private fun weekRangeText(
        timetable: com.example.awake.data.local.TimetableEntity,
        week: Int
    ): String = runCatching {
        val start = LocalDate.parse(timetable.startDate).plusWeeks((week - 1).toLong())
        val end = start.plusDays(6)
        "${start.monthValue}/${start.dayOfMonth} – ${end.monthValue}/${end.dayOfMonth}"
    }.getOrDefault("")

    private fun currentWeekOf(
        timetable: com.example.awake.data.local.TimetableEntity?
    ): Int {
        val startDate = timetable?.startDate ?: return 1
        return runCatching {
            ChronoUnit.WEEKS.between(LocalDate.parse(startDate), LocalDate.now()).toInt() + 1
        }.getOrNull()?.coerceIn(1, 30) ?: 1
    }

    private companion object {
        const val ACTION_WEEK_PREV = "com.example.awake.widget.WEEK_PREV"
        const val ACTION_WEEK_NEXT = "com.example.awake.widget.WEEK_NEXT"
        const val REQUEST_OFFSET = 9000

        /** 全局串行化组件渲染（周次自增 + buildAndUpdate + notify），防止并发交错。 */
        val updateMutex = Mutex()

        val DAY_CHARS = arrayOf("一", "二", "三", "四", "五", "六", "日")
        val headerIds = intArrayOf(
            R.id.widget_hd_1, R.id.widget_hd_2, R.id.widget_hd_3, R.id.widget_hd_4,
            R.id.widget_hd_5, R.id.widget_hd_6, R.id.widget_hd_7
        )
        val headerNumIds = intArrayOf(
            R.id.widget_hdn_1, R.id.widget_hdn_2, R.id.widget_hdn_3, R.id.widget_hdn_4,
            R.id.widget_hdn_5, R.id.widget_hdn_6, R.id.widget_hdn_7
        )
        val headerDotIds = intArrayOf(
            R.id.widget_hdt_1, R.id.widget_hdt_2, R.id.widget_hdt_3, R.id.widget_hdt_4,
            R.id.widget_hdt_5, R.id.widget_hdt_6, R.id.widget_hdt_7
        )
    }
}