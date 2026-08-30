package com.example.awake.domain.usecase

import com.example.awake.data.repository.ScutScheduleRepository
import com.example.awake.domain.model.ParseWarning

/** 刷新已存在课表；仓储保证请求和本地替换单飞且失败不覆盖旧数据。 */
class RefreshTimetableUseCase(private val remote: ScutScheduleRepository) {
    suspend operator fun invoke(timetableId: Long): List<ParseWarning> = remote.import(timetableId)
}
