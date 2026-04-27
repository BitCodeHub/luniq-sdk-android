package ai.luniq.sdk

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Detects frustration signals from the tap stream:
 *  - $rage_click: 3+ taps on same control within 2 seconds
 *  - $dead_click: tap followed by no screen change within 1.5 seconds
 */
internal class FrustrationDetector(
    private val emit: (String, Map<String, Any>) -> Unit,
) {
    private data class Tap(val control: String, val ts: Long, val screen: String)

    private val exec = Executors.newSingleThreadScheduledExecutor()
    private var recent = mutableListOf<Tap>()
    private var lastScreenChangeAt = 0L
    private val rageEmitted = mutableSetOf<String>()

    private val rageWindowMs = 2000L
    private val rageThreshold = 3
    private val deadResponseWindowMs = 1500L

    fun recordTap(controlId: String, screen: String) {
        exec.execute {
            val now = System.currentTimeMillis()
            recent.add(Tap(controlId, now, screen))
            recent = recent.filter { now - it.ts < rageWindowMs }.toMutableList()

            val key = "$screen|$controlId"
            val same = recent.filter { it.control == controlId && it.screen == screen }
            if (same.size >= rageThreshold && key !in rageEmitted) {
                rageEmitted.add(key)
                emit("\$rage_click", mapOf(
                    "control" to controlId, "screen_name" to screen,
                    "count" to same.size, "duration_ms" to (now - same.first().ts).toInt(),
                ))
                exec.schedule({ rageEmitted.remove(key) }, rageWindowMs + 500, TimeUnit.MILLISECONDS)
            }

            val snapshot = lastScreenChangeAt
            exec.schedule({
                if (lastScreenChangeAt <= snapshot && key !in rageEmitted) {
                    emit("\$dead_click", mapOf("control" to controlId, "screen_name" to screen))
                }
            }, deadResponseWindowMs, TimeUnit.MILLISECONDS)
        }
    }

    fun recordScreenChange() {
        exec.execute { lastScreenChangeAt = System.currentTimeMillis() }
    }
}
