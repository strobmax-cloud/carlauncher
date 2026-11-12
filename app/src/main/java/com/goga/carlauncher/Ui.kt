package com.goga.carlauncher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Design is done in 1280x720 "design pixels" and scaled to the real screen. */
object Ui {
    var s = 1f
    fun u(v: Number): Int = Math.round(v.toFloat() * s)

    // iOS dark palette, flat: no gradients, shadows or glow.
    const val BG = 0xFF000000.toInt()
    const val SURFACE = 0xFF1C1C1E.toInt()
    const val SURFACE2 = 0xFF2C2C2E.toInt()
    const val PRESSED = 0xFF3A3A3C.toInt()
    const val SEPARATOR = 0xFF38383A.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()
    const val TEXT2 = 0xFF8E8E93.toInt()
    const val TEXT3 = 0xFF636366.toInt()
    val ACCENTS = intArrayOf(
        0xFF0A84FF.toInt(), 0xFF30D158.toInt(), 0xFFFF9F0A.toInt(),
        0xFFFF453A.toInt(), 0xFFBF5AF2.toInt(), 0xFF64D2FF.toInt()
    )
    var accent = 0xFF0A84FF.toInt()
    const val R_CARD = 20

    val SANS: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    val LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

    fun alpha(c: Int, a: Float): Int = Color.argb((a * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))

    fun round(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }

    fun oval(color: Int): GradientDrawable =
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    /** Instant pressed state, no ripple animation. */
    fun pressable(color: Int, radius: Int, pressed: Int = PRESSED): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), round(pressed, radius))
            addState(intArrayOf(), round(color, radius))
        }

    fun pressableOval(color: Int, pressed: Int): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), oval(pressed))
            addState(intArrayOf(), oval(color))
        }

    fun text(ctx: Context, size: Int, color: Int = TEXT, face: Typeface = SANS, lines: Int = 1): TextView =
        TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, u(size).toFloat())
            setTextColor(color)
            typeface = face
            includeFontPadding = false
            if (lines > 0) { maxLines = lines; ellipsize = android.text.TextUtils.TruncateAt.END }
        }

    fun icon(ctx: Context, res: Int, tint: Int): ImageView = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    fun clipRound(v: View, radius: Int) {
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius.toFloat())
            }
        }
        v.clipToOutline = true
    }

    /**
     * Themed card background. solid != 0 → flat colour block; otherwise theme glass (pre-blurred wallpaper + tint).
     * blur=false for cards inside scrolling lists (tint only, no wallpaper sampling).
     */
    fun card(v: View, blur: Boolean = true, solid: Int = 0, press: Boolean = false, radius: Int = -1) {
        val t = Themes.current
        val r = u(if (radius >= 0) radius else t.radius)
        v.background = if (solid != 0) (if (press) pressable(solid, r, blend(solid, 0x24FFFFFF)) else round(solid, r))
        else GlassDrawable(v, r.toFloat(), t.tint, t.border, blur && t.glass, press)
        Glass.register(v)
    }

    fun blend(base: Int, over: Int): Int {
        val a = Color.alpha(over) / 255f
        return Color.argb(Color.alpha(base), (Color.red(base) * (1 - a) + Color.red(over) * a).toInt(),
            (Color.green(base) * (1 - a) + Color.green(over) * a).toInt(), (Color.blue(base) * (1 - a) + Color.blue(over) * a).toInt())
    }

    fun lin(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
    fun frame(w: Int, h: Int, gravity: Int = Gravity.CENTER) = FrameLayout.LayoutParams(w, h, gravity)
    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun vbox(ctx: Context) = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    fun hbox(ctx: Context) = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    /** Round button with a centered icon. */
    fun iconButton(ctx: Context, res: Int, size: Int, iconSize: Int, bg: Int, fg: Int, pressed: Int = PRESSED, onClick: () -> Unit): FrameLayout =
        FrameLayout(ctx).apply {
            background = pressableOval(bg, pressed)
            isClickable = true
            setOnClickListener { onClick() }
            addView(icon(ctx, res, fg), frame(u(iconSize), u(iconSize)))
            layoutParams = LinearLayout.LayoutParams(u(size), u(size))
        }

    /** Pill-shaped text button. */
    fun pill(ctx: Context, label: String, bg: Int = SURFACE2, fg: Int = TEXT, onClick: () -> Unit): TextView =
        text(ctx, 22, fg, MEDIUM).apply {
            text = label
            gravity = Gravity.CENTER
            setPadding(u(28), 0, u(28), 0)
            minHeight = u(72)
            background = pressable(bg, u(36))
            isClickable = true
            setOnClickListener { onClick() }
        }
}
