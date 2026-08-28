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
    indices = [Index(value = ["profileId", "xnm", "xqm"], unique = true)]
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
    val room: String = "",
    val dayOfWeek: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val credits: String? = null,
    val totalHours: String? = null,
    val courseType: String? = null,
    val assessment: String? = null,
    val className: String? = null,
    val color: Int = 0xff4f6bed.toInt(),
    val rawWeekText: String = ""
)

@Entity(
    tableName = "course_weeks",
    primaryKeys = ["courseId", "weekNumber"],
    foreignKeys = [ForeignKey(entity = CourseEntity::class, parentColumns = ["id"], childColumns = ["courseId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("weekNumber")]
)
data class CourseWeekEntity(val courseId: Long, val weekNumber: Int)

@Entity(tableName = "period_configs")
data class PeriodConfigEntity(
    @PrimaryKey val period: Int,
    val startTime: String,
    val endTime: String
)
