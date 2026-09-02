package com.example.awake.data.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseCheckerTest {

    private fun releaseJson(
        tag: String = "v2.1.0",
        body: String,
        htmlUrl: String = "https://github.com/Lunaunde/awake/releases/tag/v2.1.0",
        assets: List<String> = listOf("https://github.com/Lunaunde/awake/releases/download/v2.1.0/awake-2.1.0.apk"),
        publishedAt: String = "2026-09-01T10:00:00Z"
    ): String = JSONObject()
        .put("tag_name", tag)
        .put("body", body)
        .put("html_url", htmlUrl)
        .put("published_at", publishedAt)
        .put(
            "assets",
            org.json.JSONArray(assets.map { url ->
                JSONObject().put("name", url.substringAfterLast('/')).put("browser_download_url", url)
            })
        )
        .toString()

    @Test
    fun parsesConventionalReleaseBody() {
        val body = """
            versionName: 2.1.0
            versionCode: 210
            apk-sha256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
            更新内容：
            - 修复课表导入数量统计
            - 课表网格颜色加深
        """.trimIndent()
        val release = GitHubReleaseChecker.parseRelease(JSONObject(releaseJson(body = body)))

        assertEquals("2.1.0", release.versionName)
        assertEquals(210, release.versionCode)
        assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", release.apkSha256)
        assertEquals("https://github.com/Lunaunde/awake/releases/download/v2.1.0/awake-2.1.0.apk", release.apkUrl)
        assertEquals("https://github.com/Lunaunde/awake/releases/tag/v2.1.0", release.pageUrl)
        assertEquals("2026-09-01T10:00:00Z", release.publishedAt)
        // 元信息行被剔除，只保留更新说明。
        assertFalse(release.notes.contains("versionName"))
        assertFalse(release.notes.contains("versionCode"))
        assertFalse(release.notes.contains("apk-sha256"))
        assertTrue(release.notes.contains("修复课表导入数量统计"))
        assertTrue(release.notes.contains("课表网格颜色加深"))
    }

    @Test
    fun fallsBackToTagNameAndZeroCodeWithoutMetadata() {
        val body = "一些没有约定字段的说明"
        val release = GitHubReleaseChecker.parseRelease(
            JSONObject(releaseJson(tag = "v2.1.0", body = body, assets = emptyList()))
        )
        assertEquals("v2.1.0", release.versionName)
        assertEquals(0, release.versionCode)
        assertNull(release.apkUrl)
        // body 非空时不使用兜底文案。
        assertEquals(body, release.notes)
    }

    @Test
    fun blankBodyUsesFallbackNotes() {
        val release = GitHubReleaseChecker.parseRelease(JSONObject(releaseJson(body = "  ")))
        assertEquals("请在 GitHub Releases 页面查看更新内容", release.notes)
    }

    @Test
    fun ignoresNonApkAssets() {
        val release = GitHubReleaseChecker.parseRelease(
            JSONObject(
                releaseJson(
                    body = "versionName: 2.1.0\nversionCode: 210",
                    assets = listOf(
                        "https://github.com/Lunaunde/awake/releases/download/v2.1.0/awake-2.1.0.apk.asc",
                        "https://github.com/Lunaunde/awake/releases/download/v2.1.0/awake-2.1.0.apk"
                    )
                )
            )
        )
        assertEquals(
            "https://github.com/Lunaunde/awake/releases/download/v2.1.0/awake-2.1.0.apk",
            release.apkUrl
        )
    }

    @Test
    fun comparesVersionCodes() {
        assertTrue(GitHubReleaseChecker.hasUpdate(latestVersionCode = 210, currentVersionCode = 200))
        assertFalse(GitHubReleaseChecker.hasUpdate(latestVersionCode = 200, currentVersionCode = 200))
        assertFalse(GitHubReleaseChecker.hasUpdate(latestVersionCode = 190, currentVersionCode = 200))
    }
}