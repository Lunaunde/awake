package com.example.awake

import android.app.Application
import androidx.room.Room
import com.example.awake.data.local.AppDatabase
import com.example.awake.data.local.LegacyCourseImporter
import com.example.awake.data.mapper.ScutScheduleMapper
import com.example.awake.data.remote.CasWebViewCoordinator
import com.example.awake.data.remote.ScutJwClient
import com.example.awake.data.remote.SessionCookieStore
import com.example.awake.data.repository.LocalTimetableRepository
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
        container = AppContainer(this)
        container.applicationScope.launch { container.legacyImporter.expandMissingWeeks() }
    }
}

class AppContainer(context: android.content.Context) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database: AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "course.db")
        .addMigrations(AppDatabase.LEGACY_MIGRATION_2_3)
        .fallbackToDestructiveMigrationOnDowngrade()
        .build()
    val cookieStore = SessionCookieStore()
    val casCoordinator = CasWebViewCoordinator(cookieStore)
    val localRepository = LocalTimetableRepository(database)
    val scutRepository = ScutScheduleRepository(localRepository, ScutJwClient(cookieStore), ScutScheduleMapper())
    val legacyImporter = LegacyCourseImporter(database)
}
