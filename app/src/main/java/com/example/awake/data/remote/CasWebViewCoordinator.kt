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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** 仅在内存中保存会话 Cookie，进程结束即丢弃。 */
class SessionCookieStore {
    private data class StoredCookie(
        val domain: String,
        val path: String,
        val name: String,
        val value: String,
        val expiresAtMillis: Long?
    )

    private data class SessionState(
        val cookies: MutableList<StoredCookie> = mutableListOf(),
        var requestBaseUrl: HttpUrl? = null,
        var authenticated: Boolean = false
    )

    private val sessions = ScutAccessMode.values().associateWith { SessionState() }.toMutableMap()

    private fun state(accessMode: ScutAccessMode): SessionState =
        sessions.getValue(accessMode)

    @Synchronized
    fun captureFromCookieManager(
        manager: CookieManager,
        hosts: List<String>,
        extraPaths: List<String> = emptyList(),
        accessMode: ScutAccessMode = ScutAccessMode.DIRECT
    ) {
        // CookieManager.getCookie() 只返回当前 URL 路径可见的 Cookie。分别查询
        // CAS、票据交换和教务路径，避免漏掉 Path=/jwglxt 的 JSESSIONID。
        val paths = (listOf("/", "/cas", "/sso", "/jwglxt", "/jwglxt/xtgl/index_initMenu.html") + extraPaths)
            .map { path -> if (path.startsWith('/')) path else "/$path" }
            .distinct()
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
                                put(
                                    domain = host,
                                    path = cookiePathFor(host, path, parts[0].trim()),
                                    name = parts[0].trim(),
                                    value = parts[1].trim(),
                                    accessMode = accessMode
                                )
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
    fun put(
        domain: String,
        path: String,
        name: String,
        value: String,
        expiresAtMillis: Long? = null,
        accessMode: ScutAccessMode = ScutAccessMode.DIRECT
    ) {
        val normalizedDomain = domain.trim().removePrefix(".").lowercase()
        val normalizedPath = path.trim().ifBlank { "/" }
        require(normalizedDomain.isNotBlank())
        require(normalizedPath.startsWith('/'))
        require(name.isNotBlank())
        val cookies = state(accessMode).cookies
        cookies.removeAll { cookie ->
            cookie.domain == normalizedDomain && cookie.path == normalizedPath && cookie.name == name
        }
        cookies += StoredCookie(normalizedDomain, normalizedPath, name, value, expiresAtMillis)
    }

    @Synchronized
    fun has(
        host: String,
        name: String,
        path: String = "/",
        accessMode: ScutAccessMode = ScutAccessMode.DIRECT
    ): Boolean = matchingCookies(host, path, accessMode).any { it.name == name }

    @Synchronized
    fun cookieHeaderFor(
        host: String,
        path: String = "/",
        accessMode: ScutAccessMode = ScutAccessMode.DIRECT
    ): String =
        // 同名 Cookie 可能同时存在于 / 与 /jwglxt。优先发送路径更具体的值，
        // 并去掉同名的旧值，避免教务服务器把旧 JSESSIONID 当成当前会话。
        matchingCookies(host, path, accessMode)
            .sortedWith(compareByDescending<StoredCookie> { it.path.length }
                .thenByDescending { it.domain.length })
            .distinctBy { it.name }
            .joinToString("; ") { "${it.name}=${it.value}" }

    @Synchronized
    fun clearMemory() {
        sessions.values.forEach { session ->
            session.cookies.clear()
            session.requestBaseUrl = null
            session.authenticated = false
        }
    }

    /** 仅清理指定入口的会话，不能影响另一种入口已经保存的会话。 */
    @Synchronized
    fun clearSession(accessMode: ScutAccessMode) {
        val session = state(accessMode)
        session.cookies.clear()
        session.requestBaseUrl = null
        session.authenticated = false
    }

    fun clear() {
        clearMemory()
        CookieManager.getInstance().removeAllCookies(null)
    }

    @Synchronized
    fun isEmpty(accessMode: ScutAccessMode = ScutAccessMode.DIRECT): Boolean =
        state(accessMode).cookies.isEmpty()

    /**
     * 记录本次登录后课程接口应该走的地址。
     * 直连时是教务根地址；WebVPN 时是从 WebView 当前代理页面反推出的代理前缀。
     */
    @Synchronized
    fun configureSession(
        baseUrl: HttpUrl,
        accessMode: ScutAccessMode = ScutAccessMode.DIRECT
    ) {
        val session = state(accessMode)
        session.requestBaseUrl = baseUrl
        session.authenticated = true
    }

    @Synchronized
    fun configuredBaseUrl(
        fallback: HttpUrl,
        accessMode: ScutAccessMode = ScutAccessMode.DIRECT
    ): HttpUrl = state(accessMode).requestBaseUrl ?: fallback

    @Synchronized
    fun hasConfiguredSession(accessMode: ScutAccessMode = ScutAccessMode.DIRECT): Boolean =
        state(accessMode).authenticated && !isEmpty(accessMode)

    /** 返回已捕获且可用于请求的入口，顺序固定为直连优先、VPN 其次。 */
    @Synchronized
    fun availableAccessModes(): List<ScutAccessMode> = ScutAccessMode.values().filter { mode ->
        !isEmpty(mode) && (mode == ScutAccessMode.DIRECT || state(mode).requestBaseUrl != null)
    }

    @Synchronized
    private fun matchingCookies(
        host: String,
        path: String,
        accessMode: ScutAccessMode
    ): List<StoredCookie> {
        val now = System.currentTimeMillis()
        val cookies = state(accessMode).cookies
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
        private const val COURSE_MODULE_CODE = "N2151"
        private const val ACADEMIC_TERMS_MAX_ATTEMPTS = 10
        private const val ACADEMIC_TERMS_RETRY_DELAY_MS = 500L
        private const val PORTAL_SCAN_MAX_ATTEMPTS = 8
        private const val PORTAL_SCAN_RETRY_DELAY_MS = 800L
        private const val COOKIE_CLEANUP_FALLBACK_DELAY_MS = 2500L

        /** 判定为「入口网络不可达」的 WebView 主框架错误码（连接拒绝/域名解析/超时/IO）。 */
        private val CONNECTIVITY_ERROR_CODES = setOf(
            WebViewClient.ERROR_CONNECT,
            WebViewClient.ERROR_HOST_LOOKUP,
            WebViewClient.ERROR_TIMEOUT,
            WebViewClient.ERROR_IO
        )

        /** 扫描 WebVPN 门户主页全部（含同源 frame 内）链接，交给原生侧选择教务入口。 */
        private val PORTAL_LINK_SCAN_SCRIPT = """
            (() => {
              const hits = [];
              function collect(doc) {
                if (!doc) return;
                try {
                  Array.from(doc.querySelectorAll('a[href]')).forEach(a => {
                    const href = (a.href || '').trim();
                    if (!href || href === '#' || href.toLowerCase().startsWith('javascript:')) return;
                    const text = ((a.textContent || '') + ' ' + (a.title || '') + ' ' + (a.getAttribute('data-title') || ''))
                      .replace(/\s+/g, ' ').trim();
                    hits.push({ href: href, text: text.slice(0, 120) });
                  });
                } catch (e) {}
              }
              collect(document);
              try {
                for (let i = 0; i < window.frames.length; i++) {
                  try { collect(window.frames[i].document); } catch (e) {}
                }
              } catch (e) {}
              return JSON.stringify(hits);
            })();
        """.trimIndent()
    }

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
        onAcademicTerms: (List<RemoteAcademicYear>) -> Unit = {},
        onAcademicTermsFailure: (String) -> Unit = {},
        onAuthenticated: () -> Unit = {}
    ) {
        webView.stopLoading()
        Log.d(TAG, "attach accessMode=$accessMode entry=${accessMode.entryUrl}")
        var readyReported = false
        var failureReported = false
        var loopWindowStartedAt = 0L
        var loopCount = 0
        var authenticationReported = false
        var academicTermsReadStarted = false
        var academicTermsReported = false
        var autoJumpInProgress = false
        var autoJumpAttempt = 0
        var autoJumpExhausted = false
        var autoJumpGeneration = 0
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

        fun tryReportAuthentication(url: String) {
            if (authenticationReported || !isAuthenticatedJwPage(url, accessMode)) return

            // 只复制会话 Cookie，不读取页面中的账号、密码或验证码。
            manager.flush()
            cookieStore.captureFromCookieManager(
                manager,
                cookieHosts(accessMode, url),
                extraPaths = pathAncestors(url),
                accessMode = accessMode
            )
            val sessionReady = when (accessMode) {
                ScutAccessMode.DIRECT -> cookieStore.has(
                    JW_HOST,
                    "JSESSIONID",
                    "/jwglxt",
                    accessMode
                )
                ScutAccessMode.WEB_VPN -> {
                    val proxyBase = proxyBaseUrlFromPage(url)
                    proxyBase != null && cookieStore.cookieHeaderFor(
                        proxyBase.host,
                        proxyBase.encodedPath + "jwglxt",
                        accessMode
                    ).isNotBlank()
                }
            }
            if (!sessionReady) {
                Log.d(TAG, "jw page loaded without usable session mode=$accessMode ${safeLocation(url)}")
                return
            }

            val baseUrl = when (accessMode) {
                ScutAccessMode.DIRECT -> DIRECT_BASE_URL.toHttpUrl()
                ScutAccessMode.WEB_VPN -> proxyBaseUrlFromPage(url)
            }
            if (baseUrl == null) {
                Log.d(TAG, "could not derive WebVPN proxy base from ${safeLocation(url)}")
                return
            }
            cookieStore.configureSession(baseUrl, accessMode)

            authenticationReported = true
            Log.i(TAG, "authentication detected mode=$accessMode requestHost=${baseUrl.host} " +
                "requestPrefix=${baseUrl.encodedPath}")
            // 先在登录 WebView 内打开真实的课表查询模块；页面加载完成后再读取 DOM。
            readAcademicTermsPage(webView, baseUrl, accessMode)
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

        fun parsePortalLinks(raw: String): List<WebVpnPortalJump.LinkHit> = runCatching {
            val decoded = org.json.JSONTokener(raw).nextValue() as? String ?: return@runCatching emptyList()
            val array = org.json.JSONArray(decoded)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val href = item.optString("href").trim()
                if (href.isBlank()) null else WebVpnPortalJump.LinkHit(href, item.optString("text"))
            }
        }.getOrDefault(emptyList())

        fun scanPortalLinks(view: WebView, attempt: Int, generation: Int) {
            if (generation != autoJumpGeneration) {
                autoJumpInProgress = false
                return
            }
            view.evaluateJavascript(PORTAL_LINK_SCAN_SCRIPT) { raw ->
                if (generation != autoJumpGeneration) {
                    autoJumpInProgress = false
                    return@evaluateJavascript
                }
                val hits = parsePortalLinks(raw)
                val target = WebVpnPortalJump.selectJumpTarget(hits)
                if (target != null && isAllowed(target.href, accessMode)) {
                    autoJumpInProgress = false
                    autoJumpExhausted = true
                    Log.i(TAG, "portal auto jump target attempt=${attempt + 1} hits=${hits.size}")
                    onAutoNavigating()
                    view.post { view.loadUrl(target.href) }
                } else if (attempt + 1 < PORTAL_SCAN_MAX_ATTEMPTS) {
                    view.postDelayed(
                        { scanPortalLinks(view, attempt + 1, generation) },
                        PORTAL_SCAN_RETRY_DELAY_MS
                    )
                } else {
                    autoJumpInProgress = false
                    autoJumpExhausted = true
                    Log.w(TAG, "portal auto jump gave up after ${attempt + 1} scans; keeping WebView interactive")
                }
            }
        }

        /**
         * WebVPN 模式：门户登录完成后自动从门户主页中找到教务系统入口并跳转，
         * 之后与直连模式共用同一套「教务页面 → 学年读取 → 会话接管」流程，
         * 不再要求用户手动在门户内寻找教务系统。
         */
        fun maybeAutoJumpFromPortal(view: WebView, url: String) {
            if (accessMode != ScutAccessMode.WEB_VPN) return
            if (authenticationReported || academicTermsReadStarted) return
            if (!WebVpnPortalJump.isPortalHomePage(url)) return
            if (autoJumpExhausted || autoJumpInProgress) return
            autoJumpInProgress = true
            val generation = autoJumpGeneration
            val attempt = autoJumpAttempt
            autoJumpAttempt += 1
            Log.d(TAG, "portal auto jump scan attempt=${attempt + 1}")
            scanPortalLinks(view, attempt, generation)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                Log.d(TAG, "page started ${safeLocation(url)}")
                autoJumpGeneration += 1
                if (!WebVpnPortalJump.isPortalHomePage(url)) {
                    // 离开门户主页（例如进入 SSO 登录页）后重新允许下一轮自动跳转扫描。
                    autoJumpAttempt = 0
                    autoJumpExhausted = false
                    autoJumpInProgress = false
                }
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
                if (request.isForMainFrame) {
                    val reason = "官方${accessMode.title}页面加载失败，请检查网络后重试"
                    reportFailureOnce(reason)
                    if (error.errorCode in CONNECTIVITY_ERROR_CODES) {
                        onNetworkFailure(reason)
                    }
                }
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
                // WebVPN：门户登录完成后自动寻找并打开教务系统，之后与直连共用
                // 下方的学年读取与认证成功判定，不需要用户手动选择跳转。
                maybeAutoJumpFromPortal(view, url)
                // 登录页、验证码页和二次认证页不会触发；只有进入非登录教务页面
                // 且拿到 /jwglxt 路径的 JSESSIONID 才认为认证成功。
                                if (!academicTermsReadStarted && isAcademicTermsPage(url, accessMode)) {
                    academicTermsReadStarted = true
                    readAcademicTermsFromDom(
                        view = view,
                        onSuccess = { years ->
                            if (academicTermsReported) return@readAcademicTermsFromDom
                            academicTermsReported = true
                            onAcademicTerms(years)
                            manager.removeAllCookies(null)
                            onAuthenticated()
                        },
                        onFailure = { reason ->
                            if (academicTermsReported) return@readAcademicTermsFromDom
                            academicTermsReported = true
                            onAcademicTermsFailure(reason)
                            manager.removeAllCookies(null)
                            onAuthenticated()
                        }
                    )
                }
                tryReportAuthentication(url)
            }
        }
        // removeAllCookies 是异步操作。必须等回调完成后再打开入口，避免清理动作
        // 在登录过程中晚到，把刚刚由 CAS 下发的会话 Cookie 删除，形成 JW↔CAS 循环。
        // 这里仅清理 WebView 的临时 Cookie；内存中的直连/VPN 会话分别保存，不能清空另一入口。
        // 个别系统 WebView 在页面刚加载失败时可能不回调 removeAllCookies，导致切换
        // 入口后 WebView 永远停留在旧错误页；因此挂一个超时兜底，保证一定开始导航。
        var navigationStarted = false
        fun startNavigation() {
            if (navigationStarted) return
            navigationStarted = true
            val startUrl = when (accessMode) {
                ScutAccessMode.DIRECT -> DIRECT_ENTRY_URL
                ScutAccessMode.WEB_VPN -> WEB_VPN_URL
            }
            webView.post { webView.loadUrl(startUrl) }
        }
        manager.removeAllCookies {
            Log.d(TAG, "cookie cleanup completed accessMode=$accessMode")
            cookieStore.clearSession(accessMode)
            startNavigation()
        }
        webView.postDelayed({ startNavigation() }, COOKIE_CLEANUP_FALLBACK_DELAY_MS)
    }

    /**
     * 自动检测未触发时的备用检查。这里不读取账号、密码、验证码或页面内容，
     * 只校验当前官方教务页面和会话 Cookie。
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
        val isJwPage = isAuthenticatedJwPage(rawUrl, accessMode)

        if (!isJwPage) {
            val message = when {
                accessMode == ScutAccessMode.WEB_VPN && host == WEB_VPN_HOST ->
                    "当前仍在 WebVPN 门户；自动打开教务系统未成功，请在门户内打开教务系统后重试"
                else ->
                    "当前还不是已登录的教务系统页面，请在官方页面完成登录"
            }
            Log.w(TAG, "manual confirmation rejected mode=$accessMode location=${safeLocation(rawUrl)}")
            onFailure(message)
            return
        }

        val manager = CookieManager.getInstance()
        manager.flush()
        cookieStore.captureFromCookieManager(
            manager,
            cookieHosts(accessMode, rawUrl),
            extraPaths = pathAncestors(rawUrl),
            accessMode = accessMode
        )
        val baseUrl = when (accessMode) {
            ScutAccessMode.DIRECT -> DIRECT_BASE_URL.toHttpUrl()
            ScutAccessMode.WEB_VPN -> proxyBaseUrlFromPage(rawUrl)
        }
        val hasSession = when (accessMode) {
            ScutAccessMode.DIRECT -> cookieStore.has(
                JW_HOST,
                "JSESSIONID",
                "/jwglxt",
                accessMode
            )
            ScutAccessMode.WEB_VPN -> baseUrl != null &&
                cookieStore.cookieHeaderFor(
                    baseUrl.host,
                    baseUrl.encodedPath + "/jwglxt",
                    accessMode
                ).isNotBlank()
        }
        if (!hasSession) {
            Log.w(TAG, "manual confirmation missing JW session location=${safeLocation(rawUrl)}")
            onFailure("未检测到教务会话，请确认官方登录已成功后再试")
            return
        }

        if (baseUrl == null) {
            onFailure("无法识别 WebVPN 代理地址，请先在门户内打开教务系统")
            return
        }
        cookieStore.configureSession(baseUrl, accessMode)

        Log.i(TAG, "manual confirmation accepted mode=$accessMode requestHost=${baseUrl.host} " +
            "requestPrefix=${baseUrl.encodedPath}")
        // 认证结果已复制到进程内 Store，清理 WebView Cookie，避免会话持久化。
        manager.removeAllCookies(null)
        onAuthenticated()
    }

    private fun isAuthenticatedJwPage(
        rawUrl: String,
        accessMode: ScutAccessMode = ScutAccessMode.DIRECT
    ): Boolean = runCatching {
        val uri = URI(rawUrl)
        val host = uri.host?.lowercase()
        val path = uri.path.orEmpty()
        when (accessMode) {
            ScutAccessMode.DIRECT -> host == JW_HOST && path.startsWith("/jwglxt") &&
                !path.contains("/sso/login") && !path.contains("/login")
            ScutAccessMode.WEB_VPN -> host != null && isWebVpnHost(host) &&
                path.contains("/jwglxt") &&
                !path.contains("/sso/login") && !path.contains("/login")
        }
    }.getOrDefault(false)

    /** WebVPN 将原站地址放在自身路径下，例如 /<encoded-target>/jwglxt/...。 */
    private fun proxyBaseUrlFromPage(rawUrl: String): HttpUrl? = runCatching {
        val page = rawUrl.toHttpUrl()
        if (!isWebVpnHost(page.host)) return@runCatching null
        val markerIndex = page.encodedPath.indexOf("/jwglxt", ignoreCase = true)
        if (markerIndex < 0) return@runCatching null
        val prefix = page.encodedPath.substring(0, markerIndex).ifBlank { "/" }
        page.newBuilder()
            .encodedPath(if (prefix.endsWith('/')) prefix else "$prefix/")
            .query(null)
            .fragment(null)
            .build()
    }.getOrNull()

    /** 查询 WebView 当前代理 URL 的所有父路径，兼容 WebVPN 的路径型会话 Cookie。 */
    private fun pathAncestors(rawUrl: String): List<String> = runCatching {
        val path = URI(rawUrl).path.orEmpty()
        val segments = path.split('/').filter(String::isNotBlank)
        segments.indices.map { index ->
            "/" + segments.take(index + 1).joinToString("/")
        }
    }.getOrDefault(emptyList())

    private fun isWebVpnHost(host: String): Boolean =
        host == WEB_VPN_HOST || host.endsWith(".$WEB_VPN_HOST")

    private fun cookieHosts(accessMode: ScutAccessMode, rawUrl: String? = null): List<String> = when (accessMode) {
        ScutAccessMode.DIRECT -> listOf(CAS_HOST, JW_HOST)
        // WebVPN 代理会话 Cookie 位于 webvpn.scut.edu.cn；同时保留 CAS/JW，兼容
        // 门户跳转过程中短暂出现的学校原站 Cookie。
        ScutAccessMode.WEB_VPN -> buildList {
            add(WEB_VPN_HOST)
            runCatching { URI(rawUrl.orEmpty()).host?.lowercase() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
            add(CAS_HOST)
            add(JW_HOST)
        }.distinct()
    }

    private fun blockedMessage(accessMode: ScutAccessMode): String = when (accessMode) {
        ScutAccessMode.DIRECT -> "已阻止非 SCUT 直连域名跳转"
        ScutAccessMode.WEB_VPN -> "已阻止非学校 WebVPN 域名跳转"
    }

    private fun readAcademicTermsPage(
        view: WebView,
        baseUrl: HttpUrl,
        accessMode: ScutAccessMode
    ) {
        val target = baseUrl.newBuilder()
            .addPathSegments("jwglxt/kbcx/xskbcx_cxXskbcxIndex.html")
            .addQueryParameter("gnmkdm", COURSE_MODULE_CODE)
            .build()
        Log.d(TAG, "opening academic terms page mode=$accessMode")
        view.post { view.loadUrl(target.toString()) }
    }

    private fun readAcademicTermsFromDom(
        view: WebView,
        onSuccess: (List<RemoteAcademicYear>) -> Unit,
        onFailure: (String) -> Unit,
        attempt: Int = 0
    ) {
        // 正方页面的 xnm/xqm 下拉框通常由页面脚本异步填充，onPageFinished
        // 时可能还没有 option。这里返回原生数组（不要再 JSON.stringify），
        // 并在短时间内轮询，避免把“页面尚未填充”误报成“没有学年”。
        view.evaluateJavascript(
            """
            (() => Array.from(document.querySelectorAll('select')).map(select => ({
              id: select.id || '',
              name: select.name || '',
              options: Array.from(select.options || []).map(option => ({
                value: option.value || '',
                text: (option.textContent || '').trim()
              }))
            })))();
            """.trimIndent()
        ) { raw ->
            runCatching { ScutAcademicTermParser.parseWebViewJson(raw) }
                .onSuccess { years ->
                    Log.i(TAG, "academic terms DOM ready attempt=${attempt + 1} years=${years.size}")
                    onSuccess(years)
                }
                .onFailure { error ->
                    val nextAttempt = attempt + 1
                    if (nextAttempt < ACADEMIC_TERMS_MAX_ATTEMPTS) {
                        Log.d(
                            TAG,
                            "academic terms DOM not ready attempt=$nextAttempt " +
                                "type=${error.javaClass.simpleName}; retrying"
                        )
                        view.postDelayed(
                            { readAcademicTermsFromDom(view, onSuccess, onFailure, nextAttempt) },
                            ACADEMIC_TERMS_RETRY_DELAY_MS
                        )
                    } else {
                        Log.w(
                            TAG,
                            "academic terms DOM unavailable attempts=$nextAttempt " +
                                "type=${error.javaClass.simpleName}"
                        )
                        onFailure("教务页面暂未提供学年列表，请确认已进入课表查询后重试")
                    }
                }
        }
    }

    private fun isAcademicTermsPage(rawUrl: String, accessMode: ScutAccessMode): Boolean = runCatching {
        val page = rawUrl.toHttpUrl()
        page.encodedPath.contains("/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html", ignoreCase = true) &&
            (accessMode == ScutAccessMode.DIRECT || isWebVpnHost(page.host))
    }.getOrDefault(false)
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
                scheme == "https" && (
                    host == WEB_VPN_HOST || host.endsWith(".$WEB_VPN_HOST") ||
                        host in setOf(CAS_HOST, JW_HOST)
                    )
        }
    }.getOrDefault(false)

    fun cancel(webView: WebView) {
        webView.stopLoading()
        CookieManager.getInstance().removeAllCookies(null)
    }

    fun clear() = cookieStore.clear()
}



