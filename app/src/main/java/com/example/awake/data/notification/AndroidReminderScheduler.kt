package com.example.awake.data.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.awake.data.repository.Reminder
import com.example.awake.data.repository.ReminderScheduler
import java.time.ZoneId

/** 使用系统 AlarmManager 设置本地提醒，不需要上传账号或课表数据。 */
class AndroidReminderScheduler(context: Context) : ReminderScheduler {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun schedule(reminders: List<Reminder>) {
        val now = System.currentTimeMillis()
        val keys = preferences.getStringSet(KEY_ALARMS, emptySet()).orEmpty().toMutableSet()
        reminders.forEach { reminder ->
            val triggerAt = reminder.triggerAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (triggerAt <= now) return@forEach
            val key = alarmKey(reminder)
            alarmManager.setWakeup(triggerAt, pendingIntent(reminder, key))
            keys += key
        }
        preferences.edit().putStringSet(KEY_ALARMS, keys).apply()
    }

    override fun cancelForTimetable(timetableId: Long) {
        val keys = preferences.getStringSet(KEY_ALARMS, emptySet()).orEmpty().toMutableSet()
        val matching = keys.filter { it.startsWith("$timetableId:") }
        matching.forEach { key ->
            alarmManager.cancel(pendingIntentForKey(key))
            keys.remove(key)
        }
        preferences.edit().putStringSet(KEY_ALARMS, keys).apply()
    }

    override fun cancelAll() {
        val keys = preferences.getStringSet(KEY_ALARMS, emptySet()).orEmpty()
        keys.forEach { key -> alarmManager.cancel(pendingIntentForKey(key)) }
        preferences.edit().remove(KEY_ALARMS).apply()
    }

    private fun alarmKey(reminder: Reminder): String =
        "${reminder.timetableId}:${reminder.sectionId}:${reminder.triggerAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()}"

    private fun pendingIntent(reminder: Reminder, key: String): PendingIntent =
        PendingIntent.getBroadcast(
            appContext,
            key.hashCode(),
            Intent(appContext, CourseReminderReceiver::class.java).apply {
                action = ReminderNotificationContract.ACTION_COURSE_REMINDER
                putExtra(ReminderNotificationContract.EXTRA_TIMETABLE_ID, reminder.timetableId)
                putExtra(ReminderNotificationContract.EXTRA_COURSE_ID, reminder.sectionId)
                putExtra(ReminderNotificationContract.EXTRA_MINUTES_BEFORE, reminder.minutesBefore)
                putExtra(ReminderNotificationContract.EXTRA_ALARM_KEY, key)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun pendingIntentForKey(key: String): PendingIntent =
        PendingIntent.getBroadcast(
            appContext,
            key.hashCode(),
            Intent(appContext, CourseReminderReceiver::class.java).apply { action = ReminderNotificationContract.ACTION_COURSE_REMINDER },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: PendingIntent.getBroadcast(
            appContext,
            key.hashCode(),
            Intent(appContext, CourseReminderReceiver::class.java).apply { action = ReminderNotificationContract.ACTION_COURSE_REMINDER },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun AlarmManager.setWakeup(triggerAt: Long, operation: PendingIntent) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            else -> set(AlarmManager.RTC_WAKEUP, triggerAt, operation)
        }
    }

    private companion object {
        const val PREFS_NAME = "awake_reminder_alarms"
        const val KEY_ALARMS = "scheduled_alarm_keys"
    }
}
