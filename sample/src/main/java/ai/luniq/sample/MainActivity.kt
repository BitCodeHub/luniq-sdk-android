package ai.luniq.sample

import ai.luniq.sdk.Luniq
import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Minimal sample driving the Luniq SDK. Shows two buttons: one tracks an event,
 * the other identifies a demo user. The SDK is initialized inline in onCreate
 * for brevity — production apps should call Luniq.start() from Application.onCreate.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Luniq.start(
            app         = application,
            apiKey      = "lq_live_demo",
            endpoint    = "https://uselunaai.com",
            environment = "dev",
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER
            setPadding(48, 96, 48, 48)
        }

        val title = TextView(this).apply {
            text     = "Luniq SDK sample"
            textSize = 22f
            gravity  = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text     = "Endpoint: https://uselunaai.com\nEnv: dev"
            textSize = 12f
            gravity  = Gravity.CENTER
            setPadding(0, 16, 0, 48)
        }

        val trackBtn = Button(this).apply {
            text = "Track event"
            setOnClickListener {
                Luniq.track("button_click", mapOf("source" to "sample_app"))
                Toast.makeText(context, "tracked: button_click", Toast.LENGTH_SHORT).show()
            }
        }

        val identifyBtn = Button(this).apply {
            text = "Identify user"
            setOnClickListener {
                Luniq.identify(
                    accountId = "demo_user_42",
                    traits    = mapOf("plan" to "free"),
                )
                Toast.makeText(context, "identified: demo_user_42", Toast.LENGTH_SHORT).show()
            }
        }

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = 16 }

        root.addView(title)
        root.addView(subtitle)
        root.addView(trackBtn, lp)
        root.addView(identifyBtn, lp)

        setContentView(root)
    }
}
