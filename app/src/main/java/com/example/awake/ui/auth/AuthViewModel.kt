package com.example.awake.ui.auth

import android.util.Log
import android.webkit.WebView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.awake.data.remote.ScutAccessMode
import com.example.awake.domain.usecase.LoginUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val status: String = "请选择访问方式，正在准备官方登录页…",
    val selectedMode: ScutAccessMode = ScutAccessMode.DIRECT,
    val authenticated: Boolean = false,
    val confirming: Boolean = false
)

class AuthViewModel(private val login: LoginUseCase) : ViewModel() {
    private var attachedMode: ScutAccessMode? = null
    private var currentWebView: WebView? = null
    private val _uiState = MutableStateFlow(AuthUiState())
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
    }

    fun attach(webView: WebView, mode: ScutAccessMode = _uiState.value.selectedMode) {
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
                        ScutAccessMode.DIRECT -> "官方直连登录页已打开，请完成登录、验证码并进入课表"
                        ScutAccessMode.WEB_VPN -> "WebVPN 官方门户已打开，请先登录并在门户内进入教务课表"
                    },
                    selectedMode = mode,
                    confirming = false
                )
            },
            onSubmitting = {
                _uiState.value = _uiState.value.copy(
                    status = "官方页面正在跳转，请继续操作直到看到课表",
                    selectedMode = mode
                )
            },
            onVerificationRequired = {
                _uiState.value = _uiState.value.copy(
                    status = "官方页面要求二次验证码，请在页面内输入；验证码由学校系统发送，不是 Awake 发送",
                    selectedMode = mode,
                    confirming = false
                )
            }
        )
    }

    /** 用户确认已在官方教务页面进入课表后，才开始复制会话并进入导入流程。 */
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
                viewModelScope.launch {
                    runCatching { login.completeLogin() }
                        .onSuccess {
                            _uiState.value = AuthUiState("登录成功，正在进入课表导入…", mode, authenticated = true)
                            onAuthenticated()
                        }
                        .onFailure { error ->
                            _uiState.value = AuthUiState(
                                error.message ?: "登录状态保存失败",
                                mode,
                                confirming = false
                            )
                        }
                }
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

    fun cancel(webView: WebView) {
        if (currentWebView === webView) currentWebView = null
        attachedMode = null
        login.cancel(webView)
    }
}

class AuthViewModelFactory(private val login: LoginUseCase) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AuthViewModel(login) as T
}
