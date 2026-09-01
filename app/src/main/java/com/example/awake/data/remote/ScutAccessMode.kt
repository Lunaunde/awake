package com.example.awake.data.remote

/**
 * SCUT 教务访问入口。
 *
 * 直连：打开教务官方入口，登录成功后自动进入课表查询模块。
 * WebVPN：打开学校 WebVPN 官方门户，登录门户后由 [CasWebViewCoordinator]
 * 自动在门户内找到并打开教务系统，之后与直连完全共用同一套
 * 学年读取、会话接管与课表请求流程，不再要求用户手动选择跳转。
 */
enum class ScutAccessMode(
    val title: String,
    val description: String,
    val entryUrl: String,
    val isPortalOnly: Boolean
) {
    DIRECT(
        title = "直连",
        description = "直接访问学校教务系统",
        entryUrl = CasWebViewCoordinator.DIRECT_ENTRY_URL,
        isPortalOnly = false
    ),
    WEB_VPN(
        title = "VPN 连接",
        description = "登录 WebVPN 门户，自动进入教务系统",
        entryUrl = CasWebViewCoordinator.WEB_VPN_URL,
        isPortalOnly = true
    )
}
