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
    /** 覆盖目标课表（目标由调用方指定；未指定时回退为同学期已存在课表）。 */
    OVERWRITE,

    /** 保留已有课表，创建一份新的独立课表再导入。 */
    CREATE_NEW
}

/**
 * 按用户选择执行导入：
 * - overrideTargetId != null：直接覆盖该课表（学期元数据一并更新为本次导入的学期），失败不损坏原有课程；
 * - 否则按 policy 处理同学期课表（OVERWRITE 复用 / CREATE_NEW 新建“（新建）”后缀课表）。
 * 网络与解析失败时新导入流程不落库，原有课表保持完整。
 */
class ImportTimetableUseCase(
    private val local: LocalTimetableRepository,
    private val remote: ScutScheduleRepository
) {
    suspend operator fun invoke(
        profileId: Long,
        xnm: Int,
        xqm: String,
        label: String,
        policy: ExistingTimetablePolicy = ExistingTimetablePolicy.CREATE_NEW,
        selectedRemoteKeys: Set<String>? = null,
        overrideTargetId: Long? = null
    ): ImportTimetableResult {
        require(xnm > 0) { "学年起始年无效" }
        require(xqm.isNotBlank()) { "学期码不能为空" }
        require(label.isNotBlank()) { "课表名称不能为空" }

        val overrideTarget = if (policy == ExistingTimetablePolicy.OVERWRITE) overrideTargetId else null
        val existing = local.findTimetable(profileId, xnm, xqm)

        val timetable: TimetableEntity
        var originalMeta: TimetableEntity? = null
        var createdForThisImport = false
        if (overrideTarget != null) {
            val target = local.getTimetableOrNull(overrideTarget) ?: error("要覆盖的课表不存在")
            originalMeta = target
            // 覆盖当前课表：学期标识、名称更新为本次导入内容，再按该课表执行同步替换。
            timetable = target.copy(xnm = xnm, xqm = xqm, label = label).also {
                local.updateTimetable(it)
            }
        } else {
            timetable = when (policy) {
                ExistingTimetablePolicy.OVERWRITE ->
                    existing ?: local.createTimetable(profileId, xnm, xqm, label)
                ExistingTimetablePolicy.CREATE_NEW ->
                    local.createTimetable(profileId, xnm, xqm, newLabel(local, profileId, label))
            }
            createdForThisImport = policy == ExistingTimetablePolicy.CREATE_NEW || existing == null
        }
        return try {
            val warnings = remote.import(timetable.id, selectedRemoteKeys)
            ImportTimetableResult(timetable, warnings)
        } catch (error: Throwable) {
            if (createdForThisImport) local.deleteTimetable(timetable.id)
            // 覆盖现有课表失败：恢复学期元数据，保证旧课表信息一致。
            originalMeta?.let { local.updateTimetable(it) }
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