package com.example.awake.ui.navigation

object Routes {
    const val TIMETABLE = "timetable"
    const val LOGIN = "login"
    const val TERM_IMPORT = "term-import"
    const val SETTINGS = "settings"
    const val COURSE_DETAIL = "course-detail/{courseId}"
    fun courseDetail(courseId: Long) = "course-detail/$courseId"
}
