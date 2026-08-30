package com.example.awake.data.repository

import com.example.awake.data.mapper.ScutScheduleMapper
import com.example.awake.data.remote.SchoolAdapterRegistry
import com.example.awake.data.remote.ScutJwClient
import com.example.awake.domain.model.ParseWarning
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class ScutScheduleRepository(
    private val local: LocalTimetableRepository,
    private val client: ScutJwClient,
    private val mapper: ScutScheduleMapper = ScutScheduleMapper(),
    private val adapters: SchoolAdapterRegistry = SchoolAdapterRegistry()
) {
    /** 同一课表的同步请求单飞，避免并发刷新互相覆盖；不同学期可以并行导入。 */
    private val importLocks = ConcurrentHashMap<Long, Mutex>()

    suspend fun import(timetableId: Long): List<ParseWarning> {
        val lock = importLocks.getOrPut(timetableId) { Mutex() }
        return lock.withLock {
            val timetable = local.getTimetable(timetableId)
            // 在发起网络请求前确认学校适配器和学期参数，避免错误入口被静默当作 SCUT 请求。
            adapters.require(timetable.schoolCode, timetable.xnm, timetable.xqm)
            // 网络请求和解析均发生在事务外；只有完整映射成功后才进入原子替换事务。
            val payload = client.fetchSchedule(timetable.xnm, timetable.xqm)
            val mapped = mapper.map(payload, timetable.id, timetable.totalWeeks)
            local.replaceRemoteCourses(timetable, mapped.courses, mapped.weeks)
            if (mapped.studentId != null || mapped.studentName != null) {
                local.saveLoggedInProfile(mapped.studentName, mapped.studentId)
            }
            mapped.warnings
        }
    }
}
