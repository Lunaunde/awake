package com.example.awake.data.local

/**
 * SCUT 课表默认节次时间。
 *
 * 这些值只用于首次初始化或识别旧版本的内置默认值；用户在设置页保存后的自定义时间不会被覆盖。
 */
object PeriodConfigDefaults {
    val values: List<Pair<String, String>> = listOf(
        "08:50" to "09:35",
        "09:40" to "10:25",
        "10:40" to "11:25",
        "11:30" to "12:15",
        "14:00" to "14:45",
        "14:50" to "15:35",
        "15:45" to "16:30",
        "16:35" to "17:20",
        "19:00" to "19:45",
        "19:55" to "20:40",
        "20:50" to "21:35"
    )

    val periodCount: Int get() = values.size

    fun entities(): List<PeriodConfigEntity> = values.mapIndexed { index, (start, end) ->
        PeriodConfigEntity(period = index + 1, startTime = start, endTime = end)
    }
}
