package com.example.awake.data.remote

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class ScutHttpException(val kind: Kind, message: String) : IOException(message) {
    enum class Kind { NETWORK, SESSION_EXPIRED, RATE_LIMITED, SERVER, MAINTENANCE, INVALID_RESPONSE }
}

class ScutJwClient(private val cookieStore: SessionCookieStore) {
    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun fetchSchedule(xnm: Int, xqm: String): ScutSchedulePayload {
        val url = "https://xsjw2018.jw.scut.edu.cn/jwglxt/kbcx/xskbcx_cxXsKb.html?xnm=$xnm&xqm=$xqm&xszd=&kblx=1"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", "https://xsjw2018.jw.scut.edu.cn/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html")
            .header("Cookie", cookieStore.cookieHeaderFor("xsjw2018.jw.scut.edu.cn"))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403 || response.code == 302) throw ScutHttpException(ScutHttpException.Kind.SESSION_EXPIRED, "教务会话已失效，请重新登录")
                if (response.code == 429) throw ScutHttpException(ScutHttpException.Kind.RATE_LIMITED, "请求过于频繁，请稍后再试")
                if (!response.isSuccessful) throw ScutHttpException(ScutHttpException.Kind.SERVER, "教务系统暂时不可用（${response.code}）")
                val text = response.body?.string().orEmpty()
                if (text.contains("系统维护") || text.contains("登录")) throw ScutHttpException(ScutHttpException.Kind.MAINTENANCE, "教务系统返回了维护或登录页面")
                return ScutSchedulePayload.fromJson(text)
            }
        } catch (e: ScutHttpException) { throw e }
        catch (e: Exception) { throw ScutHttpException(ScutHttpException.Kind.NETWORK, "网络连接失败，请检查网络后重试") }
    }
}
