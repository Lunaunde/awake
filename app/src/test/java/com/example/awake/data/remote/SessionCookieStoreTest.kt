package com.example.awake.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCookieStoreTest {
    @Test
    fun matchesDomainAndPath() {
        val store = SessionCookieStore()
        store.put("jw.example.com", "/jwglxt", "JSESSIONID", "abc")
        store.put("example.com", "/", "ROOT", "root")

        assertTrue(store.has("jw.example.com", "JSESSIONID", "/jwglxt/kbcx"))
        assertFalse(store.has("jw.example.com", "JSESSIONID", "/other"))
        assertEquals("JSESSIONID=abc; ROOT=root", store.cookieHeaderFor("jw.example.com", "/jwglxt/kbcx"))
        assertEquals("ROOT=root", store.cookieHeaderFor("api.example.com", "/"))
    }

    @Test
    fun prefersSpecificPathWhenSameCookieNameExists() {
        val store = SessionCookieStore()
        store.put("jw.example.com", "/", "JSESSIONID", "old")
        store.put("jw.example.com", "/jwglxt", "JSESSIONID", "current")

        assertEquals("JSESSIONID=current", store.cookieHeaderFor("jw.example.com", "/jwglxt/kbcx"))
    }

    @Test
    fun expiredCookiesAreRemoved() {
        val store = SessionCookieStore()
        store.put("example.com", "/", "expired", "x", System.currentTimeMillis() - 1)
        store.put("example.com", "/", "active", "y", System.currentTimeMillis() + 60_000)

        assertFalse(store.has("example.com", "expired"))
        assertEquals("active=y", store.cookieHeaderFor("example.com"))
    }

    @Test
    fun clearMemoryRemovesAllCookies() {
        val store = SessionCookieStore()
        store.put("example.com", "/", "a", "b")
        store.clearMemory()
        assertTrue(store.isEmpty())
        assertEquals("", store.cookieHeaderFor("example.com"))
    }

    @Test
    fun directAndVpnSessionsAreKeptSeparately() {
        val store = SessionCookieStore()
        store.put(
            "direct.example.com",
            "/jwglxt",
            "JSESSIONID",
            "direct-session",
            accessMode = ScutAccessMode.DIRECT
        )
        store.put(
            "vpn.example.com",
            "/proxy/jwglxt",
            "JSESSIONID",
            "vpn-session",
            accessMode = ScutAccessMode.WEB_VPN
        )

        assertEquals(
            "JSESSIONID=direct-session",
            store.cookieHeaderFor("direct.example.com", "/jwglxt/kbcx", ScutAccessMode.DIRECT)
        )
        assertEquals(
            "",
            store.cookieHeaderFor("direct.example.com", "/jwglxt/kbcx", ScutAccessMode.WEB_VPN)
        )
        assertEquals(
            "JSESSIONID=vpn-session",
            store.cookieHeaderFor("vpn.example.com", "/proxy/jwglxt/kbcx", ScutAccessMode.WEB_VPN)
        )

        store.clearSession(ScutAccessMode.WEB_VPN)
        assertFalse(store.isEmpty(ScutAccessMode.DIRECT))
        assertTrue(store.isEmpty(ScutAccessMode.WEB_VPN))
    }
}
