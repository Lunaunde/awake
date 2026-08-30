package com.example.awake.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.awake.data.local.AppDatabase
import com.example.awake.data.local.CourseEntity
import com.example.awake.data.local.CourseWeekEntity
import com.example.awake.data.mapper.ScutScheduleMapper
import com.example.awake.data.remote.ScutJwClient
import com.example.awake.data.remote.SessionCookieStore
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class TimetableStage4Test {
    private lateinit var database: AppDatabase
    private lateinit var local: LocalTimetableRepository
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        local = LocalTimetableRepository(database)
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        database.close()
    }

    @Test
    fun coursesFromDifferentTermsStayIsolatedOffline() = runBlocking {
        val profile = local.ensureProfile()
        val first = local.findOrCreateTimetable(profile.id, 2025, "3", "2025-2026 第一学期")
        val second = local.findOrCreateTimetable(profile.id, 2026, "3", "2026-2027 第一学期")
        local.insertManualCourse(course(first.id, "first", "第一学期课程"), setOf(1))
        local.insertManualCourse(course(second.id, "second", "第二学期课程"), setOf(1))

        assertEquals(listOf("第一学期课程"), local.observeCourses(first.id, 1).first().map { it.name })
        assertEquals(listOf("第二学期课程"), local.observeCourses(second.id, 1).first().map { it.name })
    }

    @Test
    fun failedRemoteReplacementRollsBackAndKeepsOldCourses() = runBlocking {
        val profile = local.ensureProfile()
        val timetable = local.findOrCreateTimetable(profile.id, 2026, "3", "测试课表")
        val old = CourseEntity(
            timetableId = timetable.id, source = "SCUT_KB", remoteKey = "old",
            name = "旧课程", dayOfWeek = 1, startPeriod = 1, endPeriod = 2
        )
        local.replaceRemoteCourses(timetable, listOf(old), listOf(CourseWeekEntity(0, 1)))
        val original = local.observeCourses(timetable.id, 1).first().single()

        val duplicateA = old.copy(id = 0, remoteKey = "duplicate", name = "新课程A")
        val duplicateB = old.copy(id = 0, remoteKey = "duplicate", name = "新课程B")
        runCatching {
            local.replaceRemoteCourses(
                timetable,
                listOf(duplicateA, duplicateB),
                listOf(CourseWeekEntity(0, 1), CourseWeekEntity(1, 1))
            )
        }.onSuccess { error("重复 remoteKey 应触发事务失败") }

        val afterFailure = local.observeCourses(timetable.id, 1).first().single()
        assertEquals(original.id, afterFailure.id)
        assertEquals("旧课程", afterFailure.name)
        assertEquals("old", afterFailure.remoteKey)
    }

    @Test
    fun sameTimetableImportsAreSingleFlight() = runBlocking {
        val profile = local.ensureProfile()
        val timetable = local.findOrCreateTimetable(profile.id, 2026, "3", "并发测试")
        val cookies = SessionCookieStore().also {
            it.put(server.url("/").host, "/jwglxt", "JSESSIONID", "stage4")
        }
        val calls = AtomicInteger(0)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (calls.incrementAndGet() == 1) {
                    firstStarted.countDown()
                    check(releaseFirst.await(5, TimeUnit.SECONDS))
                }
                return MockResponse().setResponseCode(200).setBody(
                    """{"kbList":[{"kcmc":"并发课程","xqj":"1","jcs":"1-2","zcd":"1-16"}]}"""
                )
            }
        }
        val client = ScutJwClient(cookies, baseUrl = server.url("/").toString().toHttpUrl())
        val remote = ScutScheduleRepository(local, client, ScutScheduleMapper())

        val first = launch(kotlinx.coroutines.Dispatchers.IO) { remote.import(timetable.id) }
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
        val second = async(kotlinx.coroutines.Dispatchers.IO) { remote.import(timetable.id) }
        delay(100)
        assertEquals("第二次请求应等待第一次完成", 1, calls.get())
        releaseFirst.countDown()
        first.join()
        second.await()
        assertEquals(2, calls.get())
    }

    private fun course(timetableId: Long, key: String, name: String) = CourseEntity(
        timetableId = timetableId,
        source = "MANUAL",
        remoteKey = key,
        name = name,
        dayOfWeek = 1,
        startPeriod = 1,
        endPeriod = 2
    )
}
