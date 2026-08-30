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

class ScutJwClient(
    private val cookieStore: SessionCookieStore,
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: HttpUrl = CasWebViewCoordinator.DIRECT_BASE_URL.toHttpUrl()
) {
    fun fetchSchedule(xnm: Int, xqm: String): ScutSchedulePayload {
        if (xnm <= 0 || xqm.isBlank()) {
            throw ScutHttpException(ScutHttpException.Kind.INVALID_RESPONSE, "学年或学期参数无效")
        }
        val cookieHeader = cookieStore.cookieHeaderFor(baseUrl.host, "/jwglxt")
        Log.d(TAG, "schedule request host=${baseUrl.host} path=/jwglxt/kbcx/xskbcx_cxXsKb.html " +
            "cookieNames=${cookieNames(cookieHeader)} cookieCount=${cookieCount(cookieHeader)}")
        if (cookieHeader.isBlank()) {
            throw ScutHttpException(ScutHttpException.Kind.SESSION_EXPIRED, "教务会话已失效，请重新登录")
        }
        val url = baseUrl.newBuilder()
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
                baseUrl.newBuilder().addPathSegments("jwglxt/kbcx/xskbcx_cxXskbcxIndex.html").build().toString()
            )
            .header("Cookie", cookieHeader)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                Log.d(TAG, "schedule response code=${response.code} contentType=${response.header("Content-Type")} " +
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
            Log.w(TAG, "schedule request classified kind=${error.kind} message=${safeError(error)}")
            throw error
        } catch (error: IOException) {
            Log.e(TAG, "schedule network failure type=${error.javaClass.simpleName} message=${safeError(error)}")
            throw ScutHttpException(ScutHttpException.Kind.NETWORK, "网络连接失败，请检查网络后重试", error)
        } catch (error: Exception) {
            Log.e(TAG, "schedule response processing failure type=${error.javaClass.simpleName} message=${safeError(error)}")
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

    companion object {
        private const val TAG = "AwakeScutJw"

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
