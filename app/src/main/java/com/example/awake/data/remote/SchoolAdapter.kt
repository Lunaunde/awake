package com.example.awake.data.remote

/**
 * 学校适配器的最小契约。适配器只描述学校和学期参数支持范围，
 * 不接收密码、Cookie、ticket 等认证敏感信息。
 */
interface SchoolAdapter {
    val code: String
    val displayName: String

    fun supports(xnm: Int, xqm: String): Boolean = xnm > 0 && xqm.isNotBlank()
}

/** 华南理工大学适配器。具体认证和课表请求仍由 SCUT 专用网络层负责。 */
class ScutAdapter : SchoolAdapter {
    override val code = "SCUT"
    override val displayName = "华南理工大学"
}

/**
 * 学校适配器注册表。
 *
 * 首版只注册 SCUT；未来接入其他学校时，应新增独立适配器并在这里注册，
 * 不要在通用 UI 或 Repository 中继续堆叠学校特判。
 */
class SchoolAdapterRegistry(adapters: List<SchoolAdapter> = listOf(ScutAdapter())) {
    private val byCode: Map<String, SchoolAdapter> = adapters.associateBy { it.code }

    init {
        require(adapters.isNotEmpty()) { "至少需要注册一个学校适配器" }
        require(byCode.size == adapters.size) { "学校适配器代码不能重复" }
        require(adapters.all { it.code.isNotBlank() && it.displayName.isNotBlank() }) {
            "学校适配器代码和名称不能为空"
        }
    }

    fun get(code: String): SchoolAdapter? = byCode[code]

    fun require(code: String, xnm: Int, xqm: String): SchoolAdapter {
        val adapter = get(code) ?: error("暂不支持学校：$code")
        check(adapter.supports(xnm, xqm)) { "学校 $code 不支持该学期参数" }
        return adapter
    }

    fun all(): List<SchoolAdapter> = byCode.values.toList()
}
