package com.example.awake.data.remote

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.HttpCookie

/** 仅在内存中保存会话 Cookie，进程结束即丢弃。 */
class SessionCookieStore {
    private val cookies = linkedMapOf<String, MutableList<HttpCookie>>()

    fun captureFromCookieManager(manager: CookieManager, hosts: List<String>) {
        hosts.forEach { host ->
            manager.getCookie("https://$host")?.split(';')?.forEach { item ->
                val parts = item.trim().split('=', limit = 2)
                if (parts.size == 2) put(host, "/", parts[0], parts[1])
            }
        }
    }

    fun put(domain: String, path: String, name: String, value: String) {
        val list = cookies.getOrPut(domain) { mutableListOf() }
        list.removeAll { it.name == name }
        list += HttpCookie(name, value).apply { this.path = path }
    }

    fun cookieHeaderFor(host: String): String = cookies.entries
        .filter { host == it.key || host.endsWith(".${it.key}") }
        .flatMap { it.value }
        .joinToString("; ") { "${it.name}=${it.value}" }

    fun clear() {
        cookies.clear()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    fun isEmpty() = cookies.isEmpty()
}

class CasWebViewCoordinator(private val cookieStore: SessionCookieStore) {
    companion object {
        const val CAS_HOST = "sso.scut.edu.cn"
        const val JW_HOST = "xsjw2018.jw.scut.edu.cn"
        const val SERVICE_URL = "https://xsjw2018.jw.scut.edu.cn/jwglxt/sso/login"
        const val LOGIN_URL = "https://sso.scut.edu.cn/cas/login?service=https%3A%2F%2Fxsjw2018.jw.scut.edu.cn%2Fjwglxt%2Fsso%2Flogin"
    }

    fun attach(webView: WebView, onAuthenticated: () -> Unit, onBlocked: (String) -> Unit) {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.userAgentString = "Awake/1.0 (Android)"
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return if (isAllowed(url)) false else { onBlocked("已阻止外部跳转"); true }
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (!isAllowed(url)) return
                if (url.contains("/jwglxt") && !url.contains("/login")) {
                    CookieManager.getInstance().flush()
                    cookieStore.captureFromCookieManager(CookieManager.getInstance(), listOf(CAS_HOST, JW_HOST))
                    if (!cookieStore.isEmpty()) onAuthenticated()
                }
            }
        }
        webView.loadUrl(LOGIN_URL)
    }

    fun isAllowed(url: String): Boolean = runCatching {
        val host = java.net.URI(url).host ?: return false
        host == CAS_HOST || host == JW_HOST
    }.getOrDefault(false)

    fun clear() = cookieStore.clear()
}
