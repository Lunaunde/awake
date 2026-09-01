package com.example.awake.data.notification

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.awake.AwakeApplication
import com.example.awake.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 闹钟触发后从 Room 读取最新课程，课程被删除或提醒关闭时直接忽略。 */
class CourseReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderNotificationContract.ACTION_COURSE_REMINDER) return
        val pendingResult = goAsync()
        val app = context.applicationContext as? AwakeApplication
        if (app == null) {
            pendingResult.finish()
            return
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val timetableId = intent.getLongExtra(ReminderNotificationContract.EXTRA_TIMETABLE_ID, -1L)
                // EXTRA_COURSE_ID 携带的是触发提醒的时段 id（CourseSectionEntity.id）。
                val sectionId = intent.getLongExtra(ReminderNotificationContract.EXTRA_COURSE_ID, -1L)
                val minutesBefore = intent.getIntExtra(ReminderNotificationContract.EXTRA_MINUTES_BEFORE, 15)
                if (timetableId <= 0L || sectionId <= 0L) return@launch
                val settings = app.container.reminderSettingsStore.read()
                if (!settings.enabled || !NotificationChannels.canPostNotifications(context)) return@launch
                val slot = app.container.localRepository.getSlotOrNull(sectionId) ?: return@launch
                if (slot.timetableId != timetableId) return@launch
                val timetable = app.container.localRepository.getTimetableOrNull(timetableId) ?: return@launch
                val notification = NotificationCompat.Builder(context, NotificationChannels.REMINDER_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle("${slot.name} · 课前提醒")
                    .setContentText(buildContent(slot.teacher, slot.room, minutesBefore))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(buildContent(slot.teacher, slot.room, minutesBefore)))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .setContentIntent(contentIntent(context))
                    .build()
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                ) {
                    NotificationManagerCompat.from(context).notify(notificationId(timetable.id, slot.sectionId), notification)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun buildContent(teacher: String, room: String, minutesBefore: Int): String = buildString {
        append("还有 ").append(minutesBefore).append(" 分钟")
        if (room.isNotBlank()) append(" · ").append(room)
        if (teacher.isNotBlank()) append(" · ").append(teacher)
    }

    private fun contentIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun notificationId(timetableId: Long, sectionId: Long): Int =
        "${timetableId}:$sectionId".hashCode() and 0x7fffffff
}

/** 设备重启或应用更新后恢复当前课表的未来提醒。 */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pendingResult = goAsync()
        val app = context.applicationContext as? AwakeApplication
        if (app == null) {
            pendingResult.finish()
            return
        }
        app.container.applicationScope.launch {
            try {
                app.container.reminderCoordinator.rescheduleSelected()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
