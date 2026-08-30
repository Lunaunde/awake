package com.example.awake.domain.usecase

import com.example.awake.data.local.TimetableEntity
import com.example.awake.data.repository.LocalTimetableRepository
import com.example.awake.data.repository.ScutScheduleRepository
import com.example.awake.domain.model.ParseWarning

 data class ImportTimetableResult(
    val timetable: TimetableEntity,
    val warnings: List<ParseWarning>
)

enum class ExistingTimetablePolicy {
    /** 导入到已有课表，成功后替换其中的教务同步课程。 */
    OVERWRITE,

    /** 保留已有课表，创建一份新的独立课表再导入。 */
    CREATE_NEW
}

/** 根据用户选择覆盖已有课表或新建课表；失败时不破坏原有课表。 */
class ImportTimetableUseCase(
    private val local: LocalTimetableRepository,
    private val remote: ScutScheduleRepository
) {
    suspend operator fun invoke(
        profileId: Long,
        xnm: Int,
        xqm: String,
        label: String,
        policy: ExistingTimetablePolicy = ExistingTimetablePolicy.CREATE_NEW
    ): ImportTimetableResult {
        require(xnm > 0) { "学年起始年无效" }
        require(xqm.isNotBlank()) { "学期码不能为空" }
        require(label.isNotBlank()) { "课表名称不能为空" }

        val existing = local.findTimetable(profileId, xnm, xqm)
        val timetable = when (policy) {
            ExistingTimetablePolicy.OVERWRITE ->
                existing ?: local.createTimetable(profileId, xnm, xqm, label)
            ExistingTimetablePolicy.CREATE_NEW ->
                local.createTimetable(profileId, xnm, xqm, newLabel(local, profileId, label))
        }
        val createdForThisImport = policy == ExistingTimetablePolicy.CREATE_NEW || existing == null
        return try {
            val warnings = remote.import(timetable.id)
            ImportTimetableResult(timetable, warnings)
        } catch (error: Throwable) {
            if (createdForThisImport) local.deleteTimetable(timetable.id)
            throw error
        }
    }

    private suspend fun newLabel(
        local: LocalTimetableRepository,
        profileId: Long,
        label: String
    ): String {
        val labels = local.getTimetables(profileId).map { it.label }.toSet()
        val base = if (label.endsWith("（新建）")) label else "$label（新建）"
        if (base !in labels) return base
        var suffix = 2
        while ("$base $suffix" in labels) suffix++
        return "$base $suffix"
    }
}
