package com.example.awake.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScutAuthRepositoryTest {
    @Test
    fun authenticationDependsOnJwSessionCookie() {
        val store = SessionCookieStore()
        val auth = ScutAuthRepository(store)
        assertFalse(auth.isAuthenticated())
        store.put(CasWebViewCoordinator.JW_HOST, "/jwglxt", "JSESSIONID", "session")
        assertTrue(auth.isAuthenticated())
        store.clearMemory()
        assertFalse(auth.isAuthenticated())
    }
}
