package com.example.awake.data.repository

import com.example.awake.data.mapper.ScutScheduleMapper
import com.example.awake.data.remote.ScutJwClient
import com.example.awake.domain.model.ParseWarning

class ScutScheduleRepository(
    private val local: LocalTimetableRepository,
    private val client: ScutJwClient,
    private val mapper: ScutScheduleMapper = ScutScheduleMapper()
) {
    suspend fun import(timetableId: Long): List<ParseWarning> {
        val timetable = localTimetable(timetableId)
        val payload = client.fetchSchedule(timetable.xnm, timetable.xqm)
        val mapped = mapper.map(payload, timetable.id)
        local.replaceRemoteCourses(timetable, mapped.courses, mapped.weeks)
        if (mapped.studentId != null || mapped.studentName != null) local.saveLoggedInProfile(mapped.studentName, mapped.studentId)
        return mapped.warnings
    }

    private suspend fun localTimetable(id: Long) = local.getTimetable(id)
}
