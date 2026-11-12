package com.goga.carlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import com.goga.carlauncher.Ui.u
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Widgets for the v1.4 "car-brand inspired" layouts. All static Canvas drawing: they redraw only on a GPS fix
 * (or once a minute for the clock gauge). Shapes are shared with the HTML simulator through the same path strings
 * (tools/simulator_template.html → CAR_TOP / CAR_SIDE / AMBIENT).
 */
object Shapes {
    /** Top view of a generic EV saloon, viewBox 200×400. */
    const val CAR_TOP_BODY = "M100 10 C150 10 172 30 176 80 L180 300 C180 360 160 390 100 390 C40 390 20 360 20 300 L24 80 C28 30 50 10 100 10 Z"
    const val CAR_TOP_GLASS = "M44 112 C70 96 130 96 156 112 L148 160 C120 152 80 152 52 160 Z M52 170 C80 162 120 162 148 170 L150 288 C120 294 80 294 50 288 Z M52 300 C80 306 120 306 148 300 L146 336 C120 346 80 346 54 336 Z"
    const val CAR_TOP_MIRRORS = "M24 128 L6 136 L8 148 L24 142 Z M176 128 L194 136 L192 148 L176 142 Z"
    const val CAR_TOP_HEAD = "M38 38 C54 26 68 22 84 21 L82 29 C68 30 54 34 42 44 Z M162 38 C146 26 132 22 116 21 L118 29 C132 30 146 34 158 44 Z"
    const val CAR_TOP_TAIL = "M30 366 C52 378 74 382 92 383 L92 377 C74 376 52 372 32 360 Z M170 366 C148 378 126 382 108 383 L108 377 C126 376 148 372 168 360 Z"
    /** Side view of a crossover-coupe (3/4-ish with highlights), viewBox 600×220. */
    const val CAR_SIDE_BODY = "M36 150 C36 122 64 110 104 104 L196 96 C238 66 286 50 352 48 C420 46 470 62 514 96 L552 104 C576 108 588 122 588 146 L588 160 C588 168 582 174 572 174 L56 174 C44 174 36 168 36 158 Z"
    const val CAR_SIDE_GLASS = "M210 100 C246 74 290 62 344 60 L344 100 Z M354 60 C408 60 446 72 478 98 L354 100 Z"
    const val CAR_SIDE_LINE = "M64 130 C200 118 400 114 576 126"
    const val CAR_SIDE_HEAD = "M546 110 L584 120 L584 130 L548 124 Z"
    const val CAR_SIDE_TAIL = "M38 126 L70 120 L70 130 L38 136 Z"
    /** MBUX-like ambient light strokes, viewBox 1168×720. */
    const val AMBIENT_1 = "M0 600 C300 540 700 690 1168 560"
    const val AMBIENT_2 = "M740 0 C800 130 960 170 1168 170"
    const val AMBIENT_3 = "M0 90 C140 70 220 30 260 0"

    private val TOKEN = Regex("[MLCZ]|-?[0-9]*\\.?[0-9]+")
    /** Tiny SVG path parser: absolute M/L/C/Z (all our shapes use that subset). */
    fun parse(d: String): Path {
        val p = Path()
        val tk = TOKEN.findAll(d).map { it.value }.toList()
        var i = 0
        var cmd = 'M'
        fun n() = tk[i++].toFloat()
        while (i < tk.size) {
            val t = tk[i]
            if (t[0].isLetter()) { cmd = t[0]; i++; if (cmd == 'Z') { p.close(); continue } }
            when (cmd) {
                'M' -> { p.moveTo(n(), n()); cmd = 'L' }
                'L' -> p.lineTo(n(), n())
                'C' -> p.cubicTo(n(), n(), n(), n(), n(), n())
                else -> i++
            }
        }
        return p
    }
}

private fun fp(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c; style = Paint.Style.FILL }
private fun sp(c: Int, w: Float = 1f, cap: Paint.Cap = Paint.Cap.ROUND) =
    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c; style = Paint.Style.STROKE; strokeWidth = w; strokeCap = cap }
private fun tp(c: Int, face: Typeface) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c; typeface = face; textAlign = Paint.Align.CENTER }

/** Ambient light strokes (European premium). Static; drawn once per layout. */
class AmbientLines(ctx: Context, private val accent: Int) : View(ctx) {
    private val paths = listOf(Shapes.parse(Shapes.AMBIENT_1), Shapes.parse(Shapes.AMBIENT_2), Shapes.parse(Shapes.AMBIENT_3))
    private val wide = sp(Color.BLACK, 16f); private val thin = sp(Color.BLACK, 2.5f)
    private var shW = -1
    override fun onDraw(c: Canvas) {
        if (shW != width) {
            shW = width
            val sh = LinearGradient(0f, 0f, 1168f, 0f, intArrayOf(0, accent, Ui.blend(accent, 0x66FFFFFF), accent, 0), null, Shader.TileMode.CLAMP)
            thin.shader = sh; wide.shader = sh
        }
        c.save(); c.scale(width / 1168f, height / 720f)
        for ((k, p) in paths.withIndex()) {
            wide.alpha = if (k == 0) 60 else 38; thin.alpha = if (k == 0) 255 else 170
            c.drawPath(p, wide); c.drawPath(p, thin)
        }
        c.restore()
    }
}

/**
 * European premium round instrument: chrome bezel, black lacquer face, sparse light numerals, accent "light" arc
 * with a soft glow, slim needle. 0–220 km/h over 240°.
 */
class LuxGauge(ctx: Context, private val accent: Int) : View(ctx), SpeedSink {
    private val max = 220
    private var value: Int? = null
    private var status = ""
    private var target = 0f; private var shown = 0f
    private val bezel = sp(Color.BLACK, 1f, Paint.Cap.BUTT); private val face = fp(Color.BLACK)
    private val ring = sp(0x1FFFFFFF, 1f, Paint.Cap.BUTT)
    private val glowP = sp(Ui.alpha(accent, 0.22f), 1f); private val arcP = sp(accent, 1f)
    private val tickA = sp(0xD9FFFFFF.toInt(), 1f, Paint.Cap.BUTT); private val tickB = sp(0x59FFFFFF, 1f, Paint.Cap.BUTT)
    private val num = tp(0xB3FFFFFF.toInt(), Ui.LIGHT)
    private val big = tp(Color.WHITE, Typeface.create("sans-serif-thin", Typeface.NORMAL))
    private val unit = tp(0x99FFFFFF.toInt(), Ui.MEDIUM)
    private val needle = sp(accent, 1f); private val tip = sp(Color.WHITE, 1f)
    private val cap = fp(Color.BLACK); private val oval = RectF()
    private var shW = -1

    override fun setSpeed(kmh: Int?, status: String) { value = kmh; this.status = status; target = (kmh ?: 0).coerceIn(0, max).toFloat(); invalidate() }

    override fun onDraw(c: Canvas) {
        if (abs(target - shown) > 0.4f) { shown += (target - shown) * 0.35f; postInvalidateOnAnimation() } else shown = target
        val cx = width / 2f; val cy = height / 2f; val r = min(width, height) / 2f - u(6)
        if (shW != width) {
            shW = width
            bezel.shader = SweepGradient(cx, cy, intArrayOf(0xFF5A5E66.toInt(), 0xFFE6E9EE.toInt(), 0xFF6B7078.toInt(), 0xFFC9CDD4.toInt(), 0xFF4A4E55.toInt(), 0xFFE6E9EE.toInt(), 0xFF5A5E66.toInt()), null)
            face.shader = RadialGradient(cx, cy - r * 0.3f, r * 1.3f, 0xFF1A1D24.toInt(), 0xFF020305.toInt(), Shader.TileMode.CLAMP)
            cap.shader = RadialGradient(cx - u(4), cy - u(4), u(18).toFloat(), 0xFFF2F4F7.toInt(), 0xFF6B7078.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawCircle(cx, cy, r, face)
        bezel.strokeWidth = u(9).toFloat(); c.drawCircle(cx, cy, r - u(4), bezel)
        ring.strokeWidth = u(1.5f).toFloat(); c.drawCircle(cx, cy, r - u(52), ring)
        val start = 150f; val sweep = 240f; val f = shown / max
        val ar = r - u(28); oval.set(cx - ar, cy - ar, cx + ar, cy + ar)
        if (f > 0.002f) {
            glowP.strokeWidth = u(20).toFloat(); c.drawArc(oval, start, sweep * f, false, glowP)
            arcP.strokeWidth = u(4).toFloat(); c.drawArc(oval, start, sweep * f, false, arcP)
        }
        tickA.strokeWidth = u(2.5f).toFloat(); tickB.strokeWidth = u(1.2f).toFloat()
        num.textSize = u(20).toFloat()
        val to = r - u(40)
        for (v in 0..max step 10) {
            val ang = Math.toRadians((start + sweep * v / max).toDouble()); val ca = cos(ang).toFloat(); val sa = sin(ang).toFloat()
            val major = v % 20 == 0; val len = u(if (major) 14 else 7)
            c.drawLine(cx + ca * to, cy + sa * to, cx + ca * (to - len), cy + sa * (to - len), if (major) tickA else tickB)
            if (v % 40 == 0) { val nr = to - u(34); c.drawText(v.toString(), cx + ca * nr, cy + sa * nr + u(7), num) }
        }
        val na = Math.toRadians((start + sweep * f).toDouble()); val ca = cos(na).toFloat(); val sa = sin(na).toFloat()
        needle.strokeWidth = u(4).toFloat(); tip.strokeWidth = u(4).toFloat()
        c.drawLine(cx - ca * u(22), cy - sa * u(22), cx + ca * (r - u(70)), cy + sa * (r - u(70)), needle)
        c.drawLine(cx + ca * (r - u(70)), cy + sa * (r - u(70)), cx + ca * (r - u(46)), cy + sa * (r - u(46)), tip)
        c.drawCircle(cx, cy, u(16).toFloat(), cap)
        big.textSize = u(78).toFloat(); big.color = if (value == null) 0x59FFFFFF else Color.WHITE
        c.drawText(value?.toString() ?: "—", cx, cy + r * 0.5f, big)
        unit.textSize = u(16).toFloat(); unit.letterSpacing = 0.3f
        c.drawText("КМ/Ч", cx, cy + r * 0.5f + u(30), unit)
    }
}

/**
 * Sport cockpit dial: segmented LED-style ring, condensed italic numerals.
 * clock=false → speed 0–240 with red zone; clock=true → time of day 0–24 h ("tach-like" second dial).
 */
class SportGauge(ctx: Context, private val accent: Int, private val clock: Boolean) : View(ctx), SpeedSink {
    private val max = if (clock) 1440 else 240
    private var value: Int? = null
    private var target = 0f; private var shown = 0f
    private var timeStr = ""; private var subStr = ""
    private val face = fp(Color.BLACK); private val seg = sp(0, 1f, Paint.Cap.BUTT)
    private val inner = sp(0x26FFFFFF, 1f, Paint.Cap.BUTT); private val outer = sp(0x40FFFFFF, 1f, Paint.Cap.BUTT)
    private val cond = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC)
    private val num = tp(0xCCFFFFFF.toInt(), cond); private val big = tp(Color.WHITE, cond); private val lab = tp(0x99FFFFFF.toInt(), cond)
    private val mark = fp(accent)
    private val oval = RectF()
    private var shW = -1

    override fun setSpeed(kmh: Int?, status: String) { if (clock) return; value = kmh; target = (kmh ?: 0).coerceIn(0, max).toFloat(); invalidate() }
    fun setTime(minutes: Int, text: String, sub: String) { target = minutes.toFloat(); shown = target; timeStr = text; subStr = sub; invalidate() }

    override fun onDraw(c: Canvas) {
        if (abs(target - shown) > 0.4f) { shown += (target - shown) * 0.35f; postInvalidateOnAnimation() } else shown = target
        val cx = width / 2f; val cy = height / 2f; val r = min(width, height) / 2f - u(4)
        if (shW != width) { shW = width; face.shader = RadialGradient(cx, cy, r, 0xFF16171B.toInt(), 0xFF050506.toInt(), Shader.TileMode.CLAMP) }
        c.drawCircle(cx, cy, r, face)
        outer.strokeWidth = u(2).toFloat(); c.drawCircle(cx, cy, r, outer)
        val start = 140f; val sweep = 260f; val n = 48; val step = sweep / n
        val ar = r - u(24); oval.set(cx - ar, cy - ar, cx + ar, cy + ar)
        seg.strokeWidth = u(22).toFloat()
        val lit = (shown / max * n)
        for (i in 0 until n) {
            val on = i < lit
            val red = !clock && i >= n * 0.8f
            seg.color = when { on && red -> 0xFFFF3B30.toInt(); on -> accent; red -> 0x40FF3B30; else -> 0xFF26272B.toInt() }
            c.drawArc(oval, start + i * step + 0.7f, step - 1.4f, false, seg)
        }
        inner.strokeWidth = u(1.5f).toFloat(); c.drawCircle(cx, cy, r - u(44), inner)
        num.textSize = u(22).toFloat()
        val labels = if (clock) listOf(0 to "0", 360 to "6", 720 to "12", 1080 to "18", 1440 to "24") else (0..240 step 40).map { it to it.toString() }
        for ((v, s) in labels) {
            val ang = Math.toRadians((start + sweep * v / max).toDouble()); val nr = r - u(66)
            c.drawText(s, cx + cos(ang).toFloat() * nr, cy + sin(ang).toFloat() * nr + u(8), num)
        }
        // pointer mark at the current value
        val pa = Math.toRadians((start + sweep * shown / max).toDouble())
        c.drawCircle(cx + cos(pa).toFloat() * (r - u(46)), cy + sin(pa).toFloat() * (r - u(46)), u(6).toFloat(), mark)
        big.textSize = u(if (clock) 74 else 104).toFloat()
        val main = if (clock) timeStr else value?.toString() ?: "—"
        big.color = if (!clock && value == null) 0x59FFFFFF else Color.WHITE
        c.drawText(main, cx, cy + u(if (clock) 22 else 32), big)
        lab.textSize = u(18).toFloat(); lab.letterSpacing = 0.25f
        c.drawText(if (clock) subStr else "КМ/Ч", cx, cy + u(if (clock) 62 else 76), lab)
        lab.color = accent; lab.textSize = u(15).toFloat()
        c.drawText(if (clock) "ВРЕМЯ" else "СКОРОСТЬ", cx, cy - u(if (clock) 62 else 82), lab)
        lab.color = 0x99FFFFFF.toInt()
    }
}

/** Chamfered "angular" card (sport cockpit): two cut corners, hairline, accent stripe on the top edge. */
class ChamferDrawable(private var fill: Int, private val stroke: Int, private val accent: Int, private val cut: Float, private var pressedFill: Int = fill) : Drawable() {
    /** Light look (LightPass): pale plate, dark hairline. */
    fun toLight() { fill = LightPass.bg(fill) ?: fill; pressedFill = LightPass.bg(pressedFill) ?: pressedFill; s.color = if (Color.alpha(s.color) > 0) 0x26000000 else 0; invalidateSelf() }
    private val p = fp(fill); private val s = sp(stroke, Ui.u(1.5f).toFloat(), Paint.Cap.BUTT); private val a = fp(accent)
    private val path = Path()
    private var pressed = false
    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        val n = state.contains(android.R.attr.state_pressed)
        if (n != pressed) { pressed = n; invalidateSelf(); return true }
        return false
    }
    override fun draw(c: Canvas) {
        val b = bounds; val w = b.width().toFloat(); val h = b.height().toFloat()
        path.reset()
        path.moveTo(b.left + cut, b.top.toFloat()); path.lineTo(b.left + w, b.top.toFloat()); path.lineTo(b.left + w, b.top + h - cut)
        path.lineTo(b.left + w - cut, b.top + h); path.lineTo(b.left.toFloat(), b.top + h); path.lineTo(b.left.toFloat(), b.top + cut); path.close()
        p.color = if (pressed) pressedFill else fill
        c.drawPath(path, p)
        if (Color.alpha(stroke) > 0) c.drawPath(path, s)
        if (Color.alpha(accent) > 0) c.drawRect(b.left + cut, b.top.toFloat(), b.left + cut + w * 0.4f, b.top + Ui.u(3).toFloat(), a)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Sport cockpit backdrop: faint accent floor glow under the dials. Static. */
class SportStripes(ctx: Context, private val accent: Int) : View(ctx) {
    private val floor = fp(Color.BLACK); private var shH = -1
    override fun onDraw(c: Canvas) {
        if (shH != height) { shH = height; floor.shader = LinearGradient(0f, height * 0.55f, 0f, height.toFloat(), 0, Ui.alpha(accent, 0.10f), Shader.TileMode.CLAMP) }
        c.drawRect(0f, height * 0.55f, width.toFloat(), height.toFloat(), floor)
    }
}
