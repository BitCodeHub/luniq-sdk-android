package ai.luniq.sdk

/** On-device user profile — updated on every event, queryable via Luniq.profile(). */
data class PulseProfile(
    val engagementScore:  Int,
    val churnRisk:        Int,
    val frustrationLevel: Int,
    val sessionWorth:     Int,
    val featureMastery:   Map<String, Int>,
)

/** Adaptive nudge fired when the SDK detects a struggling user. */
data class PulseNudge(val kind: String, val reason: String, val screen: String)

/**
 * Native, on-device user intelligence engine. Runs every track() with zero
 * server roundtrip. Classifies events (intent / sentiment / complexity /
 * semantic_name), maintains live scores, emits $nudge_triggered and
 * $churn_predicted when the user is about to abandon.
 */
internal class IntelligenceEngine(
    private val emit: (String, Map<String, Any>) -> Unit,
) {
    private var engagement = 50.0
    private var churn      = 0.0
    private var frustration = 0.0
    private var worth      = 0.0
    private val mastery    = mutableMapOf<String, Int>()
    private val patterns   = mutableListOf<String>()
    private var screenCount = 0; private var tapCount = 0
    private var errorCount  = 0; private var rageCount = 0; private var deadCount = 0
    private var lastScreen  = ""
    private var lastTapAt   = 0L
    private var lastNudgeAt = 0L
    private var lastProfileEmitAt = 0L
    private var churnEmitted = false
    private val listeners   = mutableListOf<(PulseNudge) -> Unit>()
    private val lock = Any()

    fun addListener(fn: (PulseNudge) -> Unit) { synchronized(lock) { listeners.add(fn) } }

    fun profile(): PulseProfile = synchronized(lock) {
        PulseProfile(
            engagementScore  = engagement.toInt(),
            churnRisk        = churn.toInt(),
            frustrationLevel = frustration.toInt(),
            sessionWorth     = worth.toInt(),
            featureMastery   = mastery.toMap(),
        )
    }
    fun predictedChurn(): Int = synchronized(lock) { churn.toInt() }
    fun sessionWorthScore(): Int = synchronized(lock) { worth.toInt() }

    /** Real-time persona classification. */
    fun persona(): String = synchronized(lock) {
        when {
            rageCount >= 2 || errorCount >= 3 || frustration >= 60 -> "struggler"
            engagement >= 80 && screenCount >= 8 -> "power_user"
            screenCount <= 2 && tapCount <= 5 -> "first_time"
            churn >= 60 -> "churner"
            engagement >= 60 && mastery.size >= 4 -> "loyalist"
            tapCount > screenCount * 3 -> "explorer"
            else -> "browser"
        }
    }

    /** Heuristic P(completes current goal). */
    fun conversionProbability(): Int = synchronized(lock) {
        var base = 50.0
        base += minOf(engagement - 50.0, 30.0)
        base -= minOf(frustration * 0.6, 30.0)
        base -= minOf(churn * 0.4, 30.0)
        base += minOf(screenCount * 1.5, 20.0)
        if (errorCount > 0) base -= 15
        if (rageCount > 0) base -= 20
        maxOf(0, minOf(100, base.toInt()))
    }

    // Breadcrumbs
    private data class Crumb(val t: Long, val name: String, val screen: String, val semantic: String)
    private val breadcrumbs = mutableListOf<Crumb>()

    fun pushBreadcrumb(name: String, props: Map<String, Any>) = synchronized(lock) {
        val screen = (props["screen_name"] as? String) ?: ""
        val sem = (props["semantic_name"] as? String) ?: ""
        breadcrumbs.add(Crumb(System.currentTimeMillis(), name, screen, sem))
        if (breadcrumbs.size > 20) breadcrumbs.removeAt(0)
    }
    fun journeySummary(): String = synchronized(lock) {
        val first = breadcrumbs.firstOrNull() ?: return ""
        breadcrumbs.joinToString(" → ") { c ->
            val sec = ((c.t - first.t) / 1000).toInt()
            val label = c.semantic.ifEmpty { c.name.removePrefix("\$") }
            "$label${if (c.screen.isEmpty()) "" else " on ${c.screen}"} (${sec}s)"
        }.take(800)
    }

    /** Call on every event. Updates scores + fires side-effect events. */
    fun observe(name: String, props: Map<String, Any>) = synchronized(lock) {
        val now = System.currentTimeMillis()

        when (name) {
            "\$screen" -> {
                screenCount++
                val sn = (props["screen_name"] as? String) ?: ""
                lastScreen = sn
                mastery[sn] = (mastery[sn] ?: 0) + 1
                engagement = clamp(engagement + 1, 0.0, 100.0)
            }
            "\$tap" -> { tapCount++; lastTapAt = now; engagement = clamp(engagement + 0.3, 0.0, 100.0) }
            "\$rage_click" -> {
                rageCount++
                frustration = clamp(frustration + 25, 0.0, 100.0)
                churn = clamp(churn + 12, 0.0, 100.0)
                engagement = clamp(engagement - 5, 0.0, 100.0)
            }
            "\$dead_click" -> {
                deadCount++
                frustration = clamp(frustration + 10, 0.0, 100.0)
                churn = clamp(churn + 4, 0.0, 100.0)
            }
            "\$error" -> {
                errorCount++
                frustration = clamp(frustration + 15, 0.0, 100.0)
                churn = clamp(churn + 8, 0.0, 100.0)
            }
            "\$guide_completed", "\$survey_completed" -> {
                engagement = clamp(engagement + 8, 0.0, 100.0)
                churn = clamp(churn - 5, 0.0, 100.0)
            }
        }

        // Natural decay
        val sinceTap = if (lastTapAt > 0) (now - lastTapAt) / 60000.0 else 0.0
        if (sinceTap > 1) frustration = clamp(frustration - 5 * sinceTap, 0.0, 100.0)

        patterns.add(name)
        if (patterns.size > 8) patterns.removeAt(0)

        val depth = screenCount + tapCount * 0.2
        worth = clamp(minOf(depth, 50.0) + errorCount * 10.0 + rageCount * 12.0 + frustration * 0.3, 0.0, 100.0)

        if (now - lastProfileEmitAt > 30_000) {
            lastProfileEmitAt = now
            emit("\$profile_snapshot", mapOf(
                "engagement_score"  to engagement.toInt(),
                "churn_risk"        to churn.toInt(),
                "frustration_level" to frustration.toInt(),
                "session_worth"     to worth.toInt(),
                "screens_visited"   to screenCount,
                "taps"              to tapCount,
                "errors"            to errorCount,
                "rage_clicks"       to rageCount,
            ))
        }

        if (churn >= 70 && !churnEmitted && isAbandonPattern()) {
            churnEmitted = true
            emit("\$churn_predicted", mapOf(
                "confidence" to churn.toInt(),
                "reason"     to abandonReason(),
                "screen_name" to lastScreen,
            ))
        }

        maybeFireNudge(now)
    }

    /** Add intent/sentiment/complexity/semantic_name to every event's props. */
    fun enrich(name: String, props: Map<String, Any>): Map<String, Any> {
        val out = props.toMutableMap()
        if (!out.containsKey("intent"))     out["intent"]     = classifyIntent(name, props)
        if (!out.containsKey("sentiment"))  out["sentiment"]  = classifySentiment(name, props)
        if (!out.containsKey("complexity")) out["complexity"] = classifyComplexity(name, props)
        inferSemanticName(name, props)?.let { if (!out.containsKey("semantic_name")) out["semantic_name"] = it }
        return out
    }

    // ----- classifiers -----

    private fun classifyIntent(name: String, props: Map<String, Any>): String {
        val text = text(props).lowercase()
        if (name == "\$error" || name == "\$rage_click" || name == "\$dead_click") return "troubleshoot"
        if (text.containsAny("buy", "cart", "checkout", "purchase", "pay")) return "purchase"
        if (text.containsAny("help", "support", "faq", "contact")) return "support"
        if (text.containsAny("settings", "profile", "account", "manage")) return "configure"
        if (text.containsAny("search", "find", "explore", "browse")) return "explore"
        return "browse"
    }
    private fun classifySentiment(name: String, props: Map<String, Any>): String {
        if (name == "\$rage_click" || name == "\$error") return "negative"
        if (name == "\$guide_completed" || name == "\$survey_completed") return "positive"
        val text = text(props).lowercase()
        if (text.containsAny("love", "great", "awesome", "thanks", "complete")) return "positive"
        if (text.containsAny("cancel", "skip", "close", "back", "no")) return "negative"
        return "neutral"
    }
    private fun classifyComplexity(name: String, props: Map<String, Any>): String {
        if (name == "\$error") return "complex"
        val text = text(props)
        return when {
            text.length > 40 -> "complex"
            text.length > 12 -> "medium"
            else             -> "simple"
        }
    }
    private fun inferSemanticName(name: String, props: Map<String, Any>): String? {
        if (name != "\$tap") return null
        val text = text(props).lowercase()
        return when {
            text.containsAny("submit", "confirm", "place order", "checkout", "pay") -> "form_submit"
            text.containsAny("buy", "purchase", "add to cart") -> "purchase_intent"
            text.containsAny("sign up", "create account", "register") -> "signup_intent"
            text.containsAny("log in", "sign in", "login") -> "login_intent"
            text.containsAny("delete", "remove", "cancel") -> "destructive_action"
            text.containsAny("help", "support", "faq") -> "help_seek"
            text.containsAny("search", "find") -> "search_intent"
            else -> null
        }
    }
    private fun text(props: Map<String, Any>): String =
        (props["text"] as? String) ?: (props["title"] as? String) ?: ""

    private fun isAbandonPattern(): Boolean {
        if (patterns.size < 3) return false
        val last3 = patterns.takeLast(3).joinToString(",")
        return last3.contains("rage") || last3.contains("error") || last3.contains("dead_click")
    }
    private fun abandonReason(): String = when {
        rageCount  >= 2      -> "repeated_rage_clicks"
        errorCount >= 2      -> "multiple_errors"
        deadCount  >= 3      -> "unresponsive_ui"
        frustration > 60     -> "high_frustration"
        else                 -> "abandon_pattern"
    }

    private fun maybeFireNudge(now: Long) {
        if (now - lastNudgeAt < 30_000) return
        val n = when {
            frustration >= 60                  -> PulseNudge("help_offer", "high_frustration", lastScreen)
            churn       >= 75                  -> PulseNudge("save_offer", "churn_risk", lastScreen)
            engagement  <  20 && screenCount > 3 -> PulseNudge("reengagement_prompt", "low_engagement", lastScreen)
            else -> null
        } ?: return
        lastNudgeAt = now
        listeners.forEach { it(n) }
        emit("\$nudge_triggered", mapOf(
            "nudge_kind" to n.kind, "reason" to n.reason, "screen_name" to n.screen,
        ))
    }

    private fun clamp(v: Double, lo: Double, hi: Double) = minOf(maxOf(v, lo), hi)
}

private fun String.containsAny(vararg needles: String): Boolean =
    needles.any { this.contains(it) }
