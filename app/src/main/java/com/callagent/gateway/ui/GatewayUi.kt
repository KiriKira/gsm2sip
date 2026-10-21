package com.callagent.gateway.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.callagent.gateway.R
import com.google.android.material.color.MaterialColors
import com.google.android.material.textview.MaterialTextView
import com.google.android.material.R as MaterialR

/**
 * Shared look of the Homura screens, kept the same as the Madoka host app's
 * HostUi: lists are stacks of rounded tiles (large corners at the ends of a
 * group, small corners between items), people get colored monogram avatars,
 * and search is a full-width pill.
 */
object GatewayUi {
    /** Where a tile sits in its group, which decides its corner radii. */
    enum class Position { SINGLE, FIRST, MIDDLE, LAST }

    fun position(index: Int, count: Int): Position = when {
        count <= 1 -> Position.SINGLE
        index == 0 -> Position.FIRST
        index == count - 1 -> Position.LAST
        else -> Position.MIDDLE
    }

    fun color(context: Context, attr: Int): Int = MaterialColors.getColor(context, attr, 0)

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    /** Rounded tile background with a ripple, for one item of a grouped list. */
    fun tileBackground(context: Context, position: Position, fillAttr: Int = MaterialR.attr.colorSurfaceContainer): Drawable {
        val large = dp(context, 24).toFloat()
        val small = dp(context, 6).toFloat()
        val top = if (position == Position.SINGLE || position == Position.FIRST) large else small
        val bottom = if (position == Position.SINGLE || position == Position.LAST) large else small
        val radii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
        val shape = GradientDrawable().apply {
            cornerRadii = radii
            setColor(color(context, fillAttr))
        }
        val mask = GradientDrawable().apply { cornerRadii = radii; setColor(0xFFFFFFFF.toInt()) }
        val ripple = ColorStateList.valueOf(color(context, MaterialR.attr.colorOnSurface) and 0x1FFFFFFF)
        return RippleDrawable(ripple, shape, mask)
    }

    /** Layout params for a tile: groups have 2dp gaps between their items. */
    fun tileParams(context: Context, position: Position): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (position == Position.MIDDLE || position == Position.LAST) topMargin = dp(context, 2)
        }

    /** Pill background, e.g. for the search bar. */
    fun pill(context: Context, fillAttr: Int = MaterialR.attr.colorSurfaceContainerHigh): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(context, 28).toFloat()
            setColor(color(context, fillAttr))
        }

    /** Small, all-caps-free section heading above a group ("今天", "账号"). */
    fun groupHeader(context: Context, text: String): TextView = MaterialTextView(context).apply {
        this.text = text
        setTextAppearance(MaterialR.style.TextAppearance_Material3_TitleSmall)
        setTextColor(color(context, androidx.appcompat.R.attr.colorPrimary))
        setPadding(dp(context, 16), dp(context, 20), dp(context, 16), dp(context, 8))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private val avatarPalettes = listOf(
        MaterialR.attr.colorPrimaryContainer to MaterialR.attr.colorOnPrimaryContainer,
        MaterialR.attr.colorSecondaryContainer to MaterialR.attr.colorOnSecondaryContainer,
        MaterialR.attr.colorTertiaryContainer to MaterialR.attr.colorOnTertiaryContainer,
    )

    /**
     * A circular avatar: the first letter of a contact name, or a person
     * icon for a bare number. The color is stable per [key].
     */
    fun avatar(context: Context, name: String?, key: String, sizeDp: Int = 40): View {
        val (fill, onFill) = avatarPalettes[(key.hashCode() and Int.MAX_VALUE) % avatarPalettes.size]
        val size = dp(context, sizeDp)
        val frame = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color(context, fill))
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
        val initial = name?.trim()?.firstOrNull { it.isLetter() }
        if (initial != null && name.none { it.isDigit() }) {
            frame.addView(MaterialTextView(context).apply {
                text = initial.uppercaseChar().toString()
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_PX, size * 0.42f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(color(context, onFill))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            frame.addView(ImageView(context).apply {
                setImageResource(R.drawable.call_ui_person)
                imageTintList = ColorStateList.valueOf(color(context, onFill))
                val inset = (size * 0.22f).toInt()
                setPadding(inset, inset, inset, inset)
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        return frame
    }

    /** A circular tonal icon used at the start of a settings tile. */
    fun iconBadge(context: Context, drawable: Int, sizeDp: Int = 40): ImageView = ImageView(context).apply {
        setImageResource(drawable)
        imageTintList = ColorStateList.valueOf(color(context, MaterialR.attr.colorOnSecondaryContainer))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color(context, MaterialR.attr.colorSecondaryContainer))
        }
        val inset = dp(context, 8)
        setPadding(inset, inset, inset, inset)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
    }

    fun title(context: Context, text: CharSequence, colorAttr: Int = MaterialR.attr.colorOnSurface): TextView =
        MaterialTextView(context).apply {
            this.text = text
            setTextAppearance(MaterialR.style.TextAppearance_Material3_BodyLarge)
            setTextColor(color(context, colorAttr))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

    fun subtitle(context: Context, text: CharSequence, colorAttr: Int = MaterialR.attr.colorOnSurfaceVariant, lines: Int = 1): TextView =
        MaterialTextView(context).apply {
            this.text = text
            setTextAppearance(MaterialR.style.TextAppearance_Material3_BodyMedium)
            setTextColor(color(context, colorAttr))
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
        }

    /**
     * A settings-style tile: optional leading icon, title, optional summary
     * and an optional trailing view (switch, chevron, value).
     */
    fun tile(
        context: Context,
        position: Position,
        title: CharSequence,
        summary: CharSequence? = null,
        icon: Int? = null,
        trailing: View? = null,
        onClick: (() -> Unit)? = null,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(context, 64)
        setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
        background = tileBackground(context, position)
        layoutParams = tileParams(context, position)
        if (onClick != null) {
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        icon?.let { addView(iconBadge(context, it), LinearLayout.LayoutParams(dp(context, 40), dp(context, 40)).apply { marginEnd = dp(context, 16) }) }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(title(context, title).apply { maxLines = 2 })
        if (!summary.isNullOrBlank()) texts.addView(subtitle(context, summary, lines = 4).apply { setPadding(0, dp(context, 2), 0, 0) })
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        trailing?.let { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(context, 12) }) }
    }

    fun chevron(context: Context): ImageView = ImageView(context).apply {
        setImageResource(R.drawable.ic_ui_chevron)
        imageTintList = ColorStateList.valueOf(color(context, MaterialR.attr.colorOnSurfaceVariant))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun tint(context: Context, drawable: Int, colorAttr: Int): Drawable? =
        ContextCompat.getDrawable(context, drawable)?.mutate()?.apply { setTint(color(context, colorAttr)) }
}
