package com.example.awake.ui.auth

import android.util.Log
import android.webkit.WebView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.awake.data.remote.ScutAccessMode
import com.example.awake.data.remote.RemoteAcademicYear
import com.example.awake.data.remote.AcademicTermsCache
import com.example.awake.domain.usecase.LoginUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val status: String = "请选择访问方式，正在准备官方登录页…",
    val selectedMode: ScutAccessMode = ScutAccessMode.DIRECT,
    val authenticated: Boolean = false,
    val confirming: Boolean = false,
    val academicYears: List<RemoteAcademicYear> = emptyList(),
    val academicTermsLoading: Boolean = false
)

class AuthViewModel(
    private val login: LoginUseCase,
    private val academicTermsCache: AcademicTermsCache
) : ViewModel() {
    private var attachedMode: ScutAccessMode? = null
    private var currentWebView: WebView? = null
    private var loginCompletionStarted = false
    private val _uiState = MutableStateFlow(AuthUiState())
    private var latestAcademicYears: List<RemoteAcademicYear> = emptyList()
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun selectMode(mode: ScutAccessMode) {
        if (_uiState.value.selectedMode == mode) return
        _uiState.value = AuthUiState(
            status = when (mode) {
                ScutAccessMode.DIRECT -> "将通过学校教务系统直连入口打开官方登录页"
                ScutAccessMode.WEB_VPN -> "将打开学校 WebVPN 官方门户，请在门户内继续操作"
            },
            selectedMode = mode
        )
        loginCompletionStarted = false
    }

    fun attach(
        webView: WebView,
        mode: ScutAccessMode = _uiState.value.selectedMode,
        onAuthenticated: () -> Unit
    ) {
        currentWebView = webView
        if (attachedMode == mode) return
        Log.d("AwakeAuth", "attach mode=$mode previous=$attachedMode")
        attachedMode = mode
        login.attach(
            webView = webView,
            accessMode = mode,
            onBlocked = { reason ->
                _uiState.value = _uiState.value.copy(status = reason, selectedMode = mode, confirming = false)
            },
            onFailure = { reason ->
                _uiState.value = _uiState.value.copy(status = reason, selectedMode = mode, confirming = false)
            },
            onReady = {
                _uiState.value = _uiState.value.copy(
                    status = when (mode) {
                        ScutAccessMode.DIRECT -> "请在官方页面完成登录；成功获取会话后将自动进入课表选择"
                        ScutAccessMode.WEB_VPN -> "请先在 WebVPN 官方门户登录并打开教务系统；成功后将自动进入课表选择"
                    },
                    selectedMode = mode,
                    confirming = false
                )
            },
            onSubmitting = {
                _uiState.value = _uiState.value.copy(
                    status = "官方页面正在跳转，请稍候；登录成功后会自动进入课表选择",
                    selectedMode = mode,
                    academicTermsLoading = true
                )
            },
            onAcademicTerms = { years ->
                latestAcademicYears = years
                academicTermsCache.years = years
                _uiState.value = _uiState.value.copy(
                    academicYears = years,
                    academicTermsLoading = false,
                    status = "登录成功，已读取 ${years.size} 个学年，正在进入课表选择…"
                )
            },
            onAcademicTermsFailure = { reason ->
                _uiState.value = _uiState.value.copy(academicTermsLoading = false, status = reason)
            },
            onVerificationRequired = {
                _uiState.value = _uiState.value.copy(
                    status = "官方页面要求二次验证码，请在页面内输入；验证码由学校系统发送，不是 Awake 发送",
                    selectedMode = mode,
                    confirming = false
                )
            },
            onAuthenticated = { completeAuthentication(mode, onAuthenticated) }
        )
    }

    /** 自动检测未触发时，允许用户手动重新检查当前官方页面。 */
    fun confirmCurrentPage(onAuthenticated: () -> Unit) {
        val webView = currentWebView
        val mode = _uiState.value.selectedMode
        if (webView == null) {
            _uiState.value = _uiState.value.copy(status = "官方页面尚未准备好，请稍候再试", confirming = false)
            return
        }
        if (_uiState.value.confirming) return
        _uiState.value = _uiState.value.copy(
            status = "正在确认教务页面，请稍候…",
            confirming = true,
            selectedMode = mode
        )
        login.confirmCurrentPage(
            webView = webView,
            accessMode = mode,
            onAuthenticated = {
                completeAuthentication(mode, onAuthenticated)
            },
            onFailure = { reason ->
                _uiState.value = _uiState.value.copy(
                    status = reason,
                    selectedMode = mode,
                    confirming = false
                )
            }
        )
    }

    private fun completeAuthentication(mode: ScutAccessMode, onAuthenticated: () -> Unit) {
        if (loginCompletionStarted) return
        loginCompletionStarted = true
        viewModelScope.launch {
            runCatching { login.completeLogin() }
                .onSuccess {
                    _uiState.value = AuthUiState(
                        status = "登录成功，正在进入课表选择…",
                        selectedMode = mode,
                        authenticated = true,
                        academicYears = latestAcademicYears
                    )
                    onAuthenticated()
                }
                .onFailure { error ->
                    loginCompletionStarted = false
                    _uiState.value = AuthUiState(
                        error.message ?: "登录状态保存失败",
                        mode,
                        confirming = false
                    )
                }
        }
    }

    fun cancel(webView: WebView) {
        if (currentWebView === webView) currentWebView = null
        attachedMode = null
        login.cancel(webView)
    }
}

class AuthViewModelFactory(
    private val login: LoginUseCase,
    private val academicTermsCache: AcademicTermsCache
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AuthViewModel(login, academicTermsCache) as T
}



