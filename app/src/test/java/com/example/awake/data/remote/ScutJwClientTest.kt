package com.example.awake.data.remote

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ScutJwClientTest {
    private lateinit var server: MockWebServer
    private lateinit var cookies: SessionCookieStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        cookies = SessionCookieStore()
        cookies.put(server.url("/").host, "/jwglxt", "JSESSIONID", "test-session")
    }

    @After
    fun tearDown() {
        server.shutdown()
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
