package com.example.awake.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CasWebViewCoordinatorTest {
    private val coordinator = CasWebViewCoordinator(SessionCookieStore())

    @Test
    fun directModeAllowsSchoolHttpEntryAndCasHttps() {
        assertTrue(
            coordinator.isAllowed(
                "http://${CasWebViewCoordinator.JW_HOST}/jwglxt/sso/login",
                ScutAccessMode.DIRECT
            )
        )
        assertTrue(
            coordinator.isAllowed(
                "https://${CasWebViewCoordinator.CAS_HOST}/cas/login",
                ScutAccessMode.DIRECT
            )
        )
    }

    @Test
    fun directModeRejectsOtherHttpAndLookalikeHosts() {
        assertFalse(coordinator.isAllowed("http://${CasWebViewCoordinator.CAS_HOST}/cas/login", ScutAccessMode.DIRECT))
        assertFalse(coordinator.isAllowed("http://example.com/", ScutAccessMode.DIRECT))
        assertFalse(coordinator.isAllowed("https://evil.${CasWebViewCoordinator.JW_HOST}/", ScutAccessMode.DIRECT))
        assertFalse(coordinator.isAllowed("https://${CasWebViewCoordinator.JW_HOST}:8443/", ScutAccessMode.DIRECT))
    }

    @Test
    fun webVpnModeAllowsOfficialPortalAndHttpsSchoolHosts() {
        assertTrue(coordinator.isAllowed(CasWebViewCoordinator.WEB_VPN_URL, ScutAccessMode.WEB_VPN))
        assertTrue(
            coordinator.isAllowed(
                "https://${CasWebViewCoordinator.CAS_HOST}/cas/login",
                ScutAccessMode.WEB_VPN
            )
        )
        assertTrue(
            coordinator.isAllowed(
                "https://${CasWebViewCoordinator.JW_HOST}/jwglxt/",
                ScutAccessMode.WEB_VPN
            )
        )
    }

    @Test
    fun webVpnModeRejectsExternalAndPlainHttpUrls() {
        assertFalse(coordinator.isAllowed("http://${CasWebViewCoordinator.JW_HOST}/", ScutAccessMode.WEB_VPN))
        assertFalse(coordinator.isAllowed("https://example.com/", ScutAccessMode.WEB_VPN))
        assertFalse(coordinator.isAllowed("https://${CasWebViewCoordinator.WEB_VPN_HOST}@example.com/", ScutAccessMode.WEB_VPN))
        assertFalse(coordinator.isAllowed("not-a-url", ScutAccessMode.WEB_VPN))
    }
}