package com.goga.carlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import com.goga.carlauncher.Ui.u
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Anything that shows GPS speed. kmh == null → no fix; status = short caption ("GPS · ±4 м"). */
interface SpeedSink { fun setSpeed(kmh: Int?, status: String) }

private fun fillPaint(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c; style = Paint.Style.FILL }
private fun strokePaint(c: Int, w: Float, cap: Paint.Cap = Paint.Cap.BUTT) =
    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c; style = Paint.Style.STROKE; strokeWidth = w; strokeCap = cap }
private fun textPaint(c: Int, size: Float, face: Typeface) =
    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c; textSize = size; typeface = face; textAlign = Paint.Align.CENTER }

/**
 * Analog speedometer (Cockpit layout): 240° dial 0–200 km/h, ticks, numbers, accent arc, needle, digital value.
 * The needle eases to the new value over a few frames only after a GPS update (no idle redraws).
 */
class SpeedGauge(ctx: Context, private val accent: Int) : View(ctx), SpeedSink {
    var max = 200
    private var target = 0f
    private var shown = 0f
    private var value: Int? = null
    private var status = ""
    private val face = fillPaint(0xB0000000.toInt())
    private val rim = strokePaint(0x33FFFFFF, 2f)
    private val track = strokePaint(0x26FFFFFF, 1f, Paint.Cap.ROUND)
    private val arc = strokePaint(accent, 1f, Paint.Cap.ROUND)
    private val tickMaj = strokePaint(Color.WHITE, 1f, Paint.Cap.ROUND)
    private val tickMin = strokePaint(0x80FFFFFF.toInt(), 1f, Paint.Cap.ROUND)
    private val num = textPaint(0xCCFFFFFF.toInt(), 1f, Ui.MEDIUM)
    private val big = textPaint(Color.WHITE, 1f, Ui.BOLD)
    private val unit = textPaint(0x99FFFFFF.toInt(), 1f, Ui.MEDIUM)
    private val cap = textPaint(0x99FFFFFF.toInt(), 1f, Ui.SANS)
    private val needle = fillPaint(accent)
    private val hub = fillPaint(0xFF101012.toInt())
    private val hubRing = strokePaint(accent, 1f)
    private val oval = RectF()
    private val path = Path()
    private var shaderW = -1

    override fun setSpeed(kmh: Int?, status: String) {
        value = kmh; this.status = status
        target = (kmh ?: 0).coerceIn(0, max).toFloat()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = min(w, h) / 2f - u(4)
        val cx = w / 2f; val cy = h / 2f
        // needle easing: ~6 frames, then stop
        if (abs(target - shown) > 0.4f) { shown += (target - shown) * 0.35f; postInvalidateOnAnimation() } else shown = target
        c.drawCircle(cx, cy, r, face)
        rim.strokeWidth = u(2).toFloat(); c.drawCircle(cx, cy, r, rim)
        val start = 150f; val sweep = 240f
        val ar = r - u(26)
        oval.set(cx - ar, cy - ar, cx + ar, cy + ar)
        track.strokeWidth = u(14).toFloat(); arc.strokeWidth = u(14).toFloat()
        c.drawArc(oval, start, sweep, false, track)
        val f = shown / max
        if (f > 0.002f) {
            if (shaderW != width) { shaderW = width; arc.shader = LinearGradient(cx - ar, cy, cx + ar, cy, Ui.alpha(accent, 0.55f), accent, Shader.TileMode.CLAMP) }
            c.drawArc(oval, start, sweep * f, false, arc)
        }
        // ticks
        tickMaj.strokeWidth = u(4).toFloat(); tickMin.strokeWidth = u(2).toFloat()
        num.textSize = u(22).toFloat()
        val tOuter = r - u(46); 
        for (v in 0..max step 10) {
            val ang = Math.toRadians((start + sweep * v / max).toDouble())
            val major = v % 20 == 0
            val len = if (major) u(18) else u(10)
            val ca = cos(ang).toFloat(); val sa = sin(ang).toFloat()
            c.drawLine(cx + ca * tOuter, cy + sa * tOuter, cx + ca * (tOuter - len), cy + sa * (tOuter - len), if (major) tickMaj else tickMin)
            if (major) {
                val nr = tOuter - u(40)
                c.drawText(v.toString(), cx + ca * nr, cy + sa * nr + u(8), num)
            }
        }
        // needle
        val na = Math.toRadians((start + sweep * f).toDouble())
        val nl = r - u(56); val nb = u(9).toFloat()
        val ca = cos(na).toFloat(); val sa = sin(na).toFloat()
        path.reset()
        path.moveTo(cx + ca * nl, cy + sa * nl)
        path.lineTo(cx - sa * nb - ca * u(20), cy + ca * nb - sa * u(20))
        path.lineTo(cx + sa * nb - ca * u(20), cy - ca * nb - sa * u(20))
        path.close()
        c.drawPath(path, needle)
        c.drawCircle(cx, cy, u(26).toFloat(), hub)
        hubRing.strokeWidth = u(4).toFloat(); c.drawCircle(cx, cy, u(26).toFloat(), hubRing)
        // digital value (below hub)
        big.textSize = u(84).toFloat(); big.color = if (value == null) 0x59FFFFFF else Color.WHITE
        c.drawText(value?.toString() ?: "—", cx, cy + r * 0.52f, big)
        unit.textSize = u(22).toFloat()
        c.drawText("км/ч", cx, cy + r * 0.52f + u(32), unit)
        cap.textSize = u(17).toFloat()
        c.drawText(status, cx, cy + r * 0.52f + u(60), cap)
    }
}

/** Ring gauge (Map / Widgets layouts): 270° ring with accent sweep, big number in the middle. */
class RingGauge(ctx: Context, private var accent: Int, private val fg: Int = Color.WHITE, private val bg: Int = 0x8C000000.toInt(),
                private val numberSize: Int = 64, private val showCaption: Boolean = true) : View(ctx), SpeedSink {
    private var value: Int? = null
    private var status = ""
    private var target = 0f; private var shown = 0f
    private val face = fillPaint(bg)
    private val track = strokePaint(Ui.alpha(fg, 0.16f), 1f, Paint.Cap.ROUND)
    private val arc = strokePaint(accent, 1f, Paint.Cap.ROUND)
    private val big = textPaint(fg, 1f, Ui.BOLD)
    private val small = textPaint(Ui.alpha(fg, 0.65f), 1f, Ui.MEDIUM)
    private val oval = RectF()

    override fun setSpeed(kmh: Int?, status: String) {
        value = kmh; this.status = status; target = (kmh ?: 0).coerceIn(0, 200).toFloat(); invalidate()
    }

    override fun onDraw(c: Canvas) {
        if (abs(target - shown) > 0.4f) { shown += (target - shown) * 0.35f; postInvalidateOnAnimation() } else shown = target
        val r = min(width, height) / 2f
        val cx = width / 2f; val cy = height / 2f
        if (Color.alpha(bg) > 0) c.drawCircle(cx, cy, r, face)
        val sw = r * 0.11f
        track.strokeWidth = sw; arc.strokeWidth = sw
        val ar = r - sw * 1.1f
        oval.set(cx - ar, cy - ar, cx + ar, cy + ar)
        c.drawArc(oval, 135f, 270f, false, track)
        if (shown > 0.5f) c.drawArc(oval, 135f, 270f * shown / 200f, false, arc)
        big.textSize = u(numberSize).toFloat(); big.color = if (value == null) Ui.alpha(fg, 0.35f) else fg
        val bigY = cy + big.textSize * 0.30f
        c.drawText(value?.toString() ?: "—", cx, bigY, big)
        small.textSize = u(numberSize * 0.3f).toFloat()
        c.drawText("км/ч", cx, bigY + small.textSize * 1.5f, small)
        if (showCaption && status.isNotEmpty()) {
            small.textSize = u(numberSize * 0.24f).toFloat()
            c.drawText(status, cx, cy + ar * 0.86f, small)
        }
    }
}

/** Compass rose from GPS bearing (degrees, null = unknown). Redrawn only on GPS updates. */
class CompassView(ctx: Context, private val accent: Int) : View(ctx), LightAware {
    private var bearing: Float? = null
    private val ring = strokePaint(0x40FFFFFF, 1f)
    private val tick = strokePaint(0x99FFFFFF.toInt(), 1f, Paint.Cap.ROUND)
    private val north = fillPaint(0xFFFF453A.toInt())
    private val south = fillPaint(0xCCFFFFFF.toInt())
    private val letter = textPaint(Color.WHITE, 1f, Ui.BOLD)
    private val big = textPaint(Color.WHITE, 1f, Ui.BOLD)
    private val small = textPaint(0x99FFFFFF.toInt(), 1f, Ui.MEDIUM)
    private val mark = fillPaint(accent)
    private val path = Path()

    fun setBearing(b: Float?) { bearing = b; invalidate() }
    override fun toLight() {
        ring.color = 0x33000000; tick.color = 0x8C000000.toInt(); south.color = 0x99000000.toInt()
        big.color = LightPass.INK; small.color = LightPass.INK2; ink = true; invalidate()
    }
    private var ink = false

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) / 2f - u(6)
        val rot = -(bearing ?: 0f)
        ring.strokeWidth = u(2).toFloat(); c.drawCircle(cx, cy, r, ring)
        // heading marker at top (fixed)
        path.reset(); path.moveTo(cx, cy - r - u(2)); path.lineTo(cx - u(9), cy - r + u(12)); path.lineTo(cx + u(9), cy - r + u(12)); path.close()
        c.drawPath(path, mark)
        c.save(); c.rotate(rot, cx, cy)
        tick.strokeWidth = u(2).toFloat()
        for (d in 0 until 360 step 30) {
            val a = Math.toRadians(d.toDouble() - 90)
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            c.drawLine(cx + ca * (r - u(16)), cy + sa * (r - u(16)), cx + ca * (r - u(6)), cy + sa * (r - u(6)), tick)
        }
        letter.textSize = u(18).toFloat()
        val lr = r - u(32)
        for ((i, s) in listOf("С", "В", "Ю", "З").withIndex()) {
            val a = Math.toRadians(i * 90.0 - 90)
            letter.color = if (i == 0) 0xFFFF453A.toInt() else if (ink) LightPass.INK else Color.WHITE
            c.drawText(s, cx + cos(a).toFloat() * lr, cy + sin(a).toFloat() * lr + u(7), letter)
        }
        c.restore()
        // centre readout
        big.textSize = u(34).toFloat()
        small.textSize = u(15).toFloat()
        val b = bearing
        if (b == null) { c.drawText("—", cx, cy + u(12), big); c.drawText("нет курса", cx, cy + u(34), small) }
        else {
            val dirs = arrayOf("С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ")
            c.drawText(dirs[(((b + 22.5f) % 360f) / 45f).toInt().coerceIn(0, 7)], cx, cy + u(8), big)
            c.drawText("${Math.round(b) % 360}°", cx, cy + u(32), small)
        }
    }
}

/** Minimal analog clock; HomeScreen calls invalidate() once a minute from its tick. */
class AnalogClockView(ctx: Context, private val accent: Int, private val fg: Int = Color.WHITE) : View(ctx), LightAware {
    private val tick = strokePaint(Ui.alpha(fg, 0.6f), 1f, Paint.Cap.ROUND)
    private val hour = strokePaint(fg, 1f, Paint.Cap.ROUND)
    private val minute = strokePaint(fg, 1f, Paint.Cap.ROUND)
    private val dot = fillPaint(accent)
    override fun toLight() { if (fg == Color.WHITE) { tick.color = 0x8C000000.toInt(); hour.color = LightPass.INK; minute.color = LightPass.INK; invalidate() } }
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) / 2f - u(8)
        tick.strokeWidth = u(3).toFloat()
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0)
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            val l = if (i % 3 == 0) u(16) else u(8)
            c.drawLine(cx + ca * r, cy + sa * r, cx + ca * (r - l), cy + sa * (r - l), tick)
        }
        val cal = java.util.Calendar.getInstance()
        val m = cal.get(java.util.Calendar.MINUTE); val hh = cal.get(java.util.Calendar.HOUR) + m / 60f
        hour.strokeWidth = u(8).toFloat(); minute.strokeWidth = u(5).toFloat()
        fun hand(deg: Float, len: Float, p: Paint) {
            val a = Math.toRadians(deg.toDouble() - 90)
            c.drawLine(cx, cy, cx + cos(a).toFloat() * len, cy + sin(a).toFloat() * len, p)
        }
        hand(hh * 30f, r * 0.52f, hour)
        hand(m * 6f, r * 0.78f, minute)
        c.drawCircle(cx, cy, u(7).toFloat(), dot)
    }
}

/** Thin progress bar (fraction 0..1). */
class BarView(ctx: Context, private val fill: Int, private val rest: Int) : View(ctx), LightAware {
    var fraction = 0f
        set(v) { val n = v.coerceIn(0f, 1f); if (n != field) { field = n; invalidate() } }
    private val pf = fillPaint(fill); private val pr = fillPaint(rest)
    override fun toLight() { pf.color = LightPass.fg(fill); pr.color = LightPass.fg(rest); invalidate() }
    private val rr = RectF()
    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val rad = h / 2
        rr.set(0f, 0f, width.toFloat(), h); c.drawRoundRect(rr, rad, rad, pr)
        if (fraction > 0f) { rr.set(0f, 0f, maxOf(h, width * fraction), h); c.drawRoundRect(rr, rad, rad, pf) }
    }
}

/**
 * Places children along a circular arc ("smile" — ends higher than the middle), like the curved favourites
 * row of Car Launcher Pro. Children keep their measured size.
 */
class ArcLayout(ctx: Context, private val sag: Int) : ViewGroup(ctx) {
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        for (i in 0 until childCount) getChildAt(i).measure(
            MeasureSpec.makeMeasureSpec(getChildAt(i).layoutParams.width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(getChildAt(i).layoutParams.height, MeasureSpec.EXACTLY))
        setMeasuredDimension(MeasureSpec.getSize(wSpec), MeasureSpec.getSize(hSpec))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val n = childCount; if (n == 0) return
        val w = r - l
        val cw = getChildAt(0).measuredWidth
        val span = (w - cw).toFloat()
        for (i in 0 until n) {
            val v = getChildAt(i)
            val f = if (n == 1) 0.5f else i.toFloat() / (n - 1)     // 0..1
            val x = (f * span).toInt()
            val d = (f - 0.5f) * 2f                                  // -1..1
            val yy = maxOf(0, -sag) + (sag * (1f - d * d)).toInt()   // sag>0: smile, sag<0: dome
            v.layout(x, yy, x + v.measuredWidth, yy + v.measuredHeight)
        }
    }
}

/**
 * Board for the iOS-style widget layout: cols×rows grid, children carry Cell(col,row,colSpan,rowSpan).
 */
class BoardLayout(ctx: Context, private val cols: Int, private val rows: Int, private val gap: Int) : ViewGroup(ctx) {
    class Cell(val c: Int, val r: Int, val cs: Int, val rs: Int) : LayoutParams(0, 0)
    private fun cw(w: Int) = (w - gap * (cols - 1)) / cols.toFloat()
    private fun ch(h: Int) = (h - gap * (rows - 1)) / rows.toFloat()
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec); val h = MeasureSpec.getSize(hSpec)
        for (i in 0 until childCount) {
            val v = getChildAt(i); val p = v.layoutParams as Cell
            val vw = (cw(w) * p.cs + gap * (p.cs - 1)).toInt(); val vh = (ch(h) * p.rs + gap * (p.rs - 1)).toInt()
            v.measure(MeasureSpec.makeMeasureSpec(vw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(vh, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(w, h)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l; val h = b - t
        for (i in 0 until childCount) {
            val v = getChildAt(i); val p = v.layoutParams as Cell
            val x = ((cw(w) + gap) * p.c).toInt(); val y = ((ch(h) + gap) * p.r).toInt()
            v.layout(x, y, x + v.measuredWidth, y + v.measuredHeight)
        }
    }
}
