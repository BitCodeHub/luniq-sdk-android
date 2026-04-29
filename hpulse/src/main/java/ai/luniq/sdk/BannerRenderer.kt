@file:Suppress("unused")

package ai.luniq.sdk

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Renders a sticky banner pinned to the top or bottom of the active
 * Activity's decorView.  No external dependencies — image (when supplied)
 * is loaded on a background executor and decoded into an ImageView the
 * SDK builds at runtime.  Mirrors the iOS [renderBanner] in
 * Sources/LuniqSDK/Guides.swift and the web SDK's renderBanner().
 */
internal object BannerRenderer {
    private val ioExec = Executors.newSingleThreadExecutor()

    fun render(
        activity: Activity,
        banner: Banner,
        onClick: () -> Unit,
        onDismiss: () -> Unit,
    ): View? {
        val root = activity.window?.decorView as? ViewGroup ?: return null

        val placement = if (banner.placement == "bottom") "bottom" else "top"
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(Color.parseColor("#14110D")) }
            setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 12), dp(activity, 12))
        }

        // Optional leading image
        if (banner.imageUrl.isNotEmpty()) {
            val img = android.widget.ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 36)).apply {
                    rightMargin = dp(activity, 12)
                }
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    cornerRadius = dp(activity, 6).toFloat()
                    setColor(Color.parseColor("#33FFFFFF"))
                }
            }
            container.addView(img)
            loadImageAsync(banner.imageUrl, img)
        }

        // Title + body block
        val textCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        if (banner.title.isNotEmpty()) {
            textCol.addView(TextView(activity).apply {
                text = banner.title
                setTextColor(Color.WHITE)
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
            })
        }
        if (banner.body.isNotEmpty()) {
            textCol.addView(TextView(activity).apply {
                text = banner.body
                setTextColor(Color.parseColor("#E5E5E5"))
                textSize = 13f
            })
        }
        container.addView(textCol)

        // Optional CTA button
        if (banner.ctaLabel.isNotEmpty()) {
            val cta = TextView(activity).apply {
                text = banner.ctaLabel
                setTextColor(Color.parseColor("#14110D"))
                setTypeface(typeface, Typeface.BOLD)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(activity, 14), dp(activity, 7), dp(activity, 14), dp(activity, 7))
                background = GradientDrawable().apply {
                    cornerRadius = dp(activity, 999).toFloat()
                    setColor(Color.parseColor("#D79750"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(activity, 8) }
            }
            cta.setOnClickListener {
                onClick()
                if (banner.linkUrl.isNotEmpty()) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(banner.linkUrl))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        activity.startActivity(intent)
                    } catch (_: Exception) { /* invalid URL */ }
                }
            }
            container.addView(cta)
        }

        // Dismiss "x"
        val close = TextView(activity).apply {
            text = "×"
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(dp(activity, 10), dp(activity, 2), dp(activity, 4), dp(activity, 2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(activity, 4) }
        }
        container.addView(close)

        // Position via FrameLayout params on the decor view
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            if (placement == "bottom") Gravity.BOTTOM else Gravity.TOP,
        )
        container.layoutParams = params
        container.elevation = dp(activity, 8).toFloat()

        // Lay out below the status bar / above the nav bar so the banner
        // doesn't slide under system chrome.
        container.fitsSystemWindows = true

        // Slide-in animation
        container.translationY = if (placement == "bottom") dp(activity, 80).toFloat() else -dp(activity, 80).toFloat()
        container.alpha = 0f

        root.addView(container)
        container.animate()
            .translationY(0f).alpha(1f).setDuration(280).start()

        val dismiss: () -> Unit = {
            container.animate()
                .translationY(if (placement == "bottom") dp(activity, 80).toFloat() else -dp(activity, 80).toFloat())
                .alpha(0f).setDuration(220)
                .withEndAction { (container.parent as? ViewGroup)?.removeView(container) }
                .start()
            onDismiss()
        }
        close.setOnClickListener { dismiss() }
        return container
    }

    private fun loadImageAsync(url: String, target: android.widget.ImageView) {
        ioExec.execute {
            try {
                val u = URL(url)
                val conn = u.openConnection() as HttpURLConnection
                conn.connectTimeout = 8_000; conn.readTimeout = 12_000
                val bmp = conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
                conn.disconnect()
                if (bmp != null) target.post { target.setImageBitmap(bmp) }
            } catch (_: Exception) { /* leave placeholder */ }
        }
    }

    private fun dp(activity: Activity, v: Int): Int =
        (v * activity.resources.displayMetrics.density).toInt()
}
