package com.callagent.gateway.ui

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.callagent.gateway.ui.GatewaySetupChecklist.Action
import com.callagent.gateway.ui.GatewaySetupChecklist.Status
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.google.android.material.R as MaterialR

/** Renders [GatewaySetupChecklist.Summary] as a group of tiles on the status page. */
class GatewaySetupCard(
    private val container: LinearLayout,
    private val onAction: (Action) -> Unit
) {
    private var rendered: GatewaySetupChecklist.Summary? = null

    fun render(summary: GatewaySetupChecklist.Summary) {
        if (summary == rendered) return
        rendered = summary
        val context = container.context
        container.removeAllViews()
        container.addView(GatewayUi.groupHeader(context, summary.headline).also {
            ViewCompat.setAccessibilityHeading(it, true)
        })
        summary.steps.forEachIndexed { index, step ->
            container.addView(stepTile(step, GatewayUi.position(index, summary.steps.size)))
        }
    }

    private fun stepTile(step: GatewaySetupChecklist.Step, position: GatewayUi.Position): View {
        val context = container.context
        val (glyph, colorAttr, containerAttr) = when (step.status) {
            Status.DONE -> Triple("✓", MaterialR.attr.colorOnTertiaryContainer, MaterialR.attr.colorTertiaryContainer)
            Status.NEXT -> Triple("${step.id.ordinal + 1}", MaterialR.attr.colorOnPrimary, androidx.appcompat.R.attr.colorPrimary)
            Status.WARNING -> Triple("!", MaterialR.attr.colorOnErrorContainer, MaterialR.attr.colorErrorContainer)
            Status.BLOCKED -> Triple("${step.id.ordinal + 1}", MaterialR.attr.colorOnSurfaceVariant, MaterialR.attr.colorSurfaceContainerHighest)
            Status.OPTIONAL -> Triple("+", MaterialR.attr.colorOnSecondaryContainer, MaterialR.attr.colorSecondaryContainer)
        }
        val label = step.actionLabel
        val button = if (label != null && step.action != Action.NONE) {
            MaterialButton(context, null,
                if (step.status == Status.NEXT) MaterialR.attr.materialButtonStyle
                else MaterialR.attr.materialButtonOutlinedStyle).apply {
                this.text = label
                isAllCaps = false
                minHeight = GatewayUi.dp(context, 48)
                cornerRadius = GatewayUi.dp(context, 24)
                if (step.status == Status.NEXT) {
                    backgroundTintList = ColorStateList.valueOf(GatewayUi.color(context, androidx.appcompat.R.attr.colorPrimary))
                }
                setOnClickListener { onAction(step.action) }
            }
        } else null
        val tile = GatewayUi.tile(context, position, step.title, summary = step.detail, trailing = button)
        tile.addView(badge(glyph, colorAttr, containerAttr), 0, LinearLayout.LayoutParams(
            GatewayUi.dp(context, 32), GatewayUi.dp(context, 32)).apply { marginEnd = GatewayUi.dp(context, 16) })
        if (step.status == Status.BLOCKED) {
            ((tile.getChildAt(1) as? LinearLayout)?.getChildAt(0) as? TextView)
                ?.setTextColor(GatewayUi.color(context, MaterialR.attr.colorOnSurfaceVariant))
        }
        tile.contentDescription = "${step.title}：${statusLabel(step.status)}。${step.detail}"
        return tile
    }

    private fun badge(glyph: String, colorAttr: Int, containerAttr: Int): TextView =
        MaterialTextView(container.context).apply {
            text = glyph
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(GatewayUi.color(context, colorAttr))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(GatewayUi.color(context, containerAttr))
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    private fun statusLabel(status: Status) = when (status) {
        Status.DONE -> "已完成"
        Status.NEXT -> "下一步"
        Status.WARNING -> "需要注意"
        Status.BLOCKED -> "等待前一步"
        Status.OPTIONAL -> "可选"
    }
}
