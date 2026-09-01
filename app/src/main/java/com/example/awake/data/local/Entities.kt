package com.example.awake.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val schoolCode: String = "SCUT",
    val maskedStudentId: String? = null,
    val displayName: String? = null,
    val lastLoginAt: Long? = null
)

@Entity(
    tableName = "timetables",
    foreignKeys = [ForeignKey(entity = ProfileEntity::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE)],
    // 同一学期允许保留多份课表，导入时由用户选择覆盖或新建。
    indices = [Index(value = ["profileId", "xnm", "xqm"])]
)
data class TimetableEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val schoolCode: String = "SCUT",
    val xnm: Int,
    val xqm: String,
    val label: String,
    val startDate: String? = null,
    val totalWeeks: Int = 20,
    val lastSyncedAt: Long? = null
)

/**
 * 课程主记录：一门课一行，记录与具体时段无关的身份信息。
 * remoteKey 为课程身份键（课程名 + 教学班号，缺失时退化为课程名 + 教师）。
 */
@Entity(
    tableName = "courses",
    foreignKeys = [ForeignKey(entity = TimetableEntity::class, parentColumns = ["id"], childColumns = ["timetableId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("timetableId"), Index(value = ["timetableId", "source", "remoteKey"], unique = true)]
)
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timetableId: Long,
    val source: String,
    val remoteKey: String = "",
    val name: String,
    val teacher: String = "",
    val credits: String? = null,
    val totalHours: String? = null,
    val courseType: String? = null,
    val assessment: String? = null,
    val className: String? = null,
    val color: Int = 0xff4f6bed.toInt()
)

/**
 * 课程时段：一门课的一个周时段（星期 + 起止节次 + 教室/教师覆盖 + 周次）。
 * remoteKey 为时段键，字段输入与 v4 的 courses.remoteKey 保持一致。
 */
@Entity(
    tableName = "course_sections",
    foreignKeys = [ForeignKey(entity = CourseEntity::class, parentColumns = ["id"], childColumns = ["courseId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("courseId"), Index(value = ["courseId", "source", "remoteKey"], unique = true)]
)
data class CourseSectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: Long,
    val source: String,
    val remoteKey: String = "",
    val dayOfWeek: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val room: String = "",
    /** 该时段的教师覆盖；为空时显示课程主教师。 */
    val teacher: String = "",
    val rawWeekText: String = "",
    /** 手动编辑过的时段打标，未来差量同步据此跳过，避免无提示覆盖。 */
    val locked: Boolean = false
)

/** 周次关系挂在时段上：同一门课不同时段可以有不同周次。 */
@Entity(
    tableName = "course_weeks",
    primaryKeys = ["sectionId", "weekNumber"],
    foreignKeys = [ForeignKey(entity = CourseSectionEntity::class, parentColumns = ["id"], childColumns = ["sectionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("weekNumber")]
)
data class CourseWeekEntity(val sectionId: Long, val weekNumber: Int)

/**
 * 周视图/提醒/导出/小组件共用的展示行：一门课的一个时段在某一课表中的扁平视图。
 * teacher 为时段教师覆盖与主教师的合并结果；由 DAO 查询直接生成。
 */
data class CourseSlotEntity(
    val sectionId: Long,
    val courseId: Long,
    val timetableId: Long,
    val source: String,
    val name: String,
    val teacher: String,
    val room: String,
    val dayOfWeek: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val color: Int,
    val rawWeekText: String,
    val locked: Boolean = false
)

/**
 * 节次时间。timetableId = 0 表示全局默认；每个课表可有一份独立配置，
 * 切换课表时段设置跟随课表（无独立配置的课表回退到全局默认）。
 */
@Entity(tableName = "period_configs", primaryKeys = ["timetableId", "period"])
data class PeriodConfigEntity(
    val period: Int,
    val startTime: String,
    val endTime: String,
    val timetableId: Long = 0
)