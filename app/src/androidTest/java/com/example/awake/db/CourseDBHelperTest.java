package com.example.awake.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * 在 Android 模拟器或真机上测试 CourseDBHelper 的增删改查。
 */
@RunWith(AndroidJUnit4.class)
public class CourseDBHelperTest {

    private static final String TAG = "DB_TEST";

    private Context context;
    private CourseDBHelper dbHelper;

    @Before
    public void setUp() {
        context = InstrumentationRegistry
                .getInstrumentation()
                .getTargetContext();

        // 每次测试前清空旧数据库，保证测试结果不受上次运行影响
        dbHelper = CourseDBHelper.getInstance(context);
        dbHelper.close();
        context.deleteDatabase("course.db");
        dbHelper = CourseDBHelper.getInstance(context);
    }

    @After
    public void tearDown() {
        if (dbHelper != null) {
            dbHelper.close();
        }
        context.deleteDatabase("course.db");
    }

    @Test
    public void testCourseDatabaseCrud() {
        // 1. 插入课程
        Course course = new Course(
                "高等数学",
                "张老师",
                "A101",
                "1,3,5",
                1,
                1,
                2,
                0xFF2196F3
        );

        long id = dbHelper.insertCourse(course);
        Log.d(TAG, "1. 插入课程：id = " + id);
        assertTrue("插入课程失败，返回的 id 应该大于 0", id > 0);

        // 2. 查询课程
        List<Course> courses = dbHelper.getCoursesByDayAndWeek(1, 1);
        Log.d(TAG, "2. 查询课程：数量 = " + courses.size());
        assertEquals("应该查询到 1 条课程", 1, courses.size());

        Course savedCourse = courses.get(0);
        assertEquals(id, savedCourse.getId());
        assertEquals("高等数学", savedCourse.getName());
        assertEquals("张老师", savedCourse.getTeacher());
        assertEquals("A101", savedCourse.getRoom());
        assertEquals("1,3,5", savedCourse.getWeekConfig());
        assertEquals(1, savedCourse.getDay());
        assertEquals(1, savedCourse.getStart());
        assertEquals(2, savedCourse.getEnd());

        // 3. 更新课程
        Course updatedCourse = new Course(
                id,
                "线性代数",
                "李老师",
                "B202",
                "1,3,5",
                1,
                3,
                4,
                0xFFF44336
        );

        int updateCount = dbHelper.updateCourse(updatedCourse);
        Log.d(TAG, "3. 修改课程：影响行数 = " + updateCount);
        assertEquals("应该更新 1 条课程", 1, updateCount);

        List<Course> updatedCourses = dbHelper.getCoursesByDayAndWeek(1, 1);
        assertEquals(1, updatedCourses.size());
        assertEquals("线性代数", updatedCourses.get(0).getName());
        assertEquals("李老师", updatedCourses.get(0).getTeacher());
        assertEquals(3, updatedCourses.get(0).getStart());
        assertEquals(4, updatedCourses.get(0).getEnd());

        // 4. 删除课程
        int deleteCount = dbHelper.deleteCourse(id);
        Log.d(TAG, "4. 删除课程：影响行数 = " + deleteCount);
        assertEquals("应该删除 1 条课程", 1, deleteCount);

        List<Course> remainingCourses = dbHelper.getCoursesByDayAndWeek(1, 1);
        assertTrue("删除后不应该再查询到课程", remainingCourses.isEmpty());
        Log.d(TAG, "5. 测试完成：删除后剩余课程数量 = " + remainingCourses.size());
    }
}


