@file:Suppress("unused")

package ai.luniq.sdk

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Pulse SDK for Android. Singleton entrypoint.
 *
 *     Luniq.start(context, "lq_live_xxxx", "https://your-luniq-host.com")
 *     Luniq.identify(visitorId = "abc", accountId = "user_42")
 *     Luniq.track("checkout_started", mapOf("cart_size" to 3))
 */
object Luniq {
    private const val TAG = "Luniq"

    private lateinit var config: LuniqConfig
    private lateinit var app: Application
    private val queue = ConcurrentLinkedQueue<JSONObject>()
    private val ioExec = Executors.newSingleThreadScheduledExecutor()
    private var visitorId: String = UUID.randomUUID().toString()
    private var accountId: String? = null
    private val traits = mutableMapOf<String, Any>()
    private val sessionId: String = UUID.randomUUID().toString()
    @Volatile private var started = false

    private val frustration  = FrustrationDetector { name, props -> track(name, props) }
    private val intelligence = IntelligenceEngine  { name, props -> track(name, props) }

    /** Initialize the SDK. Call once from [Application.onCreate]. */
    @JvmStatic
    fun start(app: Application, apiKey: String, endpoint: String, environment: String = "PRD", autoCapture: Boolean = true) {
        if (started) return
        started = true
        config = LuniqConfig(apiKey, endpoint.trimEnd('/'), environment, autoCapture)
        this.app = app
        DesignMode.configure(config.endpoint, config.apiKey, app)

        // Restore visitorId from prefs
        val prefs = app.getSharedPreferences("luniq_prefs", android.content.Context.MODE_PRIVATE)
        prefs.getString("visitor_id", null)?.let { visitorId = it }
            ?: prefs.edit().putString("visitor_id", visitorId).apply()

        if (autoCapture) {
            app.registerActivityLifecycleCallbacks(LifecycleHook(::track, ::screenChanged))
            ErrorCapture.install { name, props -> track(name, props) }
            NetworkCapture.install(config.endpoint) { name, props -> track(name, props) }
        }

        // In-app engagement runtime — fetches banners/guides/surveys and
        // renders them inside the active Activity.  Registers its own
        // lifecycle callback so it works regardless of [autoCapture].
        Engage.configure(app, config.endpoint, config.apiKey, config.environment) { name, props ->
            track(name, props)
        }

        // Flush every 30s
        ioExec.scheduleWithFixedDelay({ flushNow() }, 30, 30, TimeUnit.SECONDS)
        track("app_open", emptyMap())
    }

    /** Manually surface a fetched banner by id (mirrors web/iOS). */
    @JvmStatic fun showBanner(id: String) = Engage.showBanner(id)

    /** Manually surface a fetched guide by id. */
    @JvmStatic fun showGuide(id: String) = Engage.showGuide(id)

    /** Manually surface a fetched survey by id. */
    @JvmStatic fun showSurvey(id: String) = Engage.showSurvey(id)

    @JvmStatic
    fun identify(visitorId: String? = null, accountId: String? = null, traits: Map<String, Any>? = null) {
        visitorId?.let { this.visitorId = it }
        accountId?.let { this.accountId = it } ?: run { /* keep */ }
        traits?.let { this.traits.putAll(it) }
    }

    @JvmStatic
    fun track(name: String, properties: Map<String, Any> = emptyMap()) {
        if (!::config.isInitialized) return
        var enriched = intelligence.enrich(name, properties).toMutableMap()
        // Auto-attach journey + persona to error events
        if (name == "\$error") {
            enriched.putIfAbsent("journey", intelligence.journeySummary())
            enriched.putIfAbsent("persona", intelligence.persona())
            enriched.putIfAbsent("frustration_at_error", intelligence.profile().frustrationLevel)
        }
        intelligence.observe(name, enriched)
        intelligence.pushBreadcrumb(name, enriched)
        val props = JSONObject(enriched as Map<String, Any>)
        props.put("os_type", "ANDROID")
        props.put("env", config.environment)
        props.put("device_model", android.os.Build.MODEL)
        props.put("device_os", "Android " + android.os.Build.VERSION.RELEASE)
        traits.forEach { (k, v) -> if (!props.has(k)) props.put(k, v) }

        val ev = JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("name", name)
            .put("properties", props)
            .put("timestamp", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US).format(java.util.Date()))
            .put("sessionId", sessionId)
            .put("visitorId", visitorId)
            .put("accountId", accountId ?: JSONObject.NULL)

        queue.add(ev)
        // In-memory frustration evaluation
        if (name == "\$tap") {
            val controlId = (properties["id"] as? String) ?: (properties["text"] as? String) ?: "unknown"
            val screen = properties["screen_name"] as? String ?: ""
            frustration.recordTap(controlId, screen)
        } else if (name == "\$screen") {
            frustration.recordScreenChange()
        }
    }

    @JvmStatic
    fun screen(name: String, properties: Map<String, Any> = emptyMap()) {
        track("\$screen", properties + ("screen_name" to name))
    }

    @JvmStatic
    fun reportError(error: Throwable, context: Map<String, Any> = emptyMap()) {
        track("\$error", context + mapOf(
            "kind" to "exception",
            "name" to (error::class.java.simpleName),
            "message" to (error.message ?: ""),
            "stack" to error.stackTraceToString().take(4000),
            "fatal" to false,
        ))
    }

    @JvmStatic
    fun optOut(out: Boolean) { config = config.copy(enabled = !out) }

    /** Enter Design Mode — present a code-entry dialog so the dashboard can pair. */
    @JvmStatic fun enableDesignMode() = DesignMode.startPairing()

    /** Pair immediately with a known code (e.g. from a deep link). */
    @JvmStatic fun enableDesignMode(code: String) = DesignMode.pair(code)

    @JvmStatic
    fun flush() = ioExec.execute { flushNow() }

    /** Live on-device user intelligence. Safe to call any time. */
    @JvmStatic
    fun profile(): PulseProfile = intelligence.profile()

    /** Current predicted churn risk 0-100 (heuristic + pattern detection). */
    @JvmStatic
    fun predictChurn(): Int = intelligence.predictedChurn()

    /** Session worth score 0-100 — used to prioritize server-side AI analysis. */
    @JvmStatic
    fun sessionScore(): Int = intelligence.sessionWorthScore()

    /** Subscribe to adaptive nudge decisions (help_offer / save_offer / reengagement). */
    @JvmStatic
    fun onNudge(fn: (PulseNudge) -> Unit) = intelligence.addListener(fn)

    /** Real-time persona: power_user / explorer / struggler / first_time / loyalist / churner / browser. */
    @JvmStatic fun persona(): String = intelligence.persona()

    /** AI-flavored summary of the user's recent journey. */
    @JvmStatic fun journeySummary(): String = intelligence.journeySummary()

    /** Heuristic 0-100 probability the user completes their current goal. */
    @JvmStatic fun conversionProbability(): Int = intelligence.conversionProbability()

    private fun screenChanged() { /* used by hook */ }

    private fun flushNow() {
        if (!::config.isInitialized || !config.enabled) return
        val batch = mutableListOf<JSONObject>()
        repeat(50) {
            queue.poll()?.let { batch.add(it) } ?: return@repeat
        }
        if (batch.isEmpty()) return

        val body = JSONObject().put("events", JSONArray(batch))
        try {
            val url = URL(config.endpoint + "/v1/events")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Luniq-Key", config.apiKey)
            conn.connectTimeout = 15000; conn.readTimeout = 30000; conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            if (!ok) batch.forEach { queue.offer(it) }
        } catch (e: Exception) {
            batch.forEach { queue.offer(it) }
        }
    }
}

data class LuniqConfig(
    val apiKey: String,
    val endpoint: String,
    val environment: String,
    val autoCapture: Boolean,
    val enabled: Boolean = true,
)

/** ActivityLifecycleCallbacks → emits $screen and wraps view click listeners. */
internal class LifecycleHook(
    private val track: (String, Map<String, Any>) -> Unit,
    private val onScreen: () -> Unit,
) : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(a: Activity, b: Bundle?) {}
    override fun onActivityStarted(a: Activity) {}
    override fun onActivityResumed(a: Activity) {
        track("\$screen", mapOf(
            "screen_name" to a::class.java.simpleName,
            "source" to "auto",
        ))
        onScreen()
        // After the layout passes, install click listeners on all clickable views
        a.window.decorView.post { instrumentClicks(a.window.decorView, track) }
    }
    override fun onActivityPaused(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}
}

private fun instrumentClicks(root: View, track: (String, Map<String, Any>) -> Unit) {
    if (root is ViewGroup) {
        for (i in 0 until root.childCount) instrumentClicks(root.getChildAt(i), track)
    }
    if (!root.hasOnClickListeners()) return
    val tag = root.getTag(R.id.luniq_wrapped)
    if (tag == true) return
    root.setTag(R.id.luniq_wrapped, true)
    val orig = ReflectListeners.getOnClickListener(root)
    if (orig != null) {
        root.setOnClickListener { v ->
            val coords = IntArray(2); v.getLocationOnScreen(coords)
            track("\$tap", mapOf(
                "control" to v::class.java.simpleName,
                "id" to (try { v.resources.getResourceEntryName(v.id) } catch (_: Exception) { "" }),
                "text" to ((v as? android.widget.TextView)?.text?.toString() ?: ""),
                "tap_x" to coords[0], "tap_y" to coords[1],
                "screen_w" to v.context.resources.displayMetrics.widthPixels,
                "screen_h" to v.context.resources.displayMetrics.heightPixels,
                "source" to "auto",
            ))
            orig.onClick(v)
        }
    }
}
