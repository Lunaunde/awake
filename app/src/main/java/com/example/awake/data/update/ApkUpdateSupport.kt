package com.example.awake.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 版本更新的下载、完整性校验与安装启动。
 *
 * - 下载到 App 私有外部存储（updates/），无需存储权限；
 * - 下载完成后用 Release 约定的 apk-sha256 做完整性校验，不匹配则丢弃；
 * - 通过 FileProvider + 系统安装器启动安装（用户仍需在系统弹窗确认，
 *   普通应用无法静默安装）。
 */
object ApkUpdateSupport {

    /** 在后台下载 APK 到私有目录并校验；[onProgress] 为 (已下载字节, 总字节)。 */
    suspend fun downloadApk(
        context: Context,
        url: String,
        expectedSha256: String?,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
        val target = File(dir, "awake-update.apk")
        val client = OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.MINUTES)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("下载失败：GitHub 返回 ${response.code}")
            }
            val body = response.body ?: throw IOException("下载失败：响应为空")
            val total = body.contentLength()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE.coerceAtMost(64 * 1024))
            var downloaded = 0L
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
        }
        // 校验完整性：发布说明里有约定 sha256 时强制校验。
        val actual = sha256(target)
        if (expectedSha256 != null && !expectedSha256.equals(actual, ignoreCase = true)) {
            target.delete()
            throw IOException("下载文件校验失败（SHA-256 不匹配），已删除，请稍后重试")
        }
        target
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 用系统安装器安装已下载的 APK（用户需确认；返回是否成功发起）。 */
    fun installApk(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}