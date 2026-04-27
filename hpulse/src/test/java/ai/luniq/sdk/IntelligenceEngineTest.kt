package ai.luniq.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-only tests for [IntelligenceEngine]. Targets the pure classification +
 * scoring logic that doesn't touch Application/Context. The collected `emitted`
 * list captures any side-effect events the engine fires through its emit
 * callback so we can also assert behavior, not just return values.
 */
class IntelligenceEngineTest {

    private val emitted = mutableListOf<Pair<String, Map<String, Any>>>()
    private val engine  = IntelligenceEngine { name, props -> emitted.add(name to props) }

    // ───────── enrich() — classification ─────────

    @Test fun `enrich tags purchase intent on cart-related text`() {
        val out = engine.enrich("\$tap", mapOf("text" to "Add to cart"))
        assertEquals("purchase",        out["intent"])
        assertEquals("purchase_intent", out["semantic_name"])
    }

    @Test fun `enrich tags troubleshoot intent and negative sentiment for errors`() {
        val out = engine.enrich("\$error", mapOf("message" to "boom"))
        assertEquals("troubleshoot", out["intent"])
        assertEquals("negative",     out["sentiment"])
        assertEquals("complex",      out["complexity"])
    }

    @Test fun `enrich preserves caller-provided fields and never overwrites them`() {
        val out = engine.enrich(
            "\$tap",
            mapOf(
                "text"      to "checkout",
                "intent"    to "custom_intent",
                "sentiment" to "custom_sentiment",
            ),
        )
        // Caller wins on collisions.
        assertEquals("custom_intent",    out["intent"])
        assertEquals("custom_sentiment", out["sentiment"])
        // But complexity (not provided) is still inferred.
        assertNotNull(out["complexity"])
    }

    @Test fun `enrich does not add semantic_name for non-tap events`() {
        val out = engine.enrich("custom_event", mapOf("text" to "buy now"))
        assertNull(out["semantic_name"])
    }

    @Test fun `complexity scales with text length`() {
        assertEquals("simple",  engine.enrich("e", mapOf("text" to "ok"))["complexity"])
        assertEquals("medium",  engine.enrich("e", mapOf("text" to "click here please"))["complexity"])
        assertEquals(
            "complex",
            engine.enrich("e", mapOf("text" to "x".repeat(50)))["complexity"],
        )
    }

    // ───────── observe() + persona() ─────────

    @Test fun `persona is first_time for fresh engine with minimal activity`() {
        engine.observe("\$screen", mapOf("screen_name" to "Home"))
        engine.observe("\$tap",    mapOf("text" to "ok"))
        assertEquals("first_time", engine.persona())
    }

    @Test fun `persona becomes struggler after repeated rage clicks`() {
        repeat(3) { engine.observe("\$rage_click", mapOf("control" to "pay_btn")) }
        assertEquals("struggler", engine.persona())
        // And frustration should have climbed well above zero.
        assertTrue(engine.profile().frustrationLevel >= 60)
    }

    @Test fun `errors push churn risk upward`() {
        val before = engine.predictedChurn()
        repeat(4) { engine.observe("\$error", emptyMap()) }
        val after = engine.predictedChurn()
        assertTrue("expected churn to grow after errors (before=$before after=$after)", after > before)
    }

    @Test fun `conversionProbability stays clamped within 0 to 100`() {
        // Drive frustration and churn to ceiling.
        repeat(20) { engine.observe("\$rage_click", emptyMap()) }
        repeat(20) { engine.observe("\$error",      emptyMap()) }
        val p = engine.conversionProbability()
        assertTrue("probability=$p must be in [0,100]", p in 0..100)
    }

    // ───────── breadcrumbs / journeySummary ─────────

    @Test fun `journeySummary is empty before any breadcrumbs and populated after`() {
        assertEquals("", engine.journeySummary())

        engine.pushBreadcrumb("\$screen", mapOf("screen_name" to "Home"))
        engine.pushBreadcrumb("\$tap",    mapOf("semantic_name" to "purchase_intent"))

        val summary = engine.journeySummary()
        assertTrue("summary should mention Home: $summary",            summary.contains("Home"))
        assertTrue("summary should mention purchase_intent: $summary", summary.contains("purchase_intent"))
        assertTrue("summary should join with arrow: $summary",         summary.contains("→"))
    }

    @Test fun `breadcrumb buffer is bounded at 20 entries`() {
        repeat(30) { i ->
            engine.pushBreadcrumb("evt_$i", mapOf("screen_name" to "S$i"))
        }
        val summary = engine.journeySummary()
        // Earliest entries should have been dropped.
        assertTrue("oldest crumb should have rolled off: $summary", !summary.contains("evt_0 "))
        // Most recent should still be present.
        assertTrue("most recent crumb should remain: $summary", summary.contains("evt_29"))
    }
}
