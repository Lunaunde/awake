package com.example.awake.domain.usecase

import android.webkit.WebView
import com.example.awake.data.remote.ScutAccessMode
import com.example.awake.data.remote.ScutAuthRepository
import com.example.awake.data.repository.LocalTimetableRepository
import com.example.awake.domain.model.Profile

/** 官方 CAS 登录用例：登录过程由 WebView 完成，本地只保存脱敏摘要和时间。 */
class LoginUseCase(
    private val auth: ScutAuthRepository,
    private val local: LocalTimetableRepository
) {
    val isAuthenticated: Boolean
        get() = auth.isAuthenticated()

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
    ) = auth.attach(
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

    fun cancel(webView: WebView) = auth.cancel(webView)

    fun confirmCurrentPage(
        webView: WebView,
        accessMode: ScutAccessMode,
        onAuthenticated: () -> Unit,
        onFailure: (String) -> Unit
    ) = auth.confirmCurrentPage(webView, accessMode, onAuthenticated, onFailure)


    fun logout() = auth.logout()

    suspend fun completeLogin(displayName: String? = null, studentId: String? = null): Profile =
        local.saveLoggedInProfile(displayName, studentId).toDomain()

    private fun com.example.awake.data.local.ProfileEntity.toDomain() = Profile(
        id = id,
        school = com.example.awake.domain.model.SchoolCode.SCUT,
        maskedStudentId = maskedStudentId,
        displayName = displayName,
        lastLoginAt = lastLoginAt
    )
}
