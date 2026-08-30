package com.example.awake.data.remote

/**
 * SCUT 教务访问入口。WebVPN 当前作为官方门户入口使用，具体代理后的教务接口地址
 * 需要学校门户实际跳转结构确认后再接入 OkHttp。
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
        description = "先进入学校 WebVPN 官方门户",
        entryUrl = CasWebViewCoordinator.WEB_VPN_URL,
        isPortalOnly = true
    )
}
