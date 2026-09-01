package com.example.awake.data.remote

import java.net.URI

/**
 * WebVPN 门户登录完成后「自动进入教务系统」的纯逻辑（可单元测试）。
 *
 * 学校 WebVPN 门户的代理链接使用系统私有编码（不同厂商/版本格式不同），
 * App 不自行拼装加密 URL，而是从门户登录后真实渲染出的资源链接中选择
 * 教务系统入口，因此不依赖具体编码格式。选中入口后的跳转、会话捕获、
 * 学年读取与课表获取，与直连模式完全共用同一套逻辑。
 */
object WebVpnPortalJump {

    /** 扫描门户页面得到的一条候选链接。 */
    data class LinkHit(val href: String, val text: String = "")

    /** 链接地址本身即可识别教务原站的特征（代理路径中保留的 jwglxt/域名片段），带权重。 */
    private val JW_HREF_MARKER_WEIGHTS = listOf(
        "jwglxt" to 4,
        "xsjw2018" to 2,
        "kbcx" to 1
    )

    /** 链接文本关键词，按优先级从高到低排列。 */
    val TARGET_KEYWORDS = listOf(
        "本科教务系统", "正方教务", "教务系统", "教务管理", "教务处", "教务"
    )

    /**
     * 当前页面是否为 WebVPN 门户登录后的主页：
     * 域名属于学校 WebVPN，且不是登录页、SSO 页或已经代理的业务页。
     */
    fun isPortalHomePage(rawUrl: String): Boolean = runCatching {
        val uri = URI(rawUrl)
        val host = uri.host?.lowercase() ?: return@runCatching false
        if (!isWebVpnHost(host)) return@runCatching false
        val path = uri.path.orEmpty().lowercase()
        !path.contains("/login") &&
            !path.contains("/sso") &&
            !path.contains("/jwglxt") &&
            !path.contains("/cas")
    }.getOrDefault(false)

    fun isWebVpnHost(host: String): Boolean {
        val normalized = host.lowercase()
        return normalized == CasWebViewCoordinator.WEB_VPN_HOST ||
            normalized.endsWith("." + CasWebViewCoordinator.WEB_VPN_HOST)
    }

    /**
     * 从候选链接中选择最可信的教务系统入口：
     * 1. href 内出现教务原站特征（jwglxt/xsjw2018/kbcx）的链接优先，按特征权重求和；
     * 2. 否则取文本匹配关键词的链接，按关键词优先级排序，同关键词下取文本更短（更像磁贴）的链接。
     */
    fun selectJumpTarget(hits: List<LinkHit>): LinkHit? {
        val hrefMatches = hits
            .filter { hit ->
                hit.href.isNotBlank() &&
                    JW_HREF_MARKER_WEIGHTS.any { (marker, _) -> hit.href.contains(marker, ignoreCase = true) }
            }
            .sortedWith(
                compareByDescending<LinkHit> { hit ->
                    JW_HREF_MARKER_WEIGHTS.sumOf { (marker, weight) ->
                        if (hit.href.contains(marker, ignoreCase = true)) weight else 0
                    }
                }.thenBy { it.text.length }
            )
        if (hrefMatches.isNotEmpty()) return hrefMatches.first()

        val normalized = hits.map { hit ->
            hit.copy(text = hit.text.replace(Regex("\\s+"), " ").trim())
        }.filter { it.text.isNotBlank() }

        return TARGET_KEYWORDS
            .asSequence()
            .flatMap { keyword ->
                normalized.filter { it.text.contains(keyword) }.map { keyword to it }
            }
            .minWithOrNull(
                compareBy<Pair<String, LinkHit>> { (keyword, _) -> TARGET_KEYWORDS.indexOf(keyword) }
                    .thenBy { (_, hit) -> hit.text.length }
            )
            ?.second
    }
}
