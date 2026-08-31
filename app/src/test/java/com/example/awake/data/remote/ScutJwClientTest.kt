package com.example.awake.data.remote

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ScutJwClientTest {
    private lateinit var server: MockWebServer
    private lateinit var vpnServer: MockWebServer
    private lateinit var cookies: SessionCookieStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        vpnServer = MockWebServer()
        vpnServer.start()
        cookies = SessionCookieStore()
        cookies.put(server.url("/").host, "/jwglxt", "JSESSIONID", "test-session")
    }

    @After
    fun tearDown() {
        server.shutdown()
        vpnServer.shutdown()
    }

    @Test
    fun fetchesJsonSchedule() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"xsxx":{"xh":"20201234","xm":"张三"},"kbList":[{"kcmc":"高等数学","xqj":"1","jcs":"1-2","zcd":"1-16"}]}
        """.trimIndent()))

        val payload = client().fetchSchedule(2026, "3")
        assertEquals("张三", payload.student?.name)
        assertEquals("高等数学", payload.courses.single().name)
        assertEquals("JSESSIONID=test-session", server.takeRequest().getHeader("Cookie"))
    }

    @Test
    fun redirectMeansSessionExpired() {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/cas/login"))
        assertKind(ScutHttpException.Kind.SESSION_EXPIRED) { client().fetchSchedule(2026, "3") }
    }

    @Test
    fun unauthorizedAndRateLimitAreClassified() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertKind(ScutHttpException.Kind.SESSION_EXPIRED) { client().fetchSchedule(2026, "3") }
        server.enqueue(MockResponse().setResponseCode(429))
        assertKind(ScutHttpException.Kind.RATE_LIMITED) { client().fetchSchedule(2026, "3") }
    }

    @Test
    fun serverAndPayloadErrorsAreClassified() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertKind(ScutHttpException.Kind.SERVER) { client().fetchSchedule(2026, "3") }
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>系统维护</html>"))
        assertKind(ScutHttpException.Kind.MAINTENANCE) { client().fetchSchedule(2026, "3") }
        server.enqueue(MockResponse().setResponseCode(200).setBody("not-json"))
        assertKind(ScutHttpException.Kind.INVALID_RESPONSE) { client().fetchSchedule(2026, "3") }
    }

    @Test
    fun missingSessionDoesNotSendRequest() {
        val empty = SessionCookieStore()
        val client = ScutJwClient(empty, baseUrl = server.url("/").toString().toHttpUrl())
        assertKind(ScutHttpException.Kind.SESSION_EXPIRED) { client.fetchSchedule(2026, "3") }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun probesConfiguredDirectSession() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><body>已登录教务首页</body></html>"))

        val result = client().probeSession(ScutAccessMode.DIRECT)

        assertEquals(SessionAvailabilityState.AVAILABLE, result.state)
        assertEquals("JSESSIONID=test-session", server.takeRequest().getHeader("Cookie"))
    }

    @Test
    fun probeClassifiesLoginPageAsExpired() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>CAS/login 请先登录</html>"))

        val result = client().probeSession(ScutAccessMode.DIRECT)

        assertEquals(SessionAvailabilityState.EXPIRED, result.state)
        assertTrue(result.detail.orEmpty().isNotBlank())
    }

    @Test
    fun unconfiguredVpnSessionIsNotProbed() {
        val result = client().probeSession(ScutAccessMode.WEB_VPN)

        assertEquals(SessionAvailabilityState.NOT_CONFIGURED, result.state)
        assertEquals(0, vpnServer.requestCount)
    }

    @Test
    fun fallsBackToVpnWhenDirectSessionFails() {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/cas/login"))
        vpnServer.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"xsxx":{"xh":"20201234","xm":"张三"},"kbList":[{"kcmc":"大学英语","xqj":"2","jcs":"3-4","zcd":"1-16"}]}
        """.trimIndent()))

        cookies.put(
            vpnServer.url("/").host,
            "/jwglxt",
            "JSESSIONID",
            "vpn-session",
            accessMode = ScutAccessMode.WEB_VPN
        )
        cookies.configureSession(vpnServer.url("/").toString().toHttpUrl(), ScutAccessMode.WEB_VPN)

        val payload = client().fetchSchedule(2026, "3")

        assertEquals("大学英语", payload.courses.single().name)
        assertEquals(1, server.requestCount)
        assertEquals(1, vpnServer.requestCount)
        assertEquals("JSESSIONID=vpn-session", vpnServer.takeRequest().getHeader("Cookie"))
    }

    @Test
    fun parsesAcademicTermsFromAuthenticatedQueryPage() {
        server.enqueue(MockResponse().setBody("""
            <select id="xnm" name="xnm">
              <option value="2026">2026-2027</option>
              <option value="2025" selected>2025-2026</option>
            </select>
            <select id="xqm" name="xqm">
              <option value="3">1</option>
              <option value="12">2</option>
              <option value="16">小学期</option>
            </select>
        """.trimIndent()))

        val years = client().fetchAcademicTerms()

        assertEquals(listOf(2026, 2025), years.map(RemoteAcademicYear::xnm))
        assertEquals("2026-2027", years.first().label)
        assertEquals(listOf("3", "12", "16"), years.first().semesters.map(RemoteSemester::xqm))
        assertEquals("第1学期", years.first().semesters.first().label)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun academicTermsFallBackToVpnSession() {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/cas/login"))
        vpnServer.enqueue(MockResponse().setBody("""
            <select id="xnm"><option value="2025">2025-2026</option></select>
            <select id="xqm"><option value="12">2</option></select>
        """.trimIndent()))
        cookies.put(
            vpnServer.url("/").host,
            "/jwglxt",
            "JSESSIONID",
            "vpn-session",
            accessMode = ScutAccessMode.WEB_VPN
        )
        cookies.configureSession(vpnServer.url("/").toString().toHttpUrl(), ScutAccessMode.WEB_VPN)

        val years = client().fetchAcademicTerms()

        assertEquals(2025, years.single().xnm)
        assertEquals(1, server.requestCount)
        assertEquals(1, vpnServer.requestCount)
    }

    private fun client(): ScutJwClient =
        ScutJwClient(cookies, baseUrl = server.url("/").toString().toHttpUrl())

    private fun assertKind(expected: ScutHttpException.Kind, action: () -> Unit) {
        try {
            action()
            fail("expected ${expected.name}")
        } catch (error: ScutHttpException) {
            assertEquals(expected, error.kind)
        }
    }
}
