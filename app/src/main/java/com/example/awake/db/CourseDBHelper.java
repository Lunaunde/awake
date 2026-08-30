package com.example.awake.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

/**
 * @deprecated 正式流程已迁移到 Room 的 AppDatabase；仅作为旧版本数据读取兼容层保留。
 */
@Deprecated
public class CourseDBHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "course.db";
    private static final int DB_VERSION = 2;
    private static final String TABLE_NAME = "courses";

    // 单例模式（防止多次打开数据库造成资源浪费）
    private static CourseDBHelper instance;

    public static synchronized CourseDBHelper getInstance(Context context) {
        if (instance == null) {
            instance = new CourseDBHelper(context.getApplicationContext());
        }
        return instance;
    }

    private CourseDBHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.enableWriteAheadLogging();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // 建表
        String createTable = "CREATE TABLE " + TABLE_NAME + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +
                "teacher TEXT," +
                "room TEXT," +
                "week_config TEXT NOT NULL," +
                "day INTEGER NOT NULL," +
                "start INTEGER NOT NULL," +
                "end INTEGER NOT NULL," +
                "color INTEGER DEFAULT 0" +
                ")";
        db.execSQL(createTable);
        // 建立索引
        db.execSQL("CREATE INDEX idx_day_start ON " + TABLE_NAME + "(day, start)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 开发阶段简单粗暴：升级时删表重建（发布后切记写迁移逻辑）
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
        onCreate(db);
    }

    // ---------- 增 (Insert) ----------
    public long insertCourse(Course course) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("name", course.getName());
        values.put("teacher", course.getTeacher());
        values.put("room", course.getRoom());
        values.put("week_config", course.getWeekConfig());
        values.put("day", course.getDay());
        values.put("start", course.getStart());
        values.put("end", course.getEnd());
        values.put("color", course.getColor());
        return db.insert(TABLE_NAME, null, values);
    }

    // ---------- 删 (Delete) ----------
    public int deleteCourse(long id) {
        SQLiteDatabase db = getWritableDatabase();
        return db.delete(TABLE_NAME, "id = ?", new String[]{String.valueOf(id)});
    }

    // ---------- 改 (Update) ----------
    public int updateCourse(Course course) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("name", course.getName());
        values.put("teacher", course.getTeacher());
        values.put("room", course.getRoom());
        values.put("week_config", course.getWeekConfig());
        values.put("day", course.getDay());
        values.put("start", course.getStart());
        values.put("end", course.getEnd());
        values.put("color", course.getColor());
        return db.update(TABLE_NAME, values, "id = ?", new String[]{String.valueOf(course.getId())});
    }

    // ---------- 查 (Query) ：查询某天某周的课程 ----------
    public List<Course> getCoursesByDayAndWeek(int day, int currentWeek) {
        List<Course> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;

        try {
            String weekStr = String.valueOf(currentWeek);
            // 注意：SQLite的 rawQuery 第二个参数是 String[]，用来替换 ? 占位符
            String sql = "SELECT * FROM " + TABLE_NAME +
                    " WHERE day = ? AND (" +
                    " week_config = 'all' OR " +
                    " week_config LIKE ? OR " +
                    " week_config LIKE ? OR " +
                    " week_config LIKE ? OR " +
                    " week_config = ?" +
                    ") ORDER BY start ASC";

            // 参数数组对应上面的 5 个 ?
            String[] args = new String[]{
                    String.valueOf(day),
                    "%," + weekStr + ",%",
                    weekStr + ",%",
                    "%," + weekStr,
                    weekStr
            };

            cursor = db.rawQuery(sql, args);

            // 先确认查询结果中存在这些字段
            int idIndex = cursor.getColumnIndexOrThrow("id");
            int nameIndex = cursor.getColumnIndexOrThrow("name");
            int teacherIndex = cursor.getColumnIndexOrThrow("teacher");
            int roomIndex = cursor.getColumnIndexOrThrow("room");
            int weekConfigIndex = cursor.getColumnIndexOrThrow("week_config");
            int dayIndex = cursor.getColumnIndexOrThrow("day");
            int startIndex = cursor.getColumnIndexOrThrow("start");
            int endIndex = cursor.getColumnIndexOrThrow("end");
            int colorIndex = cursor.getColumnIndexOrThrow("color");

            while (cursor.moveToNext()) {
                Course course = new Course(
                        cursor.getLong(idIndex),
                        cursor.getString(nameIndex),
                        cursor.getString(teacherIndex),
                        cursor.getString(roomIndex),
                        cursor.getString(weekConfigIndex),
                        cursor.getInt(dayIndex),
                        cursor.getInt(startIndex),
                        cursor.getInt(endIndex),
                        cursor.getInt(colorIndex)
                );
                list.add(course);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // 【Java核心要点】Cursor必须手动关闭，否则内存泄漏！
            if (cursor != null) {
                cursor.close();
            }
        }
        return list;
    }
}

