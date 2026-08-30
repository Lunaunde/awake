package com.example.awake.data.remote

import android.webkit.WebView

/**
 * SCUT 登录会话门面：只暴露登录、取消和登出操作，凭证仍由内存 Cookie Store 管理。
 */
class ScutAuthRepository(
    private val cookieStore: SessionCookieStore,
    private val coordinator: CasWebViewCoordinator = CasWebViewCoordinator(cookieStore)
) {
    fun isAuthenticated(): Boolean =
        cookieStore.has(CasWebViewCoordinator.JW_HOST, "JSESSIONID", "/jwglxt")

    fun attach(
        webView: WebView,
        accessMode: ScutAccessMode = ScutAccessMode.DIRECT,
        onBlocked: (String) -> Unit,
        onFailure: (String) -> Unit = {},
        onReady: () -> Unit = {},
        onSubmitting: () -> Unit = {},
        onVerificationRequired: () -> Unit = {}
    ) = coordinator.attach(
        webView = webView,
        accessMode = accessMode,
        onBlocked = onBlocked,
        onFailure = onFailure,
        onReady = onReady,
        onSubmitting = onSubmitting,
        onVerificationRequired = onVerificationRequired
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
