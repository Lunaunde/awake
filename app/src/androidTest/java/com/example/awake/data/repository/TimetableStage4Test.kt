package com.example.awake.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.awake.data.local.AppDatabase
import com.example.awake.data.local.CourseEntity
import com.example.awake.data.local.CourseSectionEntity
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
import com.example.awake.domain.model.CourseIdentity

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

    private fun manualMaster(timetableId: Long, key: String, name: String) = CourseEntity(
        timetableId = timetableId,
        source = "MANUAL",
        remoteKey = key,
        name = name,
        teacher = "老师"
    )

    private fun manualSection(courseIdIndex: Long, key: String) = CourseSectionEntity(
        courseId = courseIdIndex,
        source = "MANUAL",
        remoteKey = "$key-slot",
        dayOfWeek = 1,
        startPeriod = 1,
        endPeriod = 2
    )

    @Test
    fun coursesFromDifferentTermsStayIsolatedOffline() = runBlocking {
        val profile = local.ensureProfile()
        val first = local.findOrCreateTimetable(profile.id, 2025, "3", "2025-2026 第一学期")
        val second = local.findOrCreateTimetable(profile.id, 2026, "3", "2026-2027 第一学期")
        local.insertManualCourse(manualMaster(first.id, "m-first", "第一学期课程"), manualSection(0, "m-first"), setOf(1))
        local.insertManualCourse(manualMaster(second.id, "m-second", "第二学期课程"), manualSection(0, "m-second"), setOf(1))

        assertEquals(listOf("第一学期课程"), local.observeCourses(first.id, 1).first().map { it.name })
        assertEquals(listOf("第二学期课程"), local.observeCourses(second.id, 1).first().map { it.name })
    }

    @Test
    fun failedRemoteReplacementRollsBackAndKeepsOldCourses() = runBlocking {
        val profile = local.ensureProfile()
        val timetable = local.findOrCreateTimetable(profile.id, 2026, "3", "测试课表")
        val oldMaster = CourseEntity(
            timetableId = timetable.id, source = "SCUT_KB", remoteKey = "m-old",
            name = "旧课程"
        )
        val oldSection = CourseSectionEntity(
            courseId = 0, source = "SCUT_KB", remoteKey = "old",
            dayOfWeek = 1, startPeriod = 1, endPeriod = 2
        )
        local.replaceRemoteCourses(timetable, listOf(oldMaster), listOf(oldSection), listOf(CourseWeekEntity(0, 1)))
        val original = local.observeCourses(timetable.id, 1).first().single()

        val duplicateA = oldSection.copy(id = 0, remoteKey = "duplicate")
        val duplicateB = oldSection.copy(id = 0, remoteKey = "duplicate")
        runCatching {
            local.replaceRemoteCourses(
                timetable,
                listOf(oldMaster.copy(id = 0, remoteKey = "m-duplicate", name = "新课程")),
                listOf(duplicateA, duplicateB),
                listOf(CourseWeekEntity(0, 1), CourseWeekEntity(1, 1))
            )
        }.onSuccess { error("重复时段键应触发事务失败") }

        val afterFailure = local.observeCourses(timetable.id, 1).first().single()
        assertEquals(original.sectionId, afterFailure.sectionId)
        assertEquals("旧课程", afterFailure.name)
        assertTrue(afterFailure.rawWeekText.isNotBlank())
    }

    @Test
    fun sameNameRemoteCoursesGroupIntoOneMaster() = runBlocking {
        val profile = local.ensureProfile()
        val timetable = local.findOrCreateTimetable(profile.id, 2026, "3", "合并测试")
        val cookies = SessionCookieStore().also {
            it.put(server.url("/").host, "/jwglxt", "JSESSIONID", "stage4-merge")
        }
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"kbList":[
                  {"kcmc":"软件体系结构","xm":"邓紫坤","cdmc":"A1503","xqj":"3","jcs":"3-4","zcd":"1-16"},
                  {"kcmc":"软件体系结构","xm":"邓紫坤","cdmc":"A4101","xqj":"4","jcs":"3-4","zcd":"1-16"}
                ]}
                """.trimIndent()
            )
        )
        val client = ScutJwClient(cookies, baseUrl = server.url("/").toString().toHttpUrl())
        val remote = ScutScheduleRepository(local, client, ScutScheduleMapper())
        remote.import(timetable.id)

        val slots = local.getAllSlots(timetable.id)
        assertEquals(2, slots.size)
        assertEquals(1, slots.map { it.courseId }.distinct().size)
        // 同一门课的多个时段颜色一致（由主记录下发）。
        assertEquals(slots.first().color, slots.last().color)
        assertEquals("软件体系结构", slots.first().name)
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

    @Test
    fun manualReAddConvergesToExistingMasterAndSlot() = runBlocking {
        val profile = local.ensureProfile()
        val timetable = local.findOrCreateTimetable(profile.id, 2026, "3", "收敛测试")
        val first = local.insertManualCourse(
            manualMaster(timetable.id, CourseIdentity.masterKey("MANUAL", "手工课", null, "老师"), "手工课"),
            manualSection(0, CourseIdentity.masterKey("MANUAL", "手工课", null, "老师")), setOf(1)
        )
        val second = local.insertManualCourse(
            manualMaster(timetable.id, CourseIdentity.masterKey("MANUAL", "手工课", null, "老师"), "手工课"),
            manualSection(0, CourseIdentity.masterKey("MANUAL", "手工课", null, "老师")), setOf(1, 2)
        )
        assertEquals(first, second)
        assertEquals(1, local.getAllSlots(timetable.id).size)
        assertEquals(listOf(1, 2), database.courseDao().getWeeks(local.getAllSlots(timetable.id).single().sectionId).map { it.weekNumber })
    }
}