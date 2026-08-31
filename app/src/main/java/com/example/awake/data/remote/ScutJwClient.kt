package com.example.awake.data.remote

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import android.util.Log
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

class ScutHttpException(val kind: Kind, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Kind { NETWORK, SESSION_EXPIRED, RATE_LIMITED, SERVER, MAINTENANCE, INVALID_RESPONSE }
}

enum class SessionAvailabilityState {
    NOT_CONFIGURED,
    AVAILABLE,
    EXPIRED,
    NETWORK_ERROR,
    SERVER_ERROR
}

data class SessionAvailability(
    val accessMode: ScutAccessMode,
    val state: SessionAvailabilityState,
    val detail: String? = null
)

class ScutJwClient(
    private val cookieStore: SessionCookieStore,
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: HttpUrl = CasWebViewCoordinator.DIRECT_BASE_URL.toHttpUrl()
) {
    /**
     * 读取登录后课表查询页中的真实学年和学期选项。
     * 仍按直连优先、VPN 备用；失败不会修改本地课表或会话。
     */
    fun fetchAcademicTerms(): List<RemoteAcademicYear> {
        val candidates = cookieStore.availableAccessModes()
        if (candidates.isEmpty()) {
            throw ScutHttpException(ScutHttpException.Kind.SESSION_EXPIRED, "教务会话已失效，请重新登录")
        }

        var lastError: ScutHttpException? = null
        candidates.forEach { accessMode ->
            try {
                return fetchAcademicTermsWithSession(accessMode)
            } catch (error: ScutHttpException) {
                lastError = error
                if (candidates.size > 1 && accessMode != candidates.last()) {
                    Log.w(TAG, "academic terms failed mode=$accessMode kind=${error.kind}; trying next session")
                }
            }
        }
        throw (lastError ?: ScutHttpException(
            ScutHttpException.Kind.INVALID_RESPONSE,
            "暂时无法读取教务系统的学年列表"
        ))
    }

    private fun fetchAcademicTermsWithSession(accessMode: ScutAccessMode): List<RemoteAcademicYear> {
        val requestBaseUrl = cookieStore.configuredBaseUrl(baseUrl, accessMode)
        // 正方教务的课表查询首页是菜单模块页面，必须带上模块码；
        // 不带 gnmkdm 时服务端会直接返回 500（看起来像“没有学年”，其实是入口参数缺失）。
        val queryPath = "/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html"
        val apiPath = requestBaseUrl.encodedPath.trimEnd('/') + queryPath
        val cookieHeader = cookieStore.cookieHeaderFor(requestBaseUrl.host, apiPath, accessMode)
        if (cookieHeader.isBlank()) {
            throw ScutHttpException(ScutHttpException.Kind.SESSION_EXPIRED, "教务会话已失效，请重新登录")
        }
        val url = requestBaseUrl.newBuilder()
            .addPathSegments("jwglxt/kbcx/xskbcx_cxXskbcxIndex.html")
            .addQueryParameter("gnmkdm", COURSE_MODULE_CODE)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/html, application/xhtml+xml, */*")
            .header("Referer", requestBaseUrl.toString())
            .header("Cookie", cookieHeader)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                Log.d(TAG, "academic terms response mode=$accessMode code=${response.code} " +
                    "contentType=${response.header("Content-Type")}")
                if (response.code in 300..399 || response.code == 401 || response.code == 403) {
                    throw ScutHttpException(ScutHttpException.Kind.SESSION_EXPIRED, "教务会话已失效，请重新登录")
                }
                if (response.code == 429) {
                    throw ScutHttpException(ScutHttpException.Kind.RATE_LIMITED, "请求过于频繁，请稍后再试")
                }
                if (!response.isSuccessful) {
                    throw ScutHttpException(ScutHttpException.Kind.SERVER, "教务系统暂时不可用（${response.code}）")
                }
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) {
                    throw ScutHttpException(ScutHttpException.Kind.INVALID_RESPONSE, "教务系统返回的学年列表为空")
                }
                if (looksLikeLoginPage(text)) {
                    throw ScutHttpException(ScutHttpException.Kind.SESSION_EXPIRED, "教务会话已失效，请重新登录")
                }
                if (text.contains("系统维护") || text.contains("系统升级")) {
                    throw ScutHttpException(ScutHttpException.Kind.MAINTENANCE, "教务系统返回了维护页面")
                }
                return try {
                    ScutAcademicTermParser.parse(text)
                } catch (error: IllegalArgumentException) {
                    throw ScutHttpException(
                        ScutHttpException.Kind.INVALID_RESPONSE,
                        "教务系统暂时没有提供可识别的学年列表",
                        error
                    )
                }
            }
        } catch (error: ScutHttpException) {
            Log.w(TAG, "academic terms mode=$accessMode classified kind=${error.kind} message=${safeError(error)}")
            throw error
        } catch (error: IOException) {
            Log.e(TAG, "academic terms network failure mode=$accessMode type=${error.javaClass.simpleName}")
            throw ScutHttpException(ScutHttpException.Kind.NETWORK, "网络连接失败，请检查网络后重试", error)
        } catch (error: Exception) {
            Log.e(TAG, "academic terms processing failure mode=$accessMode type=${error.javaClass.simpleName}")
            throw ScutHttpException(ScutHttpException.Kind.INVALID_RESPONSE, "教务响应处理失败", error)
        }
    }

    /**
     * 只探测当前入口的登录会话，不读取或记录 Cookie 内容，也不会改动本地课表。
     * 教务首页比具体学期接口更适合作为状态探测：不需要用户先选择学年学期。
     */
    /** 检查已保存的直连/VPN会话；顺序保持直连优先。 */
    fun probeSessions(): List<SessionAvailability> =
        ScutAccessMode.values()
            .filter { cookieStore.availableAccessModes().contains(it) }
            .map { probeSession(it) }

    fun probeSession(accessMode: ScutAccessMode): SessionAvailability {
        if (!cookieStore.availableAccessModes().contains(accessMode)) {
            return SessionAvailability(accessMode, SessionAvailabilityState.NOT_CONFIGURED)
        }

        val requestBaseUrl = cookieStore.configuredBaseUrl(baseUrl, accessMode)
        val apiPath = requestBaseUrl.encodedPath.trimEnd('/') + "/jwglxt/xtgl/index_initMenu.html"
        val cookieHeader = cookieStore.cookieHeaderFor(requestBaseUrl.host, apiPath, accessMode)
        if (cookieHeader.isBlank()) {
            return SessionAvailability(accessMode, SessionAvailabilityState.NOT_CONFIGURED)
        }

        val url = requestBaseUrl.newBuilder()
            .addPathSegments("jwglxt/xtgl/index_initMenu.html")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/html, application/xhtml+xml, */*")
            .header("Referer", requestBaseUrl.toString())
            .header("Cookie", cookieHeader)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                Log.d(TAG, "session probe mode=$accessMode code=${response.code}")
                when {
                    response.code in 300..399 || response.code == 401 || response.code == 403 ->
                        SessionAvailability(accessMode, SessionAvailabilityState.EXPIRED, "官方系统要求重新登录")
                    response.code == 429 ->
                        SessionAvailability(accessMode, SessionAvailabilityState.SERVER_ERROR, "请求过于频繁")
                    !response.isSuccessful ->
                        SessionAvailability(accessMode, SessionAvailabilityState.SERVER_ERROR, "官方系统暂时不可用")
                    else -> {
                        val body = response.body?.string().orEmpty()
                        if (body.isBlank() || looksLikeLoginPage(body)) {
                            SessionAvailability(accessMode, SessionAvailabilityState.EXPIRED, "登录会话已失效")
                        } else {
                            SessionAvailability(accessMode, SessionAvailabilityState.AVAILABLE)
                        }
                    }
                }
            }
        } catch (error: IOException) {
            Log.w(TAG, "session probe network failure mode=$accessMode type=${error.javaClass.simpleName}")
            SessionAvailability(accessMode, SessionAvailabilityState.NETWORK_ERROR, "网络暂时不可达")
        } catch (error: Exception) {
            Log.w(TAG, "session probe processing failure mode=$accessMode type=${error.javaClass.simpleName}")
            SessionAvailability(accessMode, SessionAvailabilityState.SERVER_ERROR, "状态检查失败")
        }
    }

    fun fetchSchedule(xnm: Int, xqm: String): ScutSchedulePayload {
        if (xnm <= 0 || xqm.isBlank()) {
            throw ScutHttpException(ScutHttpException.Kind.INVALID_RESPONSE, "学年或学期参数无效")
        }
        val candidates = cookieStore.availableAccessModes()
        if (candidates.isEmpty()) {
            throw ScutHttpException(ScutHttpException.Kind.SESSION_EXPIRED, "教务会话已失效，请重新登录")
        }

        var lastError: ScutHttpException? = null
        candidates.forEach { accessMode ->
            try {
                return fetchScheduleWithSession(xnm, xqm, accessMode)
            } catch (error: ScutHttpException) {
                lastError = error
                if (candidates.size > 1 && accessMode != candidates.last()) {
                    Log.w(TAG, "schedule session failed mode=$accessMode kind=${error.kind}; trying next session")
                }
            }
        }
        throw (lastError ?: ScutHttpException(
            ScutHttpException.Kind.SESSION_EXPIRED,
            "教务会话已失效，请重新登录"
        ))
    }

    private fun fetchScheduleWithSession(
        xnm: Int,
        xqm: String,
        accessMode: ScutAccessMode
    ): ScutSchedulePayload {
        val requestBaseUrl = cookieStore.configuredBaseUrl(baseUrl, accessMode)
        val apiPath = requestBaseUrl.encodedPath.trimEnd('/') + "/jwglxt/kbcx/xskbcx_cxXsKb.html"
        val cookieHeader = cookieStore.cookieHeaderFor(requestBaseUrl.host, apiPath, accessMode)
        Log.d(TAG, "schedule request mode=$accessMode host=${requestBaseUrl.host} path=$apiPath " +
            "cookieNames=${cookieNames(cookieHeader)} cookieCount=${cookieCount(cookieHeader)}")
        if (cookieHeader.isBlank()) {
            throw ScutHttpException(ScutHttpException.Kind.SESSION_EXPIRED, "教务会话已失效，请重新登录")
        }
        val url = requestBaseUrl.newBuilder()
            .addPathSegments("jwglxt/kbcx/xskbcx_cxXsKb.html")
            .addQueryParameter("xnm", xnm.toString())
            .addQueryParameter("xqm", xqm)
            .addQueryParameter("xszd", "")
            .addQueryParameter("kblx", "1")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/plain, */*")
            .header(
                "Referer",
                requestBaseUrl.newBuilder().addPathSegments("jwglxt/kbcx/xskbcx_cxXskbcxIndex.html").build().toString()
            )
            .header("Cookie", cookieHeader)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                Log.d(TAG, "schedule response mode=$accessMode code=${response.code} contentType=${response.header("Content-Type")} " +
                    "url=${safeLocation(response.request.url.toString())}")
                if (response.code in 300..399 || response.code == 401 || response.code == 403) {
                    throw ScutHttpException(ScutHttpException.Kind.SESSION_EXPIRED, "教务会话已失效，请重新登录")
                }
                if (response.code == 429) {
                    throw ScutHttpException(ScutHttpException.Kind.RATE_LIMITED, "请求过于频繁，请稍后再试")
                }
                if (!response.isSuccessful) {
                    throw ScutHttpException(ScutHttpException.Kind.SERVER, "教务系统暂时不可用（${response.code}）")
                }
                val text = response.body?.string().orEmpty()
                Log.d(TAG, "schedule response body length=${text.length} kind=${classifyBody(text)}")
                if (text.isBlank()) {
                    throw ScutHttpException(ScutHttpException.Kind.INVALID_RESPONSE, "教务系统返回为空")
                }
                when (classifyBody(text)) {
                    BodyKind.SESSION -> throw ScutHttpException(
                        ScutHttpException.Kind.SESSION_EXPIRED,
                        "教务会话已失效，请重新登录"
                    )
                    BodyKind.MAINTENANCE -> throw ScutHttpException(
                        ScutHttpException.Kind.MAINTENANCE,
                        "教务系统返回了维护页面"
                    )
                    BodyKind.JSON -> Unit
                }
                return try {
                    ScutSchedulePayload.fromJson(text)
                } catch (error: Exception) {
                    throw ScutHttpException(ScutHttpException.Kind.INVALID_RESPONSE, "教务系统返回格式无法识别", error)
                }
            }
        } catch (error: ScutHttpException) {
            Log.w(TAG, "schedule request mode=$accessMode classified kind=${error.kind} message=${safeError(error)}")
            throw error
        } catch (error: IOException) {
            Log.e(TAG, "schedule network failure mode=$accessMode type=${error.javaClass.simpleName} message=${safeError(error)}")
            throw ScutHttpException(ScutHttpException.Kind.NETWORK, "网络连接失败，请检查网络后重试", error)
        } catch (error: Exception) {
            Log.e(TAG, "schedule response processing failure mode=$accessMode type=${error.javaClass.simpleName} message=${safeError(error)}")
            throw ScutHttpException(ScutHttpException.Kind.INVALID_RESPONSE, "教务响应处理失败", error)
        }
    }

    private enum class BodyKind { JSON, SESSION, MAINTENANCE }

    private fun classifyBody(text: String): BodyKind {
        val normalized = text.lowercase()
        if (normalized.contains("cas登录") || normalized.contains("cas/login") || normalized.contains("请先登录")) {
            return BodyKind.SESSION
        }
        if (normalized.trimStart().startsWith("<html") || normalized.contains("系统维护") || normalized.contains("系统升级")) {
            return BodyKind.MAINTENANCE
        }
        return BodyKind.JSON
    }

    private fun looksLikeLoginPage(text: String): Boolean {
        val normalized = text.lowercase()
        return normalized.contains("cas/login") ||
            normalized.contains("请先登录") ||
            normalized.contains("统一身份认证") && normalized.contains("password")
    }

    companion object {
        private const val TAG = "AwakeScutJw"
        private const val COURSE_MODULE_CODE = "N2151"

        private fun cookieNames(header: String): String = header
            .split(';')
            .mapNotNull { it.trim().substringBefore('=').takeIf(String::isNotBlank) }
            .distinct()
            .joinToString(",")
            .ifBlank { "<none>" }

        private fun cookieCount(header: String): Int =
            header.split(';').count { it.trim().contains('=') }

        private fun safeError(error: Throwable): String =
            error.message.orEmpty()
                .replace(Regex("""(?i)(password|passwd|pwd|ticket|token|captcha|code|lt|rsa)\s*[=:]\s*[^,; ]+"""), "$1=<redacted>")
                .replace(Regex("""\s+"""), " ")
                .take(180)
                .ifBlank { "<no-message>" }

        private fun safeLocation(rawUrl: String): String = runCatching {
            val uri = URI(rawUrl)
            "${uri.host ?: "<no-host>"}${uri.path.orEmpty().ifBlank { "/" }}"
        }.getOrElse { "<invalid-url>" }

        private fun defaultClient() = OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

