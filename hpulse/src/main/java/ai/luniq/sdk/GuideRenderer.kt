@file:Suppress("unused")

package ai.luniq.sdk

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Multi-step guide renderer.  Mirrors iOS GuideEngine + the web SDK's
 * drawGuideStep — each step is a card with title + body + step dots +
 * Skip / Next/Got it actions.  The card is shown via [AlertDialog] so it
 * floats on top of any host activity without us needing to mutate the
 * decor view.
 *
 * If a step provides a [GuideStep.selector] that maps to a view in the
 * current Activity (by tag, contentDescription, or "#resource-id"), the
 * step's tooltip is anchored beside that view by tinting an overlay
 * around it.  Otherwise the step renders as a centered modal.
 */
internal object GuideRenderer {

    fun render(
        activity: Activity,
        guide: Guide,
        onStepShown: (Int) -> Unit,
        onStepAdvanced: (Int) -> Unit,
        onCompleted: () -> Unit,
        onDismissed: (Int) -> Unit,
    ) {
        showStep(activity, guide, 0, onStepShown, onStepAdvanced, onCompleted, onDismissed)
    }

    private fun showStep(
        activity: Activity,
        guide: Guide,
        step: Int,
        onStepShown: (Int) -> Unit,
        onStepAdvanced: (Int) -> Unit,
        onCompleted: () -> Unit,
        onDismissed: (Int) -> Unit,
    ) {
        if (activity.isFinishing) return
        val s = guide.steps.getOrNull(step) ?: run { onCompleted(); return }
        val total = guide.steps.size
        val isLast = step == total - 1

        val card = buildCard(activity, s, step, total, isLast)

        val dialog = AlertDialog.Builder(activity)
            .setView(card.root)
            .setCancelable(true)
            .create()

        // Optional anchor highlight — only valid while the dialog is up.
        val highlight = if (s.selector.isNotEmpty()) {
            tryHighlightAnchor(activity, s.selector)
        } else null

        dialog.setOnDismissListener {
            highlight?.remove()
        }

        card.skipBtn.setOnClickListener {
            highlight?.remove()
            dialog.dismiss()
            onDismissed(step)
        }
        card.nextBtn.setOnClickListener {
            highlight?.remove()
            dialog.dismiss()
            onStepAdvanced(step)
            if (isLast) onCompleted()
            else showStep(activity, guide, step + 1, onStepShown, onStepAdvanced, onCompleted, onDismissed)
        }
        // System back / outside-tap → treat as dismiss for the current step.
        dialog.setOnCancelListener {
            highlight?.remove()
            onDismissed(step)
        }

        dialog.show()
        // Match the rounded card look — clear default dialog background.
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        onStepShown(step)
    }

    // ── Card construction ────────────────────────────────────────────────

    private class Card(val root: View, val skipBtn: View, val nextBtn: View)

    private fun buildCard(
        activity: Activity,
        step: GuideStep,
        index: Int,
        total: Int,
        isLast: Boolean,
    ): Card {
        val pad = dp(activity, 18)

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 14).toFloat()
                setColor(Color.WHITE)
            }
        }

        if (step.title.isNotEmpty()) {
            container.addView(TextView(activity).apply {
                text = step.title
                setTextColor(Color.parseColor("#14110D"))
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            })
        }
        if (step.body.isNotEmpty()) {
            container.addView(TextView(activity).apply {
                text = step.body
                setTextColor(Color.parseColor("#3A3733"))
                textSize = 14f
                setLineSpacing(0f, 1.35f)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(activity, 6); bottomMargin = dp(activity, 14) }
            })
        }

        // Step dots
        if (total > 1) {
            val dots = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(activity, 12) }
            }
            for (i in 0 until total) {
                val active = i == index
                val w = dp(activity, if (active) 18 else 5)
                val h = dp(activity, 5)
                dots.addView(View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(w, h).apply {
                        rightMargin = dp(activity, 4)
                    }
                    background = GradientDrawable().apply {
                        cornerRadius = h.toFloat()
                        setColor(Color.parseColor(if (active) "#D79750" else "#ECECEA"))
                    }
                })
            }
            container.addView(dots)
        }

        // Action row
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val skip = TextView(activity).apply {
            text = "Skip"
            setTextColor(Color.parseColor("#6F6C66"))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(activity, 4), dp(activity, 6), dp(activity, 12), dp(activity, 6))
            isAllCaps = true
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        row.addView(skip)

        val next = TextView(activity).apply {
            text = if (isLast) "Got it" else if (step.ctaLabel.isNotEmpty()) step.ctaLabel else "Next"
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 13f
            setPadding(dp(activity, 16), dp(activity, 9), dp(activity, 16), dp(activity, 9))
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 999).toFloat()
                setColor(Color.parseColor("#14110D"))
            }
        }
        row.addView(next)
        container.addView(row)

        return Card(container, skip, next)
    }

    // ── Anchor highlight ─────────────────────────────────────────────────

    private class Highlight(val view: View) {
        private val originalBg = view.background
        init {
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f
                setColor(Color.parseColor("#33D79750"))
                setStroke(4, Color.parseColor("#D79750"))
            }
        }
        fun remove() { view.background = originalBg }
    }

    private fun tryHighlightAnchor(activity: Activity, selector: String): Highlight? {
        val root = activity.window?.decorView ?: return null
        val match = findBySelector(root, selector) ?: return null
        return try { Highlight(match) } catch (_: Exception) { null }
    }

    private fun findBySelector(v: View, selector: String): View? {
        if (matchesSelector(v, selector)) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findBySelector(v.getChildAt(i), selector)?.let { return it }
            }
        }
        return null
    }

    private fun matchesSelector(v: View, selector: String): Boolean {
        val tag = v.tag as? String
        if (tag != null && tag == selector) return true
        val cd = v.contentDescription?.toString()
        if (cd != null && cd == selector) return true
        if (selector.startsWith("#")) {
            try {
                val name = v.resources.getResourceEntryName(v.id)
                if (name == selector.substring(1)) return true
            } catch (_: Exception) { /* unnamed */ }
        }
        return false
    }

    private fun dp(activity: Activity, v: Int): Int =
        (v * activity.resources.displayMetrics.density).toInt()
}
