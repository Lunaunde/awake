package com.example.awake

import android.app.Application
import android.content.pm.ApplicationInfo
import android.webkit.WebView
import androidx.room.Room
import com.example.awake.data.local.AppDatabase
import com.example.awake.data.local.LegacyCourseImporter
import com.example.awake.data.local.PeriodConfigDefaults
import com.example.awake.data.mapper.ScutScheduleMapper
import com.example.awake.data.notification.AndroidReminderScheduler
import com.example.awake.data.notification.NotificationChannels
import com.example.awake.data.remote.CasWebViewCoordinator
import com.example.awake.data.remote.SchoolAdapterRegistry
import com.example.awake.data.remote.ScutAuthRepository
import com.example.awake.data.remote.ScutJwClient
import com.example.awake.data.remote.SessionCookieStore
import com.example.awake.data.repository.LocalTimetableRepository
import com.example.awake.data.repository.ReminderCoordinator
import com.example.awake.data.repository.ReminderSettingsStore
import com.example.awake.data.repository.TimetableSelectionStore
import com.example.awake.data.repository.ScutScheduleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AwakeApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        container = AppContainer(this)
        NotificationChannels.ensureReminderChannel(this)
        container.applicationScope.launch {
            container.legacyImporter.expandMissingWeeks()
            container.reminderCoordinator.rescheduleSelected()
        }
    }
}

class AppContainer(context: android.content.Context) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database: AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "course.db")
        .addMigrations(AppDatabase.LEGACY_MIGRATION_2_3)
        .addMigrations(AppDatabase.MIGRATION_3_4)
        .build()
    val cookieStore = SessionCookieStore()
    val casCoordinator = CasWebViewCoordinator(cookieStore)
    val authRepository = ScutAuthRepository(cookieStore, casCoordinator)
    val localRepository = LocalTimetableRepository(database)
    val reminderSettingsStore = ReminderSettingsStore(context)
    val timetableSelectionStore = TimetableSelectionStore(context)
    val reminderScheduler = AndroidReminderScheduler(context)
    val reminderCoordinator = ReminderCoordinator(localRepository, reminderScheduler, reminderSettingsStore, timetableSelectionStore)
    val schoolAdapterRegistry = SchoolAdapterRegistry()
    val scutRepository = ScutScheduleRepository(
        local = localRepository,
        client = ScutJwClient(cookieStore),
        mapper = ScutScheduleMapper(),
        adapters = schoolAdapterRegistry
    )
    val legacyImporter = LegacyCourseImporter(database)

    init {
        applicationScope.launch {
            val dao = database.periodConfigDao()
            val current = dao.getAll()
            val defaults = PeriodConfigDefaults.entities()

            when {
                current.isEmpty() -> dao.insertAll(defaults)
                current.size >= 12 && current.take(12).all { config ->
                    val period = config.period.coerceIn(1, PeriodConfigDefaults.periodCount)
                    val oldStart = 8 * 60 + (period - 1) * 50 + if (period > 4) 10 else 0
                    val oldEnd = oldStart + 45
                    config.startTime == "%02d:%02d".format(oldStart / 60, oldStart % 60) &&
                        config.endTime == "%02d:%02d".format(oldEnd / 60, oldEnd % 60)
                } -> {
                    dao.insertAll(defaults)
                    dao.deleteAfter(PeriodConfigDefaults.periodCount)
                }
                else -> {
                    // 保留用户已经修改过的时间，只补齐缺失的默认节次并移除旧的第 12 节。
                    val existingPeriods = current.map { it.period }.toSet()
                    dao.insertAll(defaults.filterNot { it.period in existingPeriods })
                    dao.deleteAfter(PeriodConfigDefaults.periodCount)
                }
            }
        }
    }
}
