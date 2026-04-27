@file:Suppress("unused")

package ai.luniq.sdk

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

/**
 * Design Mode — pairs a debug build to the Pulse dashboard via WebSocket so
 * a PM can preview unpublished guides/banners/surveys on the real device.
 *
 *     Luniq.enableDesignMode()                 // shows code entry dialog
 *     Luniq.enableDesignMode("abc123")         // pairs immediately
 */
object DesignMode {
    private var client: OkHttpClient? = null
    private var ws: WebSocket? = null
    private var endpoint: String = ""
    private var apiKey: String = ""
    private var connected = false
    private var screen: String = "unknown"
    private var captureHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentActivity: WeakReference<Activity>? = null
    private var overlay: View? = null
    private var lifecycle: Application.ActivityLifecycleCallbacks? = null

    internal fun configure(endpoint: String, apiKey: String, app: Application) {
        this.endpoint = endpoint
        this.apiKey = apiKey
        if (lifecycle == null) {
            val cb = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(a: Activity) {
                    currentActivity = WeakReference(a)
                    if (overlay == null && connected) installOverlay(a)
                }
                override fun onActivityPaused(a: Activity) {}
                override fun onActivityCreated(a: Activity, b: Bundle?) {}
                override fun onActivityStarted(a: Activity) {}
                override fun onActivityStopped(a: Activity) {}
                override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
                override fun onActivityDestroyed(a: Activity) {}
            }
            app.registerActivityLifecycleCallbacks(cb)
            lifecycle = cb
        }
    }

    fun startPairing() {
        val a = currentActivity?.get() ?: return
        mainHandler.post { presentCodePrompt(a) }
    }

    fun pair(code: String) {
        val pairCode = code.trim().lowercase()
        if (endpoint.isEmpty() || pairCode.isEmpty()) return
        val wsBase = endpoint
            .replace("https://", "wss://")
            .replace("http://",  "ws://")
        val req = Request.Builder()
            .url("$wsBase/v1/design/ws/$pairCode/sdk")
            .addHeader("X-Luniq-Key", apiKey)
            .build()
        val c = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
        client = c
        ws = c.newWebSocket(req, listener)
    }

    fun disconnect() {
        connected = false
        captureHandler?.removeCallbacksAndMessages(null)
        captureHandler?.looper?.quitSafely()
        captureHandler = null
        ws?.close(1000, "exit"); ws = null
        client = null
        mainHandler.post { overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }; overlay = null }
    }

    fun reportScreen(name: String) {
        screen = name
        send(jsonOf("type" to "screen", "name" to name))
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            connected = true
            send(jsonOf("type" to "hello", "platform" to "android", "device" to Build.MODEL, "os" to Build.VERSION.RELEASE))
            startCapture()
            mainHandler.post { currentActivity?.get()?.let { installOverlay(it); setOverlayStatus("paired") } }
        }
        override fun onMessage(webSocket: WebSocket, text: String) { handle(text) }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { handle(bytes.utf8()) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { connected = false; mainHandler.post { setOverlayStatus("error: ${t.message}") } }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { connected = false; mainHandler.post { setOverlayStatus("closed") } }
    }

    /** Listener registered by host app or guide engine to render preview commands. */
    var onPreview: ((kind: String, payload: JSONObject?) -> Unit)? = null

    private fun handle(s: String) {
        val obj = try { JSONObject(s) } catch (_: Exception) { return }
        when (obj.optString("type")) {
            "preview_guide"      -> onPreview?.invoke("guide",    obj.optJSONObject("guide"))
            "preview_banner"     -> onPreview?.invoke("banner",   obj.optJSONObject("banner"))
            "preview_survey"     -> onPreview?.invoke("survey",   obj.optJSONObject("survey"))
            "fire_event"         -> obj.optString("name").takeIf { it.isNotEmpty() }?.let { Luniq.track(it, mapOf("__pulse_design" to true)) }
            "navigate"           -> onPreview?.invoke("navigate", obj)
            "exit_design_mode"   -> disconnect()
        }
    }

    private fun startCapture() {
        val ht = HandlerThread("Luniq-DesignCapture").apply { start() }
        captureHandler = Handler(ht.looper)
        scheduleCapture()
    }

    private fun scheduleCapture() {
        captureHandler?.postDelayed({
            try { captureFrame() } catch (_: Throwable) {}
            if (connected) scheduleCapture()
        }, 600)
    }

    private fun captureFrame() {
        val a = currentActivity?.get() ?: return
        val window = a.window ?: return
        val view = window.decorView ?: return
        if (view.width <= 0 || view.height <= 0) return
        val scale = 0.5f
        val w = (view.width * scale).toInt().coerceAtLeast(1)
        val h = (view.height * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val locOnScreen = IntArray(2).also { view.getLocationInWindow(it) }
            try {
                PixelCopy.request(window,
                    android.graphics.Rect(locOnScreen[0], locOnScreen[1], locOnScreen[0] + view.width, locOnScreen[1] + view.height),
                    bmp,
                    { result ->
                        if (result == PixelCopy.SUCCESS) sendBitmap(bmp, w, h)
                    },
                    captureHandler ?: mainHandler)
            } catch (_: Throwable) { /* fallback */ fallbackCapture(view, w, h) }
        } else {
            fallbackCapture(view, w, h)
        }
    }

    private fun fallbackCapture(view: View, w: Int, h: Int) {
        // legacy path — draw the view into a bitmap on the main thread.
        mainHandler.post {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            val canvas = android.graphics.Canvas(bmp)
            canvas.scale(w.toFloat() / view.width, h.toFloat() / view.height)
            view.draw(canvas)
            sendBitmap(bmp, w, h)
        }
    }

    private fun sendBitmap(bmp: Bitmap, w: Int, h: Int) {
        if (!connected) return
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 40, baos)
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        send(jsonOf(
            "type" to "frame",
            "format" to "jpeg",
            "width" to w,
            "height" to h,
            "screen" to screen,
            "data" to b64
        ))
    }

    private fun send(obj: JSONObject) {
        if (!connected) return
        ws?.send(obj.toString())
    }

    private fun jsonOf(vararg pairs: Pair<String, Any?>): JSONObject =
        JSONObject().apply { pairs.forEach { put(it.first, it.second ?: JSONObject.NULL) } }

    /* ───────────── UI ───────────── */

    private fun presentCodePrompt(a: Activity) {
        val input = EditText(a).apply { hint = "abc123"; setSingleLine(true) }
        AlertDialog.Builder(a)
            .setTitle("Luniq.AI Design Mode")
            .setMessage("Enter the 6-character code from the dashboard.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Pair") { _, _ -> pair(input.text.toString()) }
            .show()
    }

    private fun installOverlay(a: Activity) {
        if (overlay != null) return
        val root = a.window?.decorView as? ViewGroup ?: return
        val container = FrameLayout(a)
        val pill = TextView(a).apply {
            text = "● LUNIQ.AI DESIGN MODE — connecting…"
            setTextColor(Color.WHITE)
            textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(Color.parseColor("#F2C2856B"))
            }
            setPadding(24, 8, 24, 8)
            gravity = Gravity.CENTER
        }
        val exit = Button(a).apply {
            text = "EXIT"
            setTextColor(Color.WHITE)
            textSize = 10f
            background = GradientDrawable().apply {
                cornerRadius = 20f
                setColor(Color.parseColor("#80000000"))
            }
            minHeight = 0; minimumHeight = 0
            setPadding(20, 4, 20, 4)
            setOnClickListener { disconnect() }
        }
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 16, 16, 0)
            addView(pill, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(exit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = 8 })
        }
        container.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        root.addView(container, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        overlay = container
    }

    private fun setOverlayStatus(s: String) {
        val tv = ((overlay as? FrameLayout)?.getChildAt(0) as? LinearLayout)?.getChildAt(0) as? TextView ?: return
        tv.text = if (s == "paired") "● LUNIQ.AI DESIGN MODE — paired" else "● LUNIQ.AI DESIGN MODE — $s"
    }
}

// MARK: - Luniq facade
@Suppress("unused")
object DesignModeApi {
    fun enable(app: Application, endpoint: String, apiKey: String) {
        DesignMode.configure(endpoint, apiKey, app)
        DesignMode.startPairing()
    }
    fun enableWithCode(app: Application, endpoint: String, apiKey: String, code: String) {
        DesignMode.configure(endpoint, apiKey, app)
        DesignMode.pair(code)
    }
}
