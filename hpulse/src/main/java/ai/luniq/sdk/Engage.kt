@file:Suppress("unused")

package ai.luniq.sdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * In-app engagement runtime — fetches active banners, guides, and surveys
 * from the dashboard, evaluates per-screen audience + trigger conditions,
 * and renders them inside the host app's foreground Activity.
 *
 *   GET /v1/sdk/banners  → Banner[]
 *   GET /v1/sdk/guides   → Guide[]
 *   GET /v1/sdk/surveys  → Survey[]
 *
 * This mirrors the iOS GuideEngine / SurveyEngine and the web SDK's
 * EngageRuntime so the cross-port contract stays identical.  Targeting is
 * deliberately minimal v1: page-path glob matching plus a couple of
 * trigger types.  More sophisticated audience rules can be added later
 * because the audience + trigger fields are JSON blobs the dashboard
 * owns end-to-end.
 *
 *   Tracked events:
 *     $banner_shown / $banner_click / $banner_dismissed
 *     $guide_started / $guide_step_shown / $guide_step_advanced /
 *       $guide_completed / $guide_dismissed
 *     $survey_shown / $survey_completed / $survey_dismissed
 */
internal object Engage {

    private const val DISMISS_PREFS = "luniq_engage_dismissed"
    private const val DISMISS_KEY = "ids"
    private const val FIRST_FETCH_DELAY_MS = 4_000L
    private const val REFRESH_MS = 5L * 60L * 1_000L

    private val ioExec = Executors.newSingleThreadScheduledExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var app: Application
    private lateinit var prefs: SharedPreferences
    private var endpoint: String = ""
    private var apiKey: String = ""
    private var environment: String = "PRD"
    private var track: (String, Map<String, Any>) -> Unit = { _, _ -> }
    @Volatile private var configured = false

    @Volatile private var banners: List<Banner> = emptyList()
    @Volatile private var guides:  List<Guide>  = emptyList()
    @Volatile private var surveys: List<Survey> = emptyList()

    private val dismissed = mutableSetOf<String>()

    private var currentActivity: WeakReference<Activity>? = null
    private var currentScreen: String = ""

    /** Currently visible items — only one of each kind at a time. */
    private var liveBannerView: WeakReference<View>? = null
    private var liveGuideTag:   String? = null
    private var liveSurveyTag:  String? = null

    /** Pending after-seconds timeouts so they can be cancelled on screen change. */
    private val pendingTimers  = mutableSetOf<Runnable>()
    /** Active on-click trigger arms that need to be torn down on screen change. */
    private val pendingArms    = mutableSetOf<ArmedClickTrigger>()

    fun configure(
        app: Application,
        endpoint: String,
        apiKey: String,
        environment: String,
        track: (String, Map<String, Any>) -> Unit,
    ) {
        if (configured) return
        configured = true
        this.app = app
        this.endpoint = endpoint.trimEnd('/')
        this.apiKey = apiKey
        this.environment = environment
        this.track = track
        this.prefs = app.getSharedPreferences(DISMISS_PREFS, Context.MODE_PRIVATE)
        prefs.getStringSet(DISMISS_KEY, null)?.let { dismissed.addAll(it) }

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {
                currentActivity = WeakReference(a)
                currentScreen = a::class.java.simpleName
                // Tear down any arms / timers from the previous screen so a
                // /pricing on-click doesn't fire while we're on /platform.
                cancelPendingArms()
                cancelPendingTimers()
                mainHandler.post { evaluateAll() }
            }
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })

        // First fetch ~4s after start, then every 5 min.
        ioExec.schedule({ fetchAll() }, FIRST_FETCH_DELAY_MS, TimeUnit.MILLISECONDS)
        ioExec.scheduleWithFixedDelay({ fetchAll() }, REFRESH_MS, REFRESH_MS, TimeUnit.MILLISECONDS)
    }

    // ── Public manual triggers ───────────────────────────────────────────

    fun showBanner(id: String) = mainHandler.post {
        banners.firstOrNull { it.id == id }?.let { renderBanner(it) }
    }
    fun showGuide(id: String) = mainHandler.post {
        guides.firstOrNull { it.id == id }?.let { renderGuide(it) }
    }
    fun showSurvey(id: String) = mainHandler.post {
        surveys.firstOrNull { it.id == id }?.let { renderSurvey(it) }
    }

    // ── Fetch ────────────────────────────────────────────────────────────

    private fun fetchAll() {
        if (!configured) return
        banners = parseBanners(httpGet("/v1/sdk/banners"))
            .sortedByDescending { it.priority }
        guides  = parseGuides(httpGet("/v1/sdk/guides"))
        surveys = parseSurveys(httpGet("/v1/sdk/surveys"))
        mainHandler.post { evaluateAll() }
    }

    private fun httpGet(path: String): String? {
        return try {
            val url = URL(endpoint + path)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("X-Luniq-Key", apiKey)
            conn.setRequestProperty("X-Luniq-Env", environment)
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 15_000; conn.readTimeout = 30_000
            val ok = conn.responseCode in 200..299
            val body = if (ok) conn.inputStream.bufferedReader().use { it.readText() } else null
            conn.disconnect()
            body
        } catch (_: Exception) { null }
    }

    // ── Audience + trigger evaluation ────────────────────────────────────

    private fun evaluateAll() {
        val act = currentActivity?.get() ?: return
        if (act.isFinishing) return

        if (liveBannerView?.get() == null) {
            banners.firstOrNull { eligible(it.id, it.audience) }
                ?.let { armOrRender(it.id, it.trigger) { renderBanner(it) } }
        }
        if (liveGuideTag == null) {
            guides.firstOrNull { eligible(it.id, it.audience) }
                ?.let { armOrRender(it.id, it.trigger) { renderGuide(it) } }
        }
        if (liveSurveyTag == null) {
            surveys.firstOrNull { eligible(it.id, it.audience) }
                ?.let { armOrRender(it.id, it.trigger) { renderSurvey(it) } }
        }
    }

    private fun eligible(id: String, audience: Audience?): Boolean {
        if (id in dismissed) return false
        return audienceMatch(audience)
    }

    private fun audienceMatch(a: Audience?): Boolean {
        if (a == null) return true
        val path = currentScreen
        if (a.pages.isNotEmpty() && a.pages.none { matchPath(path, it) }) return false
        if (a.excludePages.any { matchPath(path, it) }) return false
        return true
    }

    private fun armOrRender(id: String, trigger: Trigger?, render: () -> Unit) {
        val t = trigger
        if (t == null || t.type.isEmpty() || t.type == "page-load") { render(); return }
        when (t.type) {
            "after-seconds" -> {
                val ms = (t.delaySeconds.coerceAtLeast(1)) * 1_000L
                val r = Runnable {
                    if (id !in dismissed) render()
                }
                pendingTimers.add(r)
                mainHandler.postDelayed({
                    pendingTimers.remove(r); r.run()
                }, ms)
            }
            "on-click" -> {
                val sel = t.selector ?: return
                val act = currentActivity?.get() ?: return
                val arm = ArmedClickTrigger(act, sel) {
                    if (id !in dismissed) render()
                }
                if (arm.attach()) pendingArms.add(arm)
            }
            // exit-intent is N/A on Android — the OS handles back/home.
            else -> { /* unknown */ }
        }
    }

    private fun cancelPendingTimers() {
        pendingTimers.forEach { mainHandler.removeCallbacks(it) }
        pendingTimers.clear()
    }
    private fun cancelPendingArms() {
        pendingArms.forEach { it.detach() }
        pendingArms.clear()
    }

    // ── Renders ──────────────────────────────────────────────────────────

    private fun renderBanner(b: Banner) {
        val act = currentActivity?.get() ?: return
        val view = BannerRenderer.render(
            activity = act,
            banner   = b,
            onClick  = {
                track("\$banner_click", mapOf("banner_id" to b.id, "banner_name" to b.name))
            },
            onDismiss = {
                liveBannerView = null
                markDismissed(b.id)
                track("\$banner_dismissed", mapOf("banner_id" to b.id, "banner_name" to b.name))
            },
        ) ?: return
        liveBannerView = WeakReference(view)
        track("\$banner_shown", mapOf(
            "banner_id" to b.id,
            "banner_name" to b.name,
            "placement" to (b.placement.ifEmpty { "top" }),
        ))
    }

    private fun renderGuide(g: Guide) {
        val act = currentActivity?.get() ?: return
        if (g.steps.isEmpty()) return
        liveGuideTag = g.id
        track("\$guide_started", mapOf(
            "guide_id" to g.id, "guide_name" to g.name, "total_steps" to g.steps.size,
        ))
        GuideRenderer.render(
            activity = act,
            guide    = g,
            onStepShown    = { idx -> track("\$guide_step_shown",    mapOf("guide_id" to g.id, "guide_name" to g.name, "step" to idx)) },
            onStepAdvanced = { idx -> track("\$guide_step_advanced", mapOf("guide_id" to g.id, "guide_name" to g.name, "step" to idx)) },
            onCompleted    = {
                liveGuideTag = null
                markDismissed(g.id)
                track("\$guide_completed", mapOf("guide_id" to g.id, "guide_name" to g.name))
            },
            onDismissed    = { step ->
                liveGuideTag = null
                markDismissed(g.id)
                track("\$guide_dismissed", mapOf("guide_id" to g.id, "guide_name" to g.name, "step" to step))
            },
        )
    }

    private fun renderSurvey(s: Survey) {
        val act = currentActivity?.get() ?: return
        if (s.questions.isEmpty()) return
        liveSurveyTag = s.id
        track("\$survey_shown", mapOf("survey_id" to s.id, "survey_name" to s.name))
        SurveyRenderer.render(
            activity = act,
            survey   = s,
            onCompleted = { answers ->
                liveSurveyTag = null
                markDismissed(s.id)
                submitSurvey(s.id, answers)
                track("\$survey_completed", mapOf(
                    "survey_id" to s.id, "survey_name" to s.name,
                    "answers" to JSONObject(answers).toString(),
                ))
            },
            onDismissed = {
                liveSurveyTag = null
                markDismissed(s.id)
                track("\$survey_dismissed", mapOf("survey_id" to s.id, "survey_name" to s.name))
            },
        )
    }

    private fun submitSurvey(surveyId: String, answers: Map<String, Any?>) {
        ioExec.execute {
            try {
                val url = URL(endpoint + "/v1/surveys/" + surveyId + "/responses")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("X-Luniq-Key", apiKey)
                conn.setRequestProperty("X-Luniq-Env", environment)
                conn.connectTimeout = 15_000; conn.readTimeout = 30_000; conn.doOutput = true
                val payload = JSONObject().put("answers", JSONObject(answers))
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                conn.responseCode  // drain
                conn.disconnect()
            } catch (_: Exception) { /* swallow — best effort */ }
        }
    }

    // ── Persistence ──────────────────────────────────────────────────────

    private fun markDismissed(id: String) {
        dismissed.add(id)
        prefs.edit().putStringSet(DISMISS_KEY, dismissed.toSet()).apply()
    }

    // ── JSON parsing ─────────────────────────────────────────────────────

    private fun parseBanners(raw: String?): List<Banner> {
        val arr = parseArray(raw) ?: return emptyList()
        val out = ArrayList<Banner>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Banner(
                id        = o.optString("id"),
                name      = o.optString("name"),
                imageUrl  = o.optString("imageUrl",  ""),
                title     = o.optString("title",     ""),
                body      = o.optString("body",      ""),
                ctaLabel  = o.optString("ctaLabel",  ""),
                linkUrl   = o.optString("linkUrl",   ""),
                placement = o.optString("placement", "top"),
                priority  = o.optInt("priority", 0),
                trigger   = parseTrigger(o.optJSONObject("trigger")),
                audience  = parseAudience(o.optJSONObject("audience")),
            ))
        }
        return out
    }

    private fun parseGuides(raw: String?): List<Guide> {
        val arr = parseArray(raw) ?: return emptyList()
        val out = ArrayList<Guide>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val stepsJson = o.optJSONArray("steps")
            val steps = ArrayList<GuideStep>()
            if (stepsJson != null) {
                for (j in 0 until stepsJson.length()) {
                    val s = stepsJson.optJSONObject(j) ?: continue
                    steps.add(GuideStep(
                        selector = s.optString("selector", ""),
                        title    = s.optString("title",    ""),
                        body     = s.optString("body",     ""),
                        ctaLabel = s.optString("ctaLabel", ""),
                    ))
                }
            }
            out.add(Guide(
                id       = o.optString("id"),
                name     = o.optString("name"),
                kind     = o.optString("kind", "modal"),
                trigger  = parseTrigger(o.optJSONObject("trigger")),
                audience = parseAudience(o.optJSONObject("audience")),
                steps    = steps,
            ))
        }
        return out
    }

    private fun parseSurveys(raw: String?): List<Survey> {
        val arr = parseArray(raw) ?: return emptyList()
        val out = ArrayList<Survey>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val qsJson = o.optJSONArray("questions")
            val qs = ArrayList<SurveyQuestion>()
            if (qsJson != null) {
                for (j in 0 until qsJson.length()) {
                    val q = qsJson.optJSONObject(j) ?: continue
                    val choicesJson = q.optJSONArray("choices")
                    val choices = ArrayList<String>()
                    if (choicesJson != null) {
                        for (k in 0 until choicesJson.length()) choices.add(choicesJson.optString(k))
                    }
                    qs.add(SurveyQuestion(
                        id      = q.optString("id", ""),
                        type    = q.optString("type", "text"),
                        prompt  = q.optString("prompt", ""),
                        choices = choices,
                        scale   = q.optInt("scale", 0),
                    ))
                }
            }
            out.add(Survey(
                id        = o.optString("id"),
                name      = o.optString("name"),
                kind      = o.optString("kind", "custom"),
                trigger   = parseTrigger(o.optJSONObject("trigger")),
                audience  = parseAudience(o.optJSONObject("audience")),
                questions = qs,
            ))
        }
        return out
    }

    private fun parseArray(raw: String?): JSONArray? {
        if (raw.isNullOrBlank()) return null
        return try {
            // Server may return either a bare array or { items: [...] }.
            val trimmed = raw.trimStart()
            if (trimmed.startsWith("[")) JSONArray(raw)
            else JSONObject(raw).optJSONArray("items")
        } catch (_: Exception) { null }
    }

    private fun parseTrigger(o: JSONObject?): Trigger? {
        if (o == null) return null
        return Trigger(
            type         = o.optString("type", "page-load"),
            delaySeconds = o.optInt("delaySeconds", 0),
            selector     = if (o.has("selector")) o.optString("selector") else null,
        )
    }

    private fun parseAudience(o: JSONObject?): Audience? {
        if (o == null) return null
        val pages = o.optJSONArray("pages")?.let { jsonStrings(it) } ?: emptyList()
        val excl  = o.optJSONArray("excludePages")?.let { jsonStrings(it) } ?: emptyList()
        return Audience(pages = pages, excludePages = excl)
    }

    private fun jsonStrings(arr: JSONArray): List<String> {
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) out.add(arr.optString(i))
        return out
    }

    /**
     * Match an Activity / screen name against a configured glob.  Mirrors
     * the iOS + web behavior where pattern can be exact, "*" wildcard, or
     * "Prefix*" suffix wildcard.  We deliberately keep this small — the
     * dashboard surfaces a path picker so most rules are exact strings.
     */
    private fun matchPath(path: String, pattern: String): Boolean {
        if (pattern.isBlank()) return true
        if (pattern == "*" || pattern == "/*") return true
        if (pattern.endsWith("/*")) return path.startsWith(pattern.dropLast(2))
        if (pattern.endsWith("*"))  return path.startsWith(pattern.dropLast(1))
        return path == pattern
    }
}

// ── Public data models ──────────────────────────────────────────────────

data class Trigger(
    val type: String = "page-load",
    val delaySeconds: Int = 0,
    val selector: String? = null,
)
data class Audience(
    val pages: List<String> = emptyList(),
    val excludePages: List<String> = emptyList(),
)
data class Banner(
    val id: String,
    val name: String,
    val imageUrl: String = "",
    val title: String = "",
    val body: String = "",
    val ctaLabel: String = "",
    val linkUrl: String = "",
    val placement: String = "top",
    val priority: Int = 0,
    val trigger: Trigger? = null,
    val audience: Audience? = null,
)
data class GuideStep(
    val selector: String = "",
    val title: String = "",
    val body: String = "",
    val ctaLabel: String = "",
)
data class Guide(
    val id: String,
    val name: String,
    val kind: String = "modal",
    val trigger: Trigger? = null,
    val audience: Audience? = null,
    val steps: List<GuideStep> = emptyList(),
)
data class SurveyQuestion(
    val id: String = "",
    val type: String = "text",      // rating | single | multi | text
    val prompt: String = "",
    val choices: List<String> = emptyList(),
    val scale: Int = 0,
)
data class Survey(
    val id: String,
    val name: String,
    val kind: String = "custom",
    val trigger: Trigger? = null,
    val audience: Audience? = null,
    val questions: List<SurveyQuestion> = emptyList(),
)

// ── On-click trigger helper ─────────────────────────────────────────────

/**
 * Watches an Activity's view tree and fires once when the user clicks a
 * view whose tag or contentDescription matches the configured selector.
 * Walks the decor view tree on attach and instruments any matching view's
 * existing OnClickListener (wraps, doesn't replace).
 */
internal class ArmedClickTrigger(
    activity: Activity,
    private val selector: String,
    private val onFire: () -> Unit,
) {
    private val activityRef = WeakReference(activity)
    private val instrumented = ArrayList<Pair<View, View.OnClickListener?>>()
    private var fired = false

    fun attach(): Boolean {
        val act = activityRef.get() ?: return false
        val root = act.window?.decorView ?: return false
        root.post { walk(root) }
        return true
    }

    fun detach() {
        instrumented.forEach { (v, orig) -> v.setOnClickListener(orig) }
        instrumented.clear()
    }

    private fun walk(v: View) {
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        if (matches(v)) instrument(v)
    }

    private fun matches(v: View): Boolean {
        val tag = v.tag as? String
        if (tag != null && tag == selector) return true
        val cd  = v.contentDescription?.toString()
        if (cd != null && cd == selector) return true
        // Allow #resource-id style selector as a convenience.
        if (selector.startsWith("#")) {
            try {
                val name = v.resources.getResourceEntryName(v.id)
                if (name == selector.substring(1)) return true
            } catch (_: Exception) { /* unnamed view */ }
        }
        return false
    }

    private fun instrument(v: View) {
        val orig = ReflectListeners.getOnClickListener(v)
        instrumented.add(v to orig)
        v.setOnClickListener {
            orig?.onClick(it)
            if (!fired) { fired = true; onFire() }
        }
    }
}
