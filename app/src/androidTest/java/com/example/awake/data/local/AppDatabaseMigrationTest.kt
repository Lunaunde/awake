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
            .addMigrations(AppDatabase.LEGACY_MIGRATION_2_3)
            .build()
        try {
            val migratedDb = database.openHelper.writableDatabase
            assertEquals(1, scalar(migratedDb, "SELECT COUNT(*) FROM profiles"))
            assertEquals(1, scalar(migratedDb, "SELECT COUNT(*) FROM timetables WHERE xnm = 0 AND xqm = 'legacy'"))
            assertEquals(2, scalar(migratedDb, "SELECT COUNT(*) FROM courses WHERE source = 'MIGRATED_LEGACY'"))

            migratedDb.query(
                "SELECT name, teacher, room, dayOfWeek, startPeriod, endPeriod, color, rawWeekText FROM courses WHERE id = 1"
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

            migratedDb.query(
                "SELECT name, teacher, room, dayOfWeek, startPeriod, endPeriod, color, rawWeekText FROM courses WHERE id = 2"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("", cursor.getString(0))
                assertEquals("", cursor.getString(1))
                assertEquals("", cursor.getString(2))
                assertEquals(1, cursor.getInt(3))
                assertEquals(1, cursor.getInt(4))
                assertEquals(1, cursor.getInt(5))
                assertEquals(0, cursor.getInt(6))
                assertEquals("待定", cursor.getString(7))
            }

            runBlocking { LegacyCourseImporter(database).expandMissingWeeks() }
            migratedDb.query(
                "SELECT weekNumber FROM course_weeks WHERE courseId = 1 ORDER BY weekNumber"
            ).use { cursor ->
                val weeks = buildList {
                    while (cursor.moveToNext()) add(cursor.getInt(0))
                }
                assertEquals(listOf(1, 2, 3), weeks)
            }
            assertEquals(0, scalar(migratedDb, "SELECT COUNT(*) FROM course_weeks WHERE courseId = 2"))
        } finally {
            database.close()
        }
    }

    private fun scalar(db: SupportSQLiteDatabase, sql: String): Int =
        db.query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }
}
