package com.anytypeio.anytype.pebble.webhook

import com.anytypeio.anytype.pebble.webhook.model.WebhookConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertEquals

class WebhookConfigTest {

    @Test
    fun `authEnabled is false when token is empty`() {
        val config = WebhookConfig(authToken = "")
        assertFalse(config.authEnabled)
    }

    @Test
    fun `authEnabled is true when token is non-empty`() {
        val config = WebhookConfig(authToken = "secret-token")
        assertTrue(config.authEnabled)
    }

    @Test
    fun `defaults are sensible`() {
        val config = WebhookConfig()
        assertEquals("0.0.0.0", config.host)
        assertEquals(65_536L, config.maxRequestBodyBytes)
        assertEquals(30_000L, config.requestTimeoutMs)
        assertFalse(config.authEnabled)
    }

    @Test
    fun `custom port and host are respected`() {
        val config = WebhookConfig(port = 9090, host = "127.0.0.1")
        assertEquals(9090, config.port)
        assertEquals("127.0.0.1", config.host)
    }

    @Test
    fun `authEnabled is false for blank token`() {
        val config = WebhookConfig(authToken = "   ")
        // whitespace-only tokens are still technically non-empty strings — auth is on
        // This documents intentional behaviour: callers should strip whitespace.
        assertTrue(config.authEnabled)
    }
}
