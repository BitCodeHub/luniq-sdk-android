package ai.luniq.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the [LuniqConfig] data class. */
class LuniqConfigTest {

    @Test fun `defaults to enabled when not specified`() {
        val cfg = LuniqConfig(
            apiKey      = "lq_live_x",
            endpoint    = "https://example.com",
            environment = "PRD",
            autoCapture = true,
        )
        assertTrue(cfg.enabled)
    }

    @Test fun `copy can flip enabled flag without mutating other fields`() {
        val cfg = LuniqConfig("k", "https://e", "DEV", autoCapture = false)
        val off = cfg.copy(enabled = false)
        assertFalse(off.enabled)
        assertEquals(cfg.apiKey,      off.apiKey)
        assertEquals(cfg.endpoint,    off.endpoint)
        assertEquals(cfg.environment, off.environment)
        assertEquals(cfg.autoCapture, off.autoCapture)
    }
}
