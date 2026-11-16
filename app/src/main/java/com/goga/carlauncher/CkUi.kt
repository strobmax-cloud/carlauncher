package com.goga.carlauncher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.goga.carlauncher.Ck.put

/** Small widgets of the C / D screens (design px). */
object W {
    fun hbox(c: Context, gap: Int = 0) = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; if (gap > 0) dividerPadding = 0 }
    fun vbox(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    fun lin(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
    const val M = -1; const val WR = -2

    /** pill button «⧉ Фон» (icon optional) */
    fun pill(c: Context, icon: String?, label: String?, h: Int = 50, on: Boolean = false, onClick: () -> Unit): FrameLayout {
        val f = FrameLayout(c)
        val row = hbox(c).apply { gravity = Gravity.CENTER }
        if (icon != null) row.addView(Ck.icon(c, icon), lin(24, 24))
        if (label != null) row.addView(Ck.text(c, 20f, Color.WHITE, Ck.face(true)).apply { text = label }, lin(WR, WR).apply { leftMargin = if (icon != null) 10 else 0 })
        f.addView(row, FrameLayout.LayoutParams(WR, WR, Gravity.CENTER))
        f.background = Panel.pill(if (photoBehind) 0x73000000 else 0x14FFFFFF, if (on) Ck.accent() else 0x4DFFFFFF, h.toFloat()).apply { if (on) { strokeW = 2f; glow = Ck.accent() } }
        f.contentDescription = label ?: icon
        Ck.pressable(f) { onClick() }
        return f
    }
    /** set by the screen: a photo / video is behind the interface (darker glass) */
    var photoBehind = false

    /** segmented control */
    fun seg(c: Context, options: List<String>, sel: Int, h: Int = 54, textSize: Float = 20f, onPick: (Int) -> Unit): LinearLayout {
        val box = hbox(c).apply { setPadding(4, 4, 4, 4); background = Panel(0x33000000, 0x29FFFFFF, 1f, floatArrayOf(14f, 14f, 14f, 14f), false) }
        options.forEachIndexed { i, s ->
            val t = Ck.text(c, textSize, if (i == sel) (if (Ck.isC) Color.BLACK else 0xFF1A1020.toInt()) else 0xCCFFFFFF.toInt(), Ck.face(i == sel), gravity = Gravity.CENTER).apply { text = s; setPadding(18, 0, 18, 0) }
            if (i == sel) t.background = Panel(if (Ck.isC) Ck.LIME else Ck.accent(), 0, 0f, floatArrayOf(11f, 11f, 11f, 11f), false)
            Ck.pressable(t) { onPick(i) }
            box.addView(t, lin(WR, h - 8))
        }
        return box
    }
    /** iOS-like switch */
    class Toggle(c: Context, var on: Boolean, val onChange: (Boolean) -> Unit) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        init { Ck.pressable(this) { on = !on; invalidate(); onChange(on) }; contentDescription = "Переключатель" }
        override fun onDraw(cv: Canvas) {
            val h = height.toFloat(); val w = width.toFloat()
            p.color = if (on) (if (Ck.isC) Ck.LIME else Ck.accent()) else 0x40FFFFFF
            cv.drawRoundRect(RectF(0f, 0f, w, h), h / 2, h / 2, p)
            p.color = Color.WHITE; val r = h / 2 - 4
            cv.drawCircle(if (on) w - h / 2 else h / 2, h / 2, r, p)
        }
    }
    /** horizontal slider 0..100 */
    @SuppressLint("ClickableViewAccessibility")
    class Slider(c: Context, var value: Int, val min: Int, val max: Int, val onChange: (Int, Boolean) -> Unit) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        init {
            setOnTouchListener { _, e ->
                val f = ((e.x - 14) / (width - 28f)).coerceIn(0f, 1f); value = Math.round(min + f * (max - min)); invalidate()
                when (e.actionMasked) { MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true); MotionEvent.ACTION_UP -> onChange(value, true); MotionEvent.ACTION_MOVE -> onChange(value, false) }
                true
            }
        }
        override fun onDraw(cv: Canvas) {
            val cy = height / 2f; val f = (value - min) / (max - min).toFloat(); val x = 14 + f * (width - 28)
            p.color = 0x33FFFFFF; cv.drawRoundRect(RectF(14f, cy - 4, width - 14f, cy + 4), 4f, 4f, p)
            p.color = if (Ck.isC) Ck.LIME else Ck.accent(); cv.drawRoundRect(RectF(14f, cy - 4, x, cy + 4), 4f, 4f, p)
            p.color = Color.WHITE; cv.drawCircle(x, cy, 13f, p)
        }
    }

    /** settings row: title (+ grey subtitle) on the left, control on the right; ≥ 64 px high */
    fun row(c: Context, title: String, sub: String?, control: View?, cw: Int = WR, ch: Int = WR, onClick: (() -> Unit)? = null): LinearLayout {
        val r = hbox(c).apply { minimumHeight = 66; setPadding(0, 6, 0, 6) }
        val col = vbox(c)
        col.addView(Ck.text(c, 22f, Color.WHITE, Ck.face(false), 2).apply { text = title })
        if (!sub.isNullOrEmpty()) col.addView(Ck.text(c, 18f, 0x99FFFFFF.toInt(), Ck.face(false), 2).apply { text = sub }, lin(WR, WR).apply { topMargin = 3 })
        r.addView(col, lin(0, WR, 1f))
        if (control != null) r.addView(control, lin(cw, ch).apply { leftMargin = 16 })
        if (onClick != null) Ck.pressable(r) { onClick() }
        return r
    }
    fun button(c: Context, label: String, w: Int = 200, h: Int = 54, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit): TextView =
        Ck.text(c, 20f, if (primary) Color.BLACK else if (danger) 0xFFFF8A80.toInt() else Color.WHITE, Ck.face(true), gravity = Gravity.CENTER).apply {
            text = label; setPadding(16, 0, 16, 0); minWidth = w
            background = when {
                primary -> Panel(if (Ck.isC) Ck.LIME else Ck.accent(), 0, 0f, floatArrayOf(h / 2f, h / 2f, h / 2f, h / 2f), false)
                danger -> Panel(0x33FF453A, 0x66FF453A, 1.2f, floatArrayOf(14f, 14f, 14f, 14f), false)
                else -> Panel(0x1FFFFFFF, 0x40FFFFFF, 1.2f, floatArrayOf(h / 2f, h / 2f, h / 2f, h / 2f), false)
            }
            Ck.pressable(this) { onClick() }
        }
    fun divider(c: Context) = View(c).apply { setBackgroundColor(0x1AFFFFFF) }

    /** round icon button (play controls etc.) */
    fun round(c: Context, icon: String, size: Int, iconSize: Int, glowRing: Boolean = false, onClick: () -> Unit): FrameLayout {
        val f = FrameLayout(c)
        f.background = if (Ck.isC) {
            if (glowRing) Panel(0x14FFFFFF, 0xBFFFFFFF.toInt(), 1.3f, floatArrayOf(size / 2f, size / 2f, size / 2f, size / 2f), false).apply { glow = Ck.accent() } else null
        } else Panel(if (glowRing) 0x29FFFFFF else 0x24FFFFFF, if (glowRing) Ck.accent() else 0x40FFFFFF, if (glowRing) 3f else 1.2f, floatArrayOf(size / 2f, size / 2f, size / 2f, size / 2f), true).apply { if (glowRing) glow = Ck.accent() }
        val iv = Ck.icon(c, icon); iv.tag = "icon"
        f.addView(iv, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
        Ck.pressable(f) { onClick() }
        f.contentDescription = icon
        return f
    }
    fun setIcon(f: View, icon: String) { (f.findViewWithTag<View>("icon") as? android.widget.ImageView)?.setImageResource(Ck.iconRes(f.context, icon)) }

    /** dim veil + centred card used by popups; tapping the veil closes */
    fun veil(c: Context, alpha: Int = 0x8C, onClose: () -> Unit): FrameLayout = FrameLayout(c).apply {
        setBackgroundColor(Color.argb(alpha, 0, 0, 0)); isClickable = true; setOnClickListener { onClose() }
    }
    /** full-size popup panel (glass, opaque enough over anything) */
    fun sheet(c: Context, r: Float = 26f): FrameLayout = FrameLayout(c).apply {
        background = if (Ck.isC) Panel(0xF20A0C10.toInt(), 0x4DDCE2EB, 1.3f, floatArrayOf(r, r, r, r), true) else Panel(0xF2241D33.toInt().let { Ck.mix(it, Ck.theme().b1, 0.5f) or 0xF0000000.toInt() }, 0x40FFFFFF, 1.3f, floatArrayOf(r, r, r, r), true)
        isClickable = true
    }
    fun header(c: Context, icon: String?, title: String, sub: String? = null, onClose: (() -> Unit)?): FrameLayout {
        val f = FrameLayout(c)
        val row = hbox(c)
        if (icon != null) row.addView(Ck.icon(c, icon, if (Ck.isC) Ck.LIME else Ck.accent()), lin(28, 28).apply { rightMargin = 12 })
        row.addView(Ck.text(c, 26f, Color.WHITE, Ck.face(true)).apply { text = title })
        if (sub != null) row.addView(Ck.text(c, 18f, 0x99FFFFFF.toInt(), Ck.face(false)).apply { text = sub }, lin(WR, WR).apply { leftMargin = 14 })
        f.addView(row, FrameLayout.LayoutParams(WR, WR, Gravity.CENTER_VERTICAL or Gravity.START))
        if (onClose != null) {
            val x = round(c, "x", 50, 22) { onClose() }
            x.background = Panel(0x1FFFFFFF, 0x33FFFFFF, 1f, floatArrayOf(25f, 25f, 25f, 25f), false)
            x.contentDescription = "Закрыть"
            f.addView(x, FrameLayout.LayoutParams(50, 50, Gravity.CENTER_VERTICAL or Gravity.END))
        }
        return f
    }
    fun FrameLayout.at(v: View, x: Number, y: Number, w: Number, h: Number) = put(v, x, y, w, h)
}

/** thin seek bar with a thumb; drag → [onSeek] (0..1) */
@SuppressLint("ClickableViewAccessibility")
class SeekCk(c: Context, val onSeek: (Float) -> Unit) : View(c) {
    var fraction = 0f; set(v) { if (!dragging) { field = v.coerceIn(0f, 1f); invalidate() } }
    private var dragging = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    init {
        setOnTouchListener { _, e ->
            val f = ((e.x - 10) / (width - 20f)).coerceIn(0f, 1f)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dragging = true; parent?.requestDisallowInterceptTouchEvent(true); fractionRaw(f) }
                MotionEvent.ACTION_MOVE -> fractionRaw(f)
                MotionEvent.ACTION_UP -> { fractionRaw(f); dragging = false; onSeek(f) }
                MotionEvent.ACTION_CANCEL -> dragging = false
            }
            true
        }
        contentDescription = "Перемотка"
    }
    private fun fractionRaw(f: Float) { fraction = f; invalidateFrac = f; invalidate() }
    private var invalidateFrac = 0f
    override fun onDraw(cv: Canvas) {
        val f = if (dragging) invalidateFrac else fraction
        val cy = height / 2f; val x = 10 + f * (width - 20)
        val a = Ck.accent()
        p.color = if (Ck.isC) 0x24FFFFFF else 0x2EFFFFFF; cv.drawRoundRect(RectF(10f, cy - 2.5f, width - 10f, cy + 2.5f), 3f, 3f, p)
        p.color = a; cv.drawRoundRect(RectF(10f, cy - 2.5f, x, cy + 2.5f), 3f, 3f, p)
        p.color = Ck.alpha(a, .35f); cv.drawCircle(x, cy, 14f, p)
        p.color = Color.WHITE; cv.drawCircle(x, cy, 10f, p)
    }
}
