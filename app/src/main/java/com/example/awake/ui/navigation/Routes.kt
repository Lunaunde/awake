package com.example.awake.ui.navigation

object Routes {
    const val TIMETABLE = "timetable"
    const val LOGIN = "login"
    const val TERM_IMPORT = "term-import?mode={mode}"
    const val SETTINGS = "settings"
    const val COURSE_DETAIL = "course-detail/{courseId}"
    const val COURSE_EDITOR =
        "course-editor/{timetableId}/{dayOfWeek}/{startPeriod}?sectionId={sectionId}&masterId={masterId}"

    /** 导入页模式：add = 添加新课表（可多选，一律新建）；overwrite = 覆盖当前课表（单选）。 */
    fun termImport(mode: String) = "term-import?mode=$mode"

    fun courseDetail(courseId: Long) = "course-detail/$courseId"
    fun courseEditor(
        timetableId: Long,
        dayOfWeek: Int,
        startPeriod: Int,
        sectionId: Long = -1L,
        masterId: Long = -1L
    ) = "course-editor/$timetableId/$dayOfWeek/$startPeriod?sectionId=$sectionId&masterId=$masterId"
}