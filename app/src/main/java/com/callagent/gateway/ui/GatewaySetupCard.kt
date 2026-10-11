package com.callagent.gateway.ui

import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.callagent.gateway.ui.GatewaySetupChecklist.Action
import com.callagent.gateway.ui.GatewaySetupChecklist.Status
import com.google.android.material.button.MaterialButton
import com.google.android.material.R as MaterialR

/** Renders [GatewaySetupChecklist.Summary] into the home screen's setup card. */
class GatewaySetupCard(
    private val headline: TextView,
    private val steps: LinearLayout,
    private val onAction: (Action) -> Unit
) {
    private var rendered: GatewaySetupChecklist.Summary? = null

    init {
        ViewCompat.setAccessibilityHeading(headline, true)
    }

    fun render(summary: GatewaySetupChecklist.Summary) {
        if (summary == rendered) return
        rendered = summary
        headline.text = summary.headline
        steps.removeAllViews()
        summary.steps.forEach { step -> steps.addView(stepRow(step)) }
    }

    private fun stepRow(step: GatewaySetupChecklist.Step): View {
        val context = steps.context
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(0, dp(8), 0, dp(8))
        }
        val (glyph, colorAttr, containerAttr) = when (step.status) {
            Status.DONE -> Triple("✓", MaterialR.attr.colorOnTertiaryContainer, MaterialR.attr.colorTertiaryContainer)
            Status.NEXT -> Triple("${step.id.ordinal + 1}", MaterialR.attr.colorOnPrimary, androidx.appcompat.R.attr.colorPrimary)
            Status.WARNING -> Triple("!", MaterialR.attr.colorOnErrorContainer, MaterialR.attr.colorErrorContainer)
            Status.BLOCKED -> Triple("${step.id.ordinal + 1}", MaterialR.attr.colorOnSurfaceVariant, MaterialR.attr.colorSurfaceContainerHighest)
            Status.OPTIONAL -> Triple("+", MaterialR.attr.colorOnSecondaryContainer, MaterialR.attr.colorSecondaryContainer)
        }
        val badge = TextView(context).apply {
            text = glyph
            gravity = Gravity.CENTER
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(color(colorAttr))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(color(containerAttr))
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(badge, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(12) })

        val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(context).apply {
            this.text = step.title
            setTextAppearance(MaterialR.style.TextAppearance_Material3_TitleSmall)
            setTextColor(color(if (step.status == Status.BLOCKED) MaterialR.attr.colorOnSurfaceVariant else MaterialR.attr.colorOnSurface))
        })
        text.addView(TextView(context).apply {
            this.text = step.detail
            setTextAppearance(MaterialR.style.TextAppearance_Material3_BodySmall)
            setTextColor(color(MaterialR.attr.colorOnSurfaceVariant))
        })
        row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.contentDescription = "${step.title}：${statusLabel(step.status)}。${step.detail}"

        val label = step.actionLabel
        if (label != null && step.action != Action.NONE) {
            val button = MaterialButton(context, null,
                if (step.status == Status.NEXT) com.google.android.material.R.attr.materialButtonStyle
                else com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                this.text = label
                isAllCaps = false
                minHeight = dp(48)
                setOnClickListener { onAction(step.action) }
            }
            if (step.status == Status.NEXT) {
                button.backgroundTintList = ColorStateList.valueOf(color(androidx.appcompat.R.attr.colorPrimary))
            }
            row.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        }
        return row
    }

    private fun statusLabel(status: Status) = when (status) {
        Status.DONE -> "已完成"
        Status.NEXT -> "下一步"
        Status.WARNING -> "需要注意"
        Status.BLOCKED -> "等待前一步"
        Status.OPTIONAL -> "可选"
    }

    private fun color(attr: Int): Int {
        val value = TypedValue()
        steps.context.theme.resolveAttribute(attr, value, true)
        return value.data
    }

    private fun dp(value: Int): Int = (value * steps.resources.displayMetrics.density).toInt()
}
