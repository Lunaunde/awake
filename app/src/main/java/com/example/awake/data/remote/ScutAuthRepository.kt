package com.example.awake.data.remote

import android.webkit.WebView

/**
 * SCUT 登录会话门面：只暴露登录、取消和登出操作，凭证仍由内存 Cookie Store 管理。
 */
class ScutAuthRepository(
    private val cookieStore: SessionCookieStore,
    private val coordinator: CasWebViewCoordinator = CasWebViewCoordinator(cookieStore)
) {
    fun isAuthenticated(): Boolean = cookieStore.availableAccessModes().isNotEmpty()

    /** 返回已经配置、可供课表请求尝试的入口，顺序固定为直连优先、VPN 其次。 */
    fun configuredAccessModes(): List<ScutAccessMode> = cookieStore.availableAccessModes()

    fun hasConfiguredSession(accessMode: ScutAccessMode): Boolean =
        cookieStore.hasConfiguredSession(accessMode)

    fun attach(
        webView: WebView,
        accessMode: ScutAccessMode = ScutAccessMode.DIRECT,
        onBlocked: (String) -> Unit,
        onFailure: (String) -> Unit = {},
        onNetworkFailure: (String) -> Unit = {},
        onReady: () -> Unit = {},
        onSubmitting: () -> Unit = {},
        onVerificationRequired: () -> Unit = {},
        onAutoNavigating: () -> Unit = {},
        onAcademicTerms: (List<com.example.awake.data.remote.RemoteAcademicYear>) -> Unit = {},
        onAcademicTermsFailure: (String) -> Unit = {},
        onAuthenticated: () -> Unit = {}
    ) = coordinator.attach(
        webView = webView,
        accessMode = accessMode,
        onBlocked = onBlocked,
        onFailure = onFailure,
        onNetworkFailure = onNetworkFailure,
        onReady = onReady,
        onSubmitting = onSubmitting,
        onVerificationRequired = onVerificationRequired,
        onAutoNavigating = onAutoNavigating,
        onAcademicTerms = onAcademicTerms,
        onAcademicTermsFailure = onAcademicTermsFailure,
        onAuthenticated = onAuthenticated
    )

    fun cancel(webView: WebView) = coordinator.cancel(webView)

    fun confirmCurrentPage(
        webView: WebView,
        accessMode: ScutAccessMode,
        onAuthenticated: () -> Unit,
        onFailure: (String) -> Unit
    ) = coordinator.confirmCurrentPage(webView, accessMode, onAuthenticated, onFailure)


    fun logout() = coordinator.clear()
}
