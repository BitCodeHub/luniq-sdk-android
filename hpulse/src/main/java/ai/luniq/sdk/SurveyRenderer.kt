@file:Suppress("unused")

package ai.luniq.sdk

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Multi-question survey renderer.  Mirrors the iOS SurveyRenderer and the
 * web SDK's renderSurvey().  We deliberately avoid the AndroidX
 * Material BottomSheetDialogFragment dependency — keeping the SDK with
 * zero required Material deps means host apps that ship plain
 * androidx.appcompat (or none at all) still get surveys.  Visually we
 * present a centered dialog card with a sheet-like grabber to read
 * similarly to the iOS .pageSheet.
 *
 * Supported question types: rating | single | multi | text.
 */
internal object SurveyRenderer {

    fun render(
        activity: Activity,
        survey: Survey,
        onCompleted: (Map<String, Any?>) -> Unit,
        onDismissed: () -> Unit,
    ) {
        if (activity.isFinishing) return
        val state = SurveyState(activity, survey, onCompleted, onDismissed)
        state.start()
    }

    /**
     * Encapsulates a single in-flight survey.  The dialog is rebuilt from
     * scratch on every question to keep the layout simple — no
     * fragment-manager state to thread, no half-rendered transitions.
     */
    private class SurveyState(
        private val activity: Activity,
        private val survey: Survey,
        private val onCompleted: (Map<String, Any?>) -> Unit,
        private val onDismissed: () -> Unit,
    ) {
        private val answers = LinkedHashMap<String, Any?>()
        private var qIndex = 0
        private var dialog: AlertDialog? = null
        private var completed = false

        fun start() = renderQuestion()

        private fun renderQuestion() {
            val q = survey.questions.getOrNull(qIndex) ?: run { finish(); return }
            val card = buildCard(q)
            val d = AlertDialog.Builder(activity).setView(card).setCancelable(true).create()
            d.setOnCancelListener {
                if (!completed) onDismissed()
            }
            d.show()
            d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            dialog = d
        }

        private fun submitAnswer(value: Any?) {
            val q = survey.questions[qIndex]
            answers[q.id.ifEmpty { "q$qIndex" }] = value
            qIndex += 1
            dialog?.dismiss()
            if (qIndex >= survey.questions.size) finish()
            else renderQuestion()
        }

        private fun finish() {
            if (completed) return
            completed = true
            // Quick "Thanks" flash as the iOS sheet shows on dismiss.
            val thanks = TextView(activity).apply {
                text = "Thanks — we've got it."
                gravity = Gravity.CENTER
                setPadding(dp(40), dp(36), dp(40), dp(36))
                setTextColor(Color.parseColor("#3A3733"))
                textSize = 14f
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.WHITE)
                }
            }
            val flash = AlertDialog.Builder(activity).setView(thanks).create()
            flash.show()
            flash.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try { flash.dismiss() } catch (_: Exception) {}
                onCompleted(answers)
            }, 1500)
        }

        // ── Card construction ────────────────────────────────────────────

        private fun buildCard(q: SurveyQuestion): View {
            val pad = dp(20)
            val outer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.WHITE)
                }
            }

            // Header row: name + close
            val header = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }
            header.addView(TextView(activity).apply {
                text = (survey.name.ifEmpty { "Quick question" }).uppercase()
                setTextColor(Color.parseColor("#D79750"))
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.14f
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            header.addView(TextView(activity).apply {
                text = "×"
                setTextColor(Color.parseColor("#6F6C66"))
                textSize = 20f
                setPadding(dp(8), 0, dp(4), 0)
                setOnClickListener {
                    dialog?.dismiss()
                    if (!completed) onDismissed()
                }
            })
            outer.addView(header)

            // Prompt
            outer.addView(TextView(activity).apply {
                text = q.prompt
                setTextColor(Color.parseColor("#14110D"))
                textSize = 14f
                setLineSpacing(0f, 1.4f)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(14) }
            })

            // Body — type-specific
            val body: View = when (q.type) {
                "rating" -> buildRatingRow(q)
                "single" -> buildSingleChoice(q)
                "multi"  -> buildMultiChoice(q)
                else      -> buildTextInput()
            }
            outer.addView(body)

            // Wrap in scrollview so long surveys stay usable on small phones.
            return ScrollView(activity).apply { addView(outer) }
        }

        private fun buildRatingRow(q: SurveyQuestion): View {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val scale = if (q.scale > 0) q.scale.coerceIn(2, 11) else 5
            // NPS-flavored if the survey kind is "nps" — render 0..10.
            val start = if (survey.kind == "nps") 0 else 1
            val end   = if (survey.kind == "nps") 10 else scale
            for (i in start..end) {
                val btn = TextView(activity).apply {
                    text = i.toString()
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor("#14110D"))
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(
                        0, dp(38), 1f
                    ).apply { rightMargin = dp(4) }
                    setPadding(dp(2), dp(8), dp(2), dp(8))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        setStroke(2, Color.parseColor("#E3E3E0"))
                        setColor(Color.WHITE)
                    }
                    setOnClickListener { submitAnswer(i) }
                }
                row.addView(btn)
            }
            return row
        }

        private fun buildSingleChoice(q: SurveyQuestion): View {
            val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            for (c in q.choices) {
                col.addView(choiceButton(c) { submitAnswer(c) })
            }
            return col
        }

        private fun buildMultiChoice(q: SurveyQuestion): View {
            val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val picked = LinkedHashSet<String>()
            for (c in q.choices) {
                col.addView(choiceButton(c) { btn ->
                    if (picked.contains(c)) {
                        picked.remove(c)
                        applyChoiceStyle(btn, false)
                    } else {
                        picked.add(c)
                        applyChoiceStyle(btn, true)
                    }
                })
            }
            col.addView(primaryButton("Submit") { submitAnswer(picked.toList()) })
            return col
        }

        private fun buildTextInput(): View {
            val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val input = EditText(activity).apply {
                hint = "Type your answer..."
                setHintTextColor(Color.parseColor("#A09D97"))
                setTextColor(Color.parseColor("#14110D"))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 2
                gravity = Gravity.TOP or Gravity.START
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setStroke(2, Color.parseColor("#E3E3E0"))
                    setColor(Color.WHITE)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) }
            }
            col.addView(input)
            col.addView(primaryButton("Send") { submitAnswer(input.text?.toString()?.trim().orEmpty()) })
            return col
        }

        // ── Reusable buttons ─────────────────────────────────────────────

        private fun choiceButton(label: String, onClick: (View) -> Unit): View {
            val btn = TextView(activity).apply {
                text = label
                setTextColor(Color.parseColor("#14110D"))
                textSize = 13f
                setPadding(dp(12), dp(10), dp(12), dp(10))
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(6) }
            }
            applyChoiceStyle(btn, false)
            btn.setOnClickListener { onClick(btn) }
            return btn
        }

        private fun applyChoiceStyle(view: View, picked: Boolean) {
            if (view !is TextView) return
            view.background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setStroke(2, Color.parseColor(if (picked) "#14110D" else "#E3E3E0"))
                setColor(Color.parseColor(if (picked) "#14110D" else "#FFFFFF"))
            }
            view.setTextColor(Color.parseColor(if (picked) "#FFFFFF" else "#14110D"))
        }

        private fun primaryButton(label: String, onClick: () -> Unit): View {
            val btn = TextView(activity).apply {
                text = label
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(14), dp(11), dp(14), dp(11))
                background = GradientDrawable().apply {
                    cornerRadius = dp(999).toFloat()
                    setColor(Color.parseColor("#14110D"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) }
                setOnClickListener { onClick() }
            }
            return btn
        }

        private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
    }
}
