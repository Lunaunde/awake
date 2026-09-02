package com.example.awake.data.update

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** GitHub Releases 上最新（非预发布）版本的信息。 */
data class GitHubRelease(
    val versionName: String,
    val versionCode: Int,
    /** 发布说明中约定的 APK SHA-256（小写 hex），用于下载后完整性校验。 */
    val apkSha256: String?,
    /** 说明、示例、上传的 APK 均可引用的发布页地址。 */
    val apkUrl: String?,
    val pageUrl: String,
    val notes: String,
    val publishedAt: String?
)

/**
 * 通过 GitHub Releases API 检测新版本。
 *
 * 仓库与 Release body 约定见 README「发布与自动更新」：
 * body 前几行写 `versionName: x`、`versionCode: N`、`apk-sha256: ...`，
 * 其余内容作为更新说明展示；APK 从 assets 中按 `.apk` 后缀识别。
 *
 * 公开仓库接口免鉴权，限制 60 次/小时（按 IP），足够手动检查使用。
 */
class GitHubReleaseChecker(
    private val repo: String = GitHubReleaseChecker.DEFAULT_REPO,
    private val client: OkHttpClient = GitHubReleaseChecker.defaultClient()
) {
    /** 拉取最新正式 Release；网络或解析失败时抛 IOException。 */
    @Throws(IOException::class)
    fun fetchLatestRelease(): GitHubRelease {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Awake-Android")
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("GitHub 返回 ${response.code}${if (text.isBlank()) "" else "：${text.take(120)}"}")
            }
            return parseRelease(JSONObject(text))
        }
    }

    companion object {
        const val DEFAULT_REPO = "Lunaunde/awake"

        private val METADATA_LINE = Regex("""^\s*(versionName|versionCode|apk-sha256)\s*[:：]""")
        private val VERSION_NAME_LINE = Regex("""(?m)^\s*versionName\s*[:：]\s*(\S+)""")
        private val VERSION_CODE_LINE = Regex("""(?m)^\s*versionCode\s*[:：]\s*(\d+)""")
        private val APK_SHA256_LINE = Regex("""(?m)^\s*apk-sha256\s*[:：]\s*([0-9A-Fa-f]{32,128})""")

        /** 旧版本号是否小于最新版本的 versionCode（versionCode 是唯一比较依据）。 */
        fun hasUpdate(latestVersionCode: Int, currentVersionCode: Int): Boolean =
            latestVersionCode > currentVersionCode

        /** 解析 /releases/latest 返回的 JSON（纯函数，便于单元测试）。 */
        fun parseRelease(json: JSONObject): GitHubRelease {
            val tag = json.optString("tag_name").trim()
            val body = json.optString("body").orEmpty()
            val versionName = VERSION_NAME_LINE
                .find(body)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: tag
            val versionCode = VERSION_CODE_LINE
                .find(body)?.groupValues?.get(1)?.toIntOrNull()
                ?: 0
            val apkSha256 = APK_SHA256_LINE
                .find(body)?.groupValues?.get(1)
                ?.lowercase()
                ?.takeIf { it.length == 64 }
            val apkUrl = runCatching {
                val assets = json.optJSONArray("assets") ?: return@runCatching null
                (0 until assets.length()).mapNotNull { index ->
                    assets.optJSONObject(index)
                        ?.optString("browser_download_url")
                        .orEmpty()
                        .takeIf { it.endsWith(".apk", ignoreCase = true) }
                }.firstOrNull()
            }.getOrNull()
            // 去掉约定元信息行，保留更新说明供界面展示。
            val notes = body.lines()
                .filterNot { METADATA_LINE.matches(it) }
                .joinToString("\n")
                .trim()
            return GitHubRelease(
                versionName = versionName,
                versionCode = versionCode,
                apkSha256 = apkSha256,
                apkUrl = apkUrl,
                pageUrl = json.optString("html_url")
                    .ifBlank { "https://github.com/$DEFAULT_REPO/releases" },
                notes = notes.ifBlank { "请在 GitHub Releases 页面查看更新内容" },
                publishedAt = json.optString("published_at").takeIf { it.isNotBlank() }
            )
        }

        internal fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}