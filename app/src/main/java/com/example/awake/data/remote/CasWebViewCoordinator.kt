package com.example.awake.data.remote

import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ConsoleMessage
import android.util.Log
import java.net.URI

/** 仅在内存中保存会话 Cookie，进程结束即丢弃。 */
class SessionCookieStore {
    private data class StoredCookie(
        val domain: String,
        val path: String,
        val name: String,
        val value: String,
        val expiresAtMillis: Long?
    )

    private val cookies = mutableListOf<StoredCookie>()

    @Synchronized
    fun captureFromCookieManager(manager: CookieManager, hosts: List<String>) {
        // CookieManager.getCookie() 只返回当前 URL 路径可见的 Cookie。分别查询
        // CAS、票据交换和教务路径，避免漏掉 Path=/jwglxt 的 JSESSIONID。
        val paths = listOf("/", "/cas", "/sso", "/jwglxt", "/jwglxt/xtgl/index_initMenu.html")
        hosts.forEach { host ->
            val schemes = if (host == CasWebViewCoordinator.JW_HOST) {
                listOf("http", "https")
            } else {
                listOf("https")
            }
            schemes.forEach { scheme ->
                paths.forEach { path ->
                    val cookieUrl = "$scheme://$host$path"
                    manager.getCookie(cookieUrl)
                        ?.split(';')
                        ?.map(String::trim)
                        ?.filter(String::isNotBlank)
                        ?.forEach { item ->
                            val parts = item.split('=', limit = 2)
                            if (parts.size == 2 && parts[0].isNotBlank()) {
                                put(host, cookiePathFor(host, path, parts[0].trim()), parts[0].trim(), parts[1].trim())
                            }
                        }
                }
            }
        }
    }

    private fun cookiePathFor(host: String, requestPath: String, name: String): String = when {
        name.equals("JSESSIONID", ignoreCase = true) && host == CasWebViewCoordinator.JW_HOST &&
            requestPath.startsWith("/jwglxt") -> "/jwglxt"
        name.equals("JSESSIONID", ignoreCase = true) && host == CasWebViewCoordinator.JW_HOST &&
            requestPath.startsWith("/sso") -> "/sso"
        name.equals("CASTGC", ignoreCase = true) && host == CasWebViewCoordinator.CAS_HOST &&
            requestPath.startsWith("/cas") -> "/cas"
        else -> "/"
    }

    @Synchronized
    fun put(domain: String, path: String, name: String, value: String, expiresAtMillis: Long? = null) {
        val normalizedDomain = domain.trim().removePrefix(".").lowercase()
        val normalizedPath = path.trim().ifBlank { "/" }
        require(normalizedDomain.isNotBlank())
        require(normalizedPath.startsWith('/'))
        require(name.isNotBlank())
        cookies.removeAll { cookie ->
            cookie.domain == normalizedDomain && cookie.path == normalizedPath && cookie.name == name
        }
        cookies += StoredCookie(normalizedDomain, normalizedPath, name, value, expiresAtMillis)
    }

    @Synchronized
    fun has(host: String, name: String, path: String = "/"): Boolean =
        matchingCookies(host, path).any { it.name == name }

    @Synchronized
    fun cookieHeaderFor(host: String, path: String = "/"): String =
        // 同名 Cookie 可能同时存在于 / 与 /jwglxt。优先发送路径更具体的值，
        // 并去掉同名的旧值，避免教务服务器把旧 JSESSIONID 当成当前会话。
        matchingCookies(host, path)
            .sortedWith(compareByDescending<StoredCookie> { it.path.length }
                .thenByDescending { it.domain.length })
            .distinctBy { it.name }
            .joinToString("; ") { "${it.name}=${it.value}" }

    @Synchronized
    fun clearMemory() {
        cookies.clear()
    }

    fun clear() {
        clearMemory()
        CookieManager.getInstance().removeAllCookies(null)
    }

    @Synchronized
    fun isEmpty(): Boolean = cookies.isEmpty()

    @Synchronized
    private fun matchingCookies(host: String, path: String): List<StoredCookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { cookie -> cookie.expiresAtMillis != null && cookie.expiresAtMillis <= now }
        val normalizedHost = host.trim().lowercase()
        val normalizedPath = path.trim().ifBlank { "/" }
        return cookies.filter { cookie ->
            domainMatches(normalizedHost, cookie.domain) && pathMatches(normalizedPath, cookie.path)
        }
    }

    private fun domainMatches(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")

    private fun pathMatches(requestPath: String, cookiePath: String): Boolean =
        requestPath == cookiePath || requestPath.startsWith(cookiePath.trimEnd('/') + "/")
}

class CasWebViewCoordinator(private val cookieStore: SessionCookieStore) {
    companion object {
        private const val TAG = "AwakeWebView"
        const val CAS_HOST = "sso.scut.edu.cn"
        const val JW_HOST = "xsjw2018.jw.scut.edu.cn"
        const val WEB_VPN_HOST = "webvpn.scut.edu.cn"
        const val DIRECT_ENTRY_URL = "http://xsjw2018.jw.scut.edu.cn/"
        const val DIRECT_BASE_URL = DIRECT_ENTRY_URL
        const val SERVICE_URL = "http://xsjw2018.jw.scut.edu.cn/jwglxt/sso/login"
        const val LOGIN_URL = "https://sso.scut.edu.cn/cas/login?service=https%3A%2F%2Fxsjw2018.jw.scut.edu.cn%2Fjwglxt%2Fsso%2Flogin"
        const val DIRECT_LOGIN_URL = "https://sso.scut.edu.cn/cas/login?service=http%3A%2F%2Fxsjw2018.jw.scut.edu.cn%2Fjwglxt%2Fsso%2Flogin"
        const val WEB_VPN_URL = "https://webvpn.scut.edu.cn/"
    }

    fun attach(
        webView: WebView,
        accessMode: ScutAccessMode = ScutAccessMode.DIRECT,
        onBlocked: (String) -> Unit,
        onFailure: (String) -> Unit = {},
        onReady: () -> Unit = {},
        onSubmitting: () -> Unit = {},
        onVerificationRequired: () -> Unit = {}
    ) {
        webView.stopLoading()
        Log.d(TAG, "attach accessMode=$accessMode entry=${accessMode.entryUrl}")
        var readyReported = false
        var failureReported = false
        var loopWindowStartedAt = 0L
        var loopCount = 0
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        manager.setAcceptThirdPartyCookies(webView, true)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val message = consoleMessage.message()
                if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.e(
                        TAG,
                        "console error line=${consoleMessage.lineNumber()} " +
                            "source=${safeLocation(consoleMessage.sourceId())} " +
                            "message=${safeConsoleMessage(message)}"
                    )
                } else if (message.startsWith("[AwakeClick]") || message.startsWith("[AwakeSubmit]")) {
                    Log.i(TAG, safeConsoleMessage(message))
                    if (message.startsWith("[AwakeClick]") && message.contains("type=button")) {
                        onSubmitting()
                    }
                }
                return true
            }
        }
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        val defaultUserAgent = webView.settings.userAgentString.orEmpty()
        webView.settings.userAgentString = if (defaultUserAgent.contains("Awake/1.0")) {
            defaultUserAgent
        } else {
            "$defaultUserAgent Awake/1.0"
        }
        fun reportFailureOnce(reason: String) {
            if (failureReported) return
            failureReported = true
            onFailure(reason)
        }

        fun recordLoginLoop(url: String) {
            if (accessMode != ScutAccessMode.DIRECT) return
            val location = safeLocation(url)
            val now = System.currentTimeMillis()
            if (now - loopWindowStartedAt > 20_000L) {
                loopWindowStartedAt = now
                loopCount = 0
            }
            if (location == "$CAS_HOST/cas/login" || location == "$JW_HOST/jwglxt/sso/login") {
                loopCount += 1
                Log.d(TAG, "auth loop candidate count=$loopCount $location")
                if (loopCount >= 6) {
                    // 登录页面可能因 CAS/教务重定向重复出现，但不能因此停止 WebView。
                    // 用户仍需要在官方页面完成验证码、二次认证和菜单跳转。
                    Log.w(TAG, "auth loop observed count=$loopCount; keep WebView interactive")
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                Log.d(TAG, "page started ${safeLocation(url)}")
                if (isAllowed(url, accessMode)) recordLoginLoop(url)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                val allowed = isAllowed(url, accessMode)
                Log.d(TAG, "navigation method=${request.method} mainFrame=${request.isForMainFrame} " +
                    "allowed=$allowed ${safeLocation(url)}")
                return if (allowed) false else {
                    onBlocked(blockedMessage(accessMode))
                    true
                }
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                val allowed = isAllowed(url, accessMode)
                Log.d(TAG, "legacy navigation allowed=$allowed ${safeLocation(url)}")
                return if (allowed) false else {
                    onBlocked(blockedMessage(accessMode))
                    true
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                Log.e(TAG, "load error mainFrame=${request.isForMainFrame} " +
                    "code=${error.errorCode} description=${safeConsoleMessage(error.description.toString())} " +
                    safeLocation(request.url.toString()))
                if (request.isForMainFrame) reportFailureOnce("官方${accessMode.title}页面加载失败，请检查网络后重试")
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: android.webkit.WebResourceResponse
            ) {
                Log.e(TAG, "http error mainFrame=${request.isForMainFrame} " +
                    "status=${errorResponse.statusCode} ${safeLocation(request.url.toString())}")
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                Log.e(TAG, "ssl error primary=${error.primaryError} ${safeLocation(error.url)}")
                handler.cancel()
                reportFailureOnce("官方登录页面安全连接失败，请检查设备时间和网络")
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                Log.e(TAG, "render process gone didCrash=${detail.didCrash()} priority=${detail.rendererPriorityAtExit()}")
                reportFailureOnce("官方登录页面异常退出，请返回后重试")
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                Log.d(TAG, "page finished title=${safeConsoleMessage(view.title.orEmpty())} ${safeLocation(url)}")
                if (!readyReported && isAllowed(url, accessMode)) {
                    readyReported = true
                    onReady()
                }
                installDebugPageHooks(view)
                detectSecondFactorPage(view, onVerificationRequired)
                // 不在页面加载完成时自动判定登录成功。CAS 登录后仍可能需要
                // 验证码、菜单跳转和课表页面操作，统一由用户点击“确认”触发。
            }
        }
        // removeAllCookies 是异步操作。必须等回调完成后再打开入口，避免清理动作
        // 在登录过程中晚到，把刚刚由 CAS 下发的会话 Cookie 删除，形成 JW↔CAS 循环。
        manager.removeAllCookies {
            Log.d(TAG, "cookie cleanup completed accessMode=$accessMode")
            cookieStore.clearMemory()
            val startUrl = when (accessMode) {
                ScutAccessMode.DIRECT -> DIRECT_ENTRY_URL
                ScutAccessMode.WEB_VPN -> WEB_VPN_URL
            }
            webView.post { webView.loadUrl(startUrl) }
        }
    }

    /**
     * 用户确认已经在官方教务页面完成登录并进入课表后，才复制会话并通知上层。
     * 这里不读取账号、密码、验证码或页面内容，只校验当前官方页面和会话 Cookie。
     */
    fun confirmCurrentPage(
        webView: WebView,
        accessMode: ScutAccessMode,
        onAuthenticated: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val rawUrl = webView.url.orEmpty()
        val uri = runCatching { URI(rawUrl) }.getOrNull()
        val host = uri?.host?.lowercase()
        val path = uri?.path.orEmpty()
        val isJwPage = host == JW_HOST && path.startsWith("/jwglxt") &&
            !path.contains("/sso/login") && !path.contains("/login")

        if (!isJwPage) {
            val message = when {
                accessMode == ScutAccessMode.WEB_VPN && host == WEB_VPN_HOST ->
                    "当前仍在 WebVPN 门户，请在门户内打开教务系统并进入课表后再确认"
                else ->
                    "当前还不是教务系统页面，请在官方页面完成登录并进入课表后再确认"
            }
            Log.w(TAG, "manual confirmation rejected mode=$accessMode location=${safeLocation(rawUrl)}")
            onFailure(message)
            return
        }

        val manager = CookieManager.getInstance()
        manager.flush()
        cookieStore.captureFromCookieManager(manager, listOf(CAS_HOST, JW_HOST))
        val hasSession = cookieStore.has(JW_HOST, "JSESSIONID", "/jwglxt")
        if (!hasSession) {
            Log.w(TAG, "manual confirmation missing JW session location=${safeLocation(rawUrl)}")
            onFailure("未检测到教务会话，请确认页面已登录成功并进入课表后再试")
            return
        }

        Log.i(TAG, "manual confirmation accepted location=${safeLocation(rawUrl)}")
        // 认证结果已复制到进程内 Store，清理 WebView Cookie，避免会话持久化。
        manager.removeAllCookies(null)
        onAuthenticated()
    }

    private fun blockedMessage(accessMode: ScutAccessMode): String = when (accessMode) {
        ScutAccessMode.DIRECT -> "已阻止非 SCUT 直连域名跳转"
        ScutAccessMode.WEB_VPN -> "已阻止非学校 WebVPN 域名跳转"
    }

    private fun detectSecondFactorPage(view: WebView, onVerificationRequired: () -> Unit) {
        view.evaluateJavascript(
            """
            (() => {
              const text = (document.body?.innerText || '').replace(/\s+/g, ' ');
              return /二次认证|重新获取验证码|验证码.*有效期/.test(text);
            })();
            """.trimIndent()
        ) { result ->
            if (result == "true") {
                Log.i(TAG, "second-factor verification page detected")
                onVerificationRequired()
            }
        }
    }

    private fun installDebugPageHooks(view: WebView) {
        if ((view.context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        view.evaluateJavascript(
            """
            (() => {
              if (window.__awakeDebugHooksInstalled) return 'already-installed';
              window.__awakeDebugHooksInstalled = true;
              document.addEventListener('click', event => {
                const el = event.target && event.target.closest ? event.target.closest('button, input, a, span, [role=button]') : event.target;
                if (!el) return;
                const id = el.id || '';
                const name = el.getAttribute('name') || '';
                const type = el.getAttribute('type') || '';
                console.log('[AwakeClick] tag=' + el.tagName + ' id=' + id + ' name=' + name + ' type=' + type);
              }, true);
              document.addEventListener('submit', event => {
                const form = event.target;
                console.log('[AwakeSubmit] method=' + (form.method || 'get') + ' actionPath=' + (new URL(form.action || location.href, location.href)).pathname);
              }, true);
              return 'installed';
            })();
            """.trimIndent(), null
        )
    }

    private fun safeLocation(rawUrl: String): String = runCatching {
        val uri = URI(rawUrl)
        val host = uri.host?.lowercase() ?: "<no-host>"
        val path = uri.path.orEmpty().ifBlank { "/" }
        "$host$path"
    }.getOrElse { "<invalid-url>" }

    private fun safeConsoleMessage(message: String): String =
        message.replace(
            Regex("(?i)(password|passwd|pwd|ticket|token|captcha|code|lt|rsa)\\s*[=:]\\s*[^,; ]+"),
            "$1=<redacted>"
        )
            .replace(Regex("\\s+"), " ")
            .take(240)

    fun isAllowed(url: String, accessMode: ScutAccessMode = ScutAccessMode.DIRECT): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase()
        val scheme = uri.scheme?.lowercase()
        val validPort = when (scheme) {
            "http" -> uri.port == -1 || uri.port == 80
            "https" -> uri.port == -1 || uri.port == 443
            else -> false
        }
        uri.userInfo == null && host != null && validPort && when (accessMode) {
            ScutAccessMode.DIRECT ->
                host in setOf(CAS_HOST, JW_HOST) && (scheme == "https" || (scheme == "http" && host == JW_HOST))
            ScutAccessMode.WEB_VPN ->
                scheme == "https" && host in setOf(WEB_VPN_HOST, CAS_HOST, JW_HOST)
        }
    }.getOrDefault(false)

    fun cancel(webView: WebView) {
        webView.stopLoading()
        CookieManager.getInstance().removeAllCookies(null)
    }

    fun clear() = cookieStore.clear()
}
