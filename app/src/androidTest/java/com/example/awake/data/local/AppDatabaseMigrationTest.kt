package com.example.awake.data.local

import android.content.ContentValues
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    companion object {
        private const val DATABASE_NAME = "migration-test.db"
        private val ALL_MIGRATIONS = arrayOf(
            AppDatabase.LEGACY_MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6
        )
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @After
    fun tearDown() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    @Throws(IOException::class)
    fun migrateLegacyCoursesAndExpandWeeks() {
        helper.createDatabase(DATABASE_NAME, 2).use { legacyDb ->
            legacyDb.insert("courses", 0, ContentValues().apply {
                put("name", "高等数学")
                put("teacher", "张老师")
                put("room", "A101")
                put("week_config", "1-3")
                put("day", 1)
                put("start", 2)
                put("end", 3)
                put("color", 0xFF2196F3.toInt())
            })
            legacyDb.insert("courses", 0, ContentValues().apply {
                putNull("name")
                putNull("teacher")
                putNull("room")
                put("week_config", "待定")
                putNull("day")
                putNull("start")
                putNull("end")
                putNull("color")
            })
        }

        val database = Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        try {
            val migratedDb = database.openHelper.writableDatabase
            assertEquals(1, scalar(migratedDb, "SELECT COUNT(*) FROM profiles"))
            assertEquals(1, scalar(migratedDb, "SELECT COUNT(*) FROM timetables WHERE xnm = 0 AND xqm = 'legacy'"))
            // v5: 每条历史记录迁移为「一个主课程 + 一个时段」；两条名称不同 → 两个主课程。
            assertEquals(2, scalar(migratedDb, "SELECT COUNT(*) FROM courses WHERE source = 'MIGRATED_LEGACY'"))
            assertEquals(2, scalar(migratedDb, "SELECT COUNT(*) FROM course_sections"))

            migratedDb.query(
                """
                SELECT c.name, c.teacher, s.room, s.dayOfWeek, s.startPeriod, s.endPeriod, c.color, s.rawWeekText
                FROM courses c JOIN course_sections s ON s.courseId = c.id
                WHERE c.name = '高等数学'
                """.trimIndent()
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("高等数学", cursor.getString(0))
                assertEquals("张老师", cursor.getString(1))
                assertEquals("A101", cursor.getString(2))
                assertEquals(1, cursor.getInt(3))
                assertEquals(2, cursor.getInt(4))
                assertEquals(3, cursor.getInt(5))
                assertEquals(0xFF2196F3.toInt(), cursor.getInt(6))
                assertEquals("1-3", cursor.getString(7))
            }

            runBlocking { LegacyCourseImporter(database).expandMissingWeeks() }
            migratedDb.query(
                """
                SELECT s.startPeriod FROM course_weeks w
                JOIN course_sections s ON s.id = w.sectionId
                JOIN courses c ON c.id = s.courseId
                WHERE c.name = '高等数学'
                ORDER BY w.weekNumber
                """.trimIndent()
            ).use { cursor ->
                // 三个周次都展开到同一个时段上。
                assertTrue(cursor.moveToFirst())
                assertEquals(3, cursor.count)
                assertEquals(2, cursor.getInt(0))
            }
        } finally {
            database.close()
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrate45MergesSameNameCoursesIntoOneMaster() {
        helper.createDatabase(DATABASE_NAME, 4).use { v4 ->
            // 同名同教师（无教学班号）的两行 → 应合并为一个主课程 + 两个时段。
            val firstId = insertV4Course(v4, remoteKey = "kb-a", room = "A1503", day = 3)
            val secondId = insertV4Course(v4, remoteKey = "kb-b", room = "A4101", day = 4)
            v4.insert("course_weeks", 0, ContentValues().apply {
                put("courseId", firstId)
                put("weekNumber", 2)
            })
            v4.insert("course_weeks", 0, ContentValues().apply {
                put("courseId", firstId)
                put("weekNumber", 4)
            })
            v4.insert("course_weeks", 0, ContentValues().apply {
                put("courseId", secondId)
                put("weekNumber", 2)
            })
        }

        val database = Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        try {
            val db = database.openHelper.writableDatabase
            assertEquals(1, scalar(db, "SELECT COUNT(*) FROM courses WHERE name = '软件体系结构'"))
            assertEquals(2, scalar(db, "SELECT COUNT(*) FROM course_sections"))
            // 教务来源下同一门课的教室差异保留在时段上。
            db.query("SELECT room FROM course_sections ORDER BY dayOfWeek").use { cursor ->
                cursor.moveToFirst()
                assertEquals("A1503", cursor.getString(0))
                cursor.moveToNext()
                assertEquals("A4101", cursor.getString(0))
            }
            // 周次关系从旧课程行搬到对应时段行。
            assertEquals(2, scalar(db, "SELECT COUNT(*) FROM course_weeks WHERE weekNumber = 2"))
            assertEquals(1, scalar(db, "SELECT COUNT(*) FROM course_weeks WHERE weekNumber = 4"))
            assertEquals(3, scalar(db, "SELECT COUNT(*) FROM course_weeks"))
        } finally {
            database.close()
        }
    }

    private fun insertV4Course(db: SupportSQLiteDatabase, remoteKey: String, room: String, day: Int): Long =
        db.insert("courses", 0, ContentValues().apply {
            put("timetableId", 1L)
            put("source", "SCUT_KB")
            put("remoteKey", remoteKey)
            put("name", "软件体系结构")
            put("teacher", "邓紫坤")
            put("room", room)
            put("dayOfWeek", day)
            put("startPeriod", 3)
            put("endPeriod", 4)
            put("color", -1)
            put("rawWeekText", "1-16")
        })

    private fun scalar(db: SupportSQLiteDatabase, sql: String): Int =
        db.query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }
}