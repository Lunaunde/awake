package com.example.awake.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebVpnPortalJumpTest {

    @Test
    fun portalHomePagesAreRecognized() {
        assertTrue(WebVpnPortalJump.isPortalHomePage("https://webvpn.scut.edu.cn/"))
        assertTrue(WebVpnPortalJump.isPortalHomePage("https://webvpn.scut.edu.cn/portal/index"))
        assertTrue(WebVpnPortalJump.isPortalHomePage("https://webvpn.scut.edu.cn/portal?tab=resources"))
        assertTrue(WebVpnPortalJump.isPortalHomePage("https://a.webvpn.scut.edu.cn/"))
    }

    @Test
    fun loginSsoAndProxiedBusinessPagesAreNotPortalHome() {
        assertFalse(WebVpnPortalJump.isPortalHomePage("https://webvpn.scut.edu.cn/login"))
        assertFalse(WebVpnPortalJump.isPortalHomePage("https://sso.scut.edu.cn/cas/login?service=..."))
        assertFalse(
            WebVpnPortalJump.isPortalHomePage(
                "https://webvpn.scut.edu.cn/https/77726476706e69737468656265737421f3e8/jwglxt/xtgl/index_initMenu.html"
            )
        )
        assertFalse(WebVpnPortalJump.isPortalHomePage("https://xsjw2018.jw.scut.edu.cn/jwglxt/"))
        assertFalse(WebVpnPortalJump.isPortalHomePage("https://example.com/"))
        assertFalse(WebVpnPortalJump.isPortalHomePage("not-a-url"))
    }

    @Test
    fun prefersLinksWhoseHrefCarriesJwMarkers() {
        val hits = listOf(
            WebVpnPortalJump.LinkHit("https://webvpn.scut.edu.cn/https/7772.../library/", "图书馆"),
            WebVpnPortalJump.LinkHit(
                "https://webvpn.scut.edu.cn/http/7772.../jwglxt/xtgl/index_initMenu.html",
                "正方教务系统"
            ),
            WebVpnPortalJump.LinkHit("https://webvpn.scut.edu.cn/https/7772.../kbcx/xskbcx.html", "课表查询")
        )
        val target = WebVpnPortalJump.selectJumpTarget(hits)
        assertEquals(
            "https://webvpn.scut.edu.cn/http/7772.../jwglxt/xtgl/index_initMenu.html",
            target?.href
        )
    }

    @Test
    fun prefersHigherPriorityKeywordTextWhenNoHrefMarker() {
        val genericFirst = listOf(
            WebVpnPortalJump.LinkHit("https://webvpn.scut.edu.cn/https/7772.../other1/", "网上办事大厅"),
            WebVpnPortalJump.LinkHit("https://webvpn.scut.edu.cn/https/7772.../jw/", "教务"),
            WebVpnPortalJump.LinkHit("https://webvpn.scut.edu.cn/https/7772.../jw2/", "教务系统")
        )
        assertEquals(
            "https://webvpn.scut.edu.cn/https/7772.../jw2/",
            WebVpnPortalJump.selectJumpTarget(genericFirst)?.href
        )

        val specificFirst = listOf(
            WebVpnPortalJump.LinkHit("https://webvpn.scut.edu.cn/https/7772.../a/", "本科教务系统"),
            WebVpnPortalJump.LinkHit("https://webvpn.scut.edu.cn/https/7772.../b/", "正方教务")
        )
        assertEquals(
            "https://webvpn.scut.edu.cn/https/7772.../a/",
            WebVpnPortalJump.selectJumpTarget(specificFirst)?.href
        )
    }

    @Test
    fun returnsNullWhenNoCandidateMatches() {
        val hits = listOf(
            WebVpnPortalJump.LinkHit("https://webvpn.scut.edu.cn/https/7772.../lib/", "图书馆"),
            WebVpnPortalJump.LinkHit("https://webvpn.scut.edu.cn/https/7772.../mail/", "邮箱")
        )
        assertNull(WebVpnPortalJump.selectJumpTarget(hits))
        assertNull(WebVpnPortalJump.selectJumpTarget(emptyList()))
    }

    @Test
    fun normalizesTextWhitespaceBeforeMatching() {
        val hits = listOf(
            WebVpnPortalJump.LinkHit("https://webvpn.scut.edu.cn/https/7772.../jw/", "教 务  系统（新）")
        )
        assertEquals(
            "https://webvpn.scut.edu.cn/https/7772.../jw/",
            WebVpnPortalJump.selectJumpTarget(hits)?.href
        )
    }
}
