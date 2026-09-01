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
    private var autoSwitchedFromDirect = false
    private val _uiState = MutableStateFlow(AuthUiState())
    private var latestAcademicYears: List<RemoteAcademicYear> = emptyList()
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun selectMode(mode: ScutAccessMode) {
        if (_uiState.value.selectedMode == mode) return
        autoSwitchedFromDirect = false
        switchTo(
            mode = mode,
            status = when (mode) {
                ScutAccessMode.DIRECT -> "将通过学校教务系统直连入口打开官方登录页"
                ScutAccessMode.WEB_VPN -> "将打开学校 WebVPN 官方门户；登录门户后将自动打开教务系统"
            }
        )
    }

    private fun switchTo(mode: ScutAccessMode, status: String) {
        _uiState.value = AuthUiState(status = status, selectedMode = mode)
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
        // 重新 attach 后，旧入口的 WebView 回调仍可能迟到；只有「仍然 attach 在
        // 该入口、且 UI 仍显示该入口」的回调才允许写状态，避免旧回调把模式选择
        // 或状态消息改回去（例如直连失败自动切换 VPN 时被迟到的 onReady 覆盖）。
        fun isCurrent(target: ScutAccessMode): Boolean =
            attachedMode == target && _uiState.value.selectedMode == target
        login.attach(
            webView = webView,
            accessMode = mode,
            onBlocked = { reason ->
                if (!isCurrent(mode)) return@attach
                _uiState.value = _uiState.value.copy(status = reason, selectedMode = mode, confirming = false)
            },
            onFailure = { reason ->
                if (!isCurrent(mode)) return@attach
                _uiState.value = _uiState.value.copy(status = reason, selectedMode = mode, confirming = false)
            },
            onNetworkFailure = { reason ->
                if (!isCurrent(mode)) return@attach
                if (mode == ScutAccessMode.DIRECT) {
                    // 直连入口只在校园网内可达；校外连接被拒绝时自动切换 WebVPN，
                    // 不要让失败页面卡住用户、连切换入口的机会都没有。
                    Log.d("AwakeAuth", "direct entry unreachable, auto switching to WebVPN: $reason")
                    autoSwitchedFromDirect = true
                    switchTo(
                        mode = ScutAccessMode.WEB_VPN,
                        status = "直连入口连接失败；校外网络可能无法直连教务系统，已自动切换到 WebVPN 门户"
                    )
                } else {
                    _uiState.value = _uiState.value.copy(status = reason, selectedMode = mode, confirming = false)
                }
            },
            onReady = {
                if (!isCurrent(mode)) return@attach
                _uiState.value = _uiState.value.copy(
                    status = when {
                        mode == ScutAccessMode.WEB_VPN && autoSwitchedFromDirect ->
                            "直连入口不可用，已自动切换 WebVPN；请在门户完成登录，成功后会自动打开教务系统"
                        mode == ScutAccessMode.DIRECT -> "请在官方页面完成登录；成功获取会话后将自动进入课表选择"
                        else -> "请在 WebVPN 官方门户完成登录；登录成功后会自动打开教务系统并进入课表选择"
                    },
                    selectedMode = mode,
                    confirming = false
                )
            },
            onSubmitting = {
                if (!isCurrent(mode)) return@attach
                _uiState.value = _uiState.value.copy(
                    status = "官方页面正在跳转，请稍候；登录成功后会自动进入课表选择",
                    selectedMode = mode,
                    academicTermsLoading = true
                )
            },
            onAcademicTerms = { years ->
                if (!isCurrent(mode)) return@attach
                latestAcademicYears = years
                academicTermsCache.years = years
                _uiState.value = _uiState.value.copy(
                    academicYears = years,
                    academicTermsLoading = false,
                    status = "登录成功，已读取 ${years.size} 个学年，正在进入课表选择…"
                )
            },
            onAcademicTermsFailure = { reason ->
                if (!isCurrent(mode)) return@attach
                _uiState.value = _uiState.value.copy(academicTermsLoading = false, status = reason)
            },
            onVerificationRequired = {
                if (!isCurrent(mode)) return@attach
                _uiState.value = _uiState.value.copy(
                    status = "官方页面要求二次验证码，请在页面内输入；验证码由学校系统发送，不是 Awake 发送",
                    selectedMode = mode,
                    confirming = false
                )
            },
            onAutoNavigating = {
                if (!isCurrent(mode)) return@attach
                _uiState.value = _uiState.value.copy(
                    status = "已登录 WebVPN 门户，正在自动打开教务系统…",
                    selectedMode = mode,
                    confirming = false,
                    academicTermsLoading = true
                )
            },
            onAuthenticated = {
                if (isCurrent(mode)) completeAuthentication(mode, onAuthenticated)
            }
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



