package com.example.awake.data.remote

import org.json.JSONArray

/**
 * 教务课表查询页实际提供的一个学年及其可选学期。
 * xnm/xqm 是请求课表接口时使用的原始参数，不能根据显示文本猜测。
 */
data class RemoteAcademicYear(
    val xnm: Int,
    val label: String,
    val semesters: List<RemoteSemester>
)

data class RemoteSemester(
    val xqm: String,
    val label: String
)

/** 登录 WebView 读取到的学年列表，在当前进程内短暂共享给导入页。 */
class AcademicTermsCache {
    @Volatile
    var years: List<RemoteAcademicYear> = emptyList()
}

/**
 * 解析教务课表查询页的学年、学期 select/option。
 *
 * 解析器只接收页面文本并返回结构化选项，不保存原始 HTML。页面结构偶尔会
 * 加入 class、data-* 等属性，因此这里不依赖属性顺序，也不依赖固定 class 名。
 */
object ScutAcademicTermParser {
    private val selectPattern = Regex(
        "<select\\b([^>]*)>(.*?)</select>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val optionPattern = Regex(
        "<option\\b([^>]*)>(.*?)</option\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val attributePattern = Regex(
        "(?:id|name)\\s*=\\s*(['\"])(.*?)\\1",
        RegexOption.IGNORE_CASE
    )
    private val valuePattern = Regex(
        "\\bvalue\\s*=\\s*(['\"])(.*?)\\1",
        RegexOption.IGNORE_CASE
    )
    private val academicYearPattern = Regex("(20\\d{2})\\s*[-—至/]\\s*(20\\d{2})")

    fun parse(html: String): List<RemoteAcademicYear> {
        require(html.isNotBlank()) { "教务系统返回的课表查询页为空" }

        val selects = selectPattern.findAll(html).mapNotNull { match ->
            val openingAttributes = match.groupValues[1]
            val name = attributePattern.find(openingAttributes)?.groupValues?.get(2)
                ?.trim()?.lowercase()
            val options = parseOptions(match.groupValues[2])
            if (name == null || options.isEmpty()) null else name to options
        }.toMap()

        val academicOptions = selects["xnm"] ?: selects["学年"]
            ?: throw IllegalArgumentException("课表查询页缺少学年选项")
        val semesterOptions = selects["xqm"] ?: selects["学期"]
            ?: throw IllegalArgumentException("课表查询页缺少学期选项")

        val semesters = semesterOptions.map { option ->
            RemoteSemester(
                xqm = option.value,
                label = normalizeSemesterLabel(option.text, option.value)
            )
        }.distinctBy(RemoteSemester::xqm)

        if (semesters.isEmpty()) {
            throw IllegalArgumentException("课表查询页没有有效学期选项")
        }

        return academicOptions.mapNotNull { option ->
            val xnm = option.value.toIntOrNull()
                ?: academicYearPattern.find(option.text)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            RemoteAcademicYear(
                xnm = xnm,
                label = normalizeAcademicYearLabel(option.text, xnm),
                semesters = semesters
            )
        }.distinctBy(RemoteAcademicYear::xnm)
            .takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("课表查询页没有有效学年选项")
    }

    /**
     * 解析 WebView evaluateJavascript 返回的结构化 select 数据。
     * 只接受学年/学期的 value 和显示文本，不接收账号、Cookie 或完整页面。
     */
    fun parseWebViewJson(raw: String): List<RemoteAcademicYear> {
        if (raw.isBlank() || raw == "null") throw IllegalArgumentException("WebView 未返回学年选项")
        val selects = JSONArray(raw)
        var academic: List<RawOption>? = null
        var semester: List<RawOption>? = null
        for (i in 0 until selects.length()) {
            val select = selects.optJSONObject(i) ?: continue
            val name = select.optString("name").ifBlank { select.optString("id") }.lowercase()
            val options = select.optJSONArray("options") ?: continue
            val parsed = buildList {
                for (j in 0 until options.length()) {
                    val option = options.optJSONObject(j) ?: continue
                    val value = option.optString("value").trim()
                    if (value.isNotBlank()) add(RawOption(value, option.optString("text").trim()))
                }
            }
            when {
                name == "xnm" || name.contains("学年") -> academic = parsed
                name == "xqm" || name.contains("学期") -> semester = parsed
            }
        }
        val academicOptions = academic?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("WebView 页面缺少学年选项")
        val semesterOptions = semester?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("WebView 页面缺少学期选项")
        val semesters = semesterOptions.map { option ->
            RemoteSemester(option.value, normalizeSemesterLabel(option.text, option.value))
        }.distinctBy(RemoteSemester::xqm)
        return academicOptions.mapNotNull { option ->
            val xnm = option.value.toIntOrNull()
                ?: academicYearPattern.find(option.text)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            RemoteAcademicYear(xnm, normalizeAcademicYearLabel(option.text, xnm), semesters)
        }.distinctBy(RemoteAcademicYear::xnm).takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("WebView 页面没有有效学年选项")
    }

    private fun parseOptions(html: String): List<RawOption> = optionPattern.findAll(html)
        .mapNotNull { match ->
            val value = valuePattern.find(match.groupValues[1])?.groupValues?.get(2)
                ?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val text = decodeHtml(match.groupValues[2].replace(Regex("<[^>]+>"), " "))
                .replace(Regex("\\s+"), " ")
                .trim()
            RawOption(value, text)
        }
        .toList()

    private fun normalizeAcademicYearLabel(text: String, xnm: Int): String {
        val match = academicYearPattern.find(text)
        return if (match != null) {
            "${match.groupValues[1]}-${match.groupValues[2]}"
        } else {
            "$xnm-${xnm + 1}"
        }
    }

    private fun normalizeSemesterLabel(text: String, xqm: String): String {
        if (text.isNotBlank() && !text.matches(Regex("\\d+"))) return text
        return when (xqm) {
            "3", "1" -> "第1学期"
            "12", "2" -> "第2学期"
            "16" -> "暑期学期"
            else -> if (text.isNotBlank()) "第${text}学期" else "学期 $xqm"
        }
    }

    private fun decodeHtml(value: String): String = value
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)

    private data class RawOption(val value: String, val text: String)
}
