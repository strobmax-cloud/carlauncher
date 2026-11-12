package com.goga.carlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.view.View
import com.goga.carlauncher.Ui.u
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * v1.6 «приборные» layouts (Приборка, Хронограф, Радиал, Витрина): shared colours, day / night, accent.
 * Rendering rule: everything decorative is painted ONCE into a bitmap per size (CachedArt); only needles / hands /
 * speed and clock text change, at most once a second.
 */
object Dash {
    /** Accent presets for the new layouts ("" = theme accent). */
    val ACCENTS = listOf("" to "Как в теме", "blue" to "Синий", "gold" to "Золото", "lime" to "Лайм", "red" to "Красный")
    fun accent(): Int = when (Prefs.dashAccent) {
        "blue" -> 0xFF2E8BFF.toInt(); "gold" -> 0xFFE0B252.toInt(); "lime" -> 0xFFB8D433.toInt(); "red" -> 0xFFFF453A.toInt()
        else -> Themes.current.accent
    }
    /** Day = light look. auto: system night mode if the firmware sets it, else 07:00–19:00. */
    fun light(c: Context): Boolean = when (Prefs.dayMode) {
        "day" -> true; "night" -> false
        else -> {
            val ui = c.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            when (ui) {
                android.content.res.Configuration.UI_MODE_NIGHT_YES -> false
                else -> Calendar.getInstance().get(Calendar.HOUR_OF_DAY) in 7..18
            }
        }
    }
    fun fg(light: Boolean) = if (light) 0xFF15171B.toInt() else Color.WHITE
    fun fg2(light: Boolean) = if (light) 0xFF5E6470.toInt() else 0xFFA3A8B2.toInt()

    /** Chrome rim (pre-rendered): dark outer edge, bright vertical chrome gradient, thin inner highlight. */
    fun chromeRim(c: Canvas, r: RectF, radius: Float, w: Float, light: Boolean) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        p.strokeWidth = w + 6; p.color = if (light) 0x40000000 else 0xFF000000.toInt(); c.drawRoundRect(r, radius, radius, p)
        p.strokeWidth = w
        p.shader = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(0xFFFFFFFF.toInt(), 0xFF9CA1AA.toInt(), 0xFFF4F6F9.toInt(), 0xFF5B6069.toInt(), 0xFFC9CDD3.toInt(), 0xFF7E838C.toInt()),
            floatArrayOf(0f, 0.18f, 0.42f, 0.62f, 0.85f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radius, radius, p)
        p.shader = null; p.strokeWidth = 1.5f; p.color = 0x80FFFFFF.toInt()
        val i = RectF(r); i.inset(w / 2 + 1, w / 2 + 1); c.drawRoundRect(i, radius - w / 2, radius - w / 2, p)
    }
    fun chromeRing(c: Canvas, cx: Float, cy: Float, r: Float, w: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        p.strokeWidth = w + 6; p.color = 0xC0000000.toInt(); c.drawCircle(cx, cy, r, p)
        p.strokeWidth = w
        p.shader = SweepGradient(cx, cy, intArrayOf(0xFF6A6F78.toInt(), 0xFFF4F6F9.toInt(), 0xFF6B7078.toInt(), 0xFFD6D9DE.toInt(), 0xFF4E535B.toInt(), 0xFFF4F6F9.toInt(), 0xFF6A6F78.toInt()), null)
        c.drawCircle(cx, cy, r, p)
        p.shader = null; p.strokeWidth = 1.2f; p.color = 0x70FFFFFF; c.drawCircle(cx, cy, r - w / 2 - 1, p)
    }

    /** Leather grain tile (192 px PNG, tiled). */
    fun leatherShader(c: Context, light: Boolean): Shader {
        val b = car(c, if (light) R.drawable.leather_light else R.drawable.leather_dark)!!
        return BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    /** Decoded car pictures (the user's 2nd-gen Suzuki SX4 S-Cross, lime metallic). Shared, decoded on demand. */
    private val cars = HashMap<Int, Bitmap>()
    fun car(c: Context, res: Int): Bitmap? = cars[res] ?: try {
        BitmapFactory.decodeResource(c.resources, res)?.also { cars[res] = it }
    } catch (e: Throwable) { null }
    fun release() { cars.values.forEach { it.recycle() }; cars.clear() }
}

/** A view whose whole picture is painted once per size into a bitmap. */
open class CachedArt(c: Context, private val paint: (Canvas, Int, Int) -> Unit) : View(c) {
    private var bmp: Bitmap? = null
    private val bp = Paint(Paint.FILTER_BITMAP_FLAG)
    override fun onDraw(cv: Canvas) {
        if (width <= 0 || height <= 0) return
        var b = bmp
        if (b == null || b.width != width || b.height != height) {
            b?.recycle()
            b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            paint(Canvas(b), width, height); bmp = b
        }
        cv.drawBitmap(b, 0f, 0f, bp)
    }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); bmp?.recycle(); bmp = null }
    /** Drop the cached picture and paint it again on the next frame (e.g. the road switched day ↔ night). */
    fun repaint() { bmp?.recycle(); bmp = null; invalidate() }
}

/**
 * Chronograph-style dial. speed = true → speedometer 0–220 (needle), false → analog clock with date window and weekday sub-dial.
 * Face, numerals, ticks and chrome bezel are cached; only the needle / hands are drawn live (≤ 1 Hz).
 */
class ChronoDial(c: Context, private val accent: Int, private val light: Boolean, private val speed: Boolean) : View(c), SpeedSink {
    private val max = 220
    private var face: Bitmap? = null
    private var value: Int? = null
    private var target = 0f; private var shown = 0f
    private val bp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val hand = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val ru = java.util.Locale("ru")
    private val days = arrayOf("ВС", "ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ")
    private val faceFg get() = if (light) 0xFF17191D.toInt() else 0xFFF2F3F5.toInt()

    override fun setSpeed(kmh: Int?, status: String) {
        if (!speed) return
        value = kmh; target = (kmh ?: 0).coerceIn(0, max).toFloat(); invalidate()
    }

    private fun paintFace(cv: Canvas, w: Int, h: Int) {
        val cx = w / 2f; val cy = h / 2f; val r = min(w, h) / 2f - u(10)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // drop shadow + face
        p.shader = RadialGradient(cx, cy + u(8), r + u(10), intArrayOf(0x90000000.toInt(), 0), floatArrayOf(0.85f, 1f), Shader.TileMode.CLAMP); cv.drawCircle(cx, cy + u(8), r + u(10), p)
        p.shader = if (light) RadialGradient(cx, cy - r * 0.4f, r * 1.4f, 0xFFFBF8F1.toInt(), 0xFFE4DDCE.toInt(), Shader.TileMode.CLAMP)
                   else RadialGradient(cx, cy - r * 0.4f, r * 1.4f, 0xFF20242C.toInt(), 0xFF030406.toInt(), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, r, p); p.shader = null
        // fine guilloché rings
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = Ui.alpha(faceFg, 0.05f)
        var rr = r * 0.2f; while (rr < r * 0.62f) { cv.drawCircle(cx, cy, rr, p); rr += u(5) }
        p.style = Paint.Style.FILL
        val fg = faceFg
        txt.color = fg
        if (speed) {
            val start = 135f; val sweep = 270f
            // red zone 180..220
            p.style = Paint.Style.STROKE; p.strokeWidth = u(8).toFloat(); p.color = 0xFFE5322D.toInt()
            val ar = r - u(22); val ov = RectF(cx - ar, cy - ar, cx + ar, cy + ar)
            cv.drawArc(ov, start + sweep * 180f / max, sweep * 40f / max, false, p)
            p.color = Ui.alpha(accent, 0.9f); p.strokeWidth = u(3).toFloat(); cv.drawArc(ov, start, sweep * 180f / max, false, p)
            p.style = Paint.Style.FILL
            for (v in 0..max step 5) {
                val ang = Math.toRadians((start + sweep * v / max).toDouble()); val ca = cos(ang).toFloat(); val sa = sin(ang).toFloat()
                val major = v % 20 == 0; val mid = v % 10 == 0
                val ro = r - u(30); val ri = ro - u(if (major) 20 else if (mid) 12 else 7)
                p.strokeWidth = u(if (major) 4 else if (mid) 2 else 1).toFloat(); p.color = if (v > 180) 0xFFE5322D.toInt() else Ui.alpha(fg, if (major) 1f else 0.6f)
                cv.drawLine(cx + ca * ro, cy + sa * ro, cx + ca * ri, cy + sa * ri, p)
                if (major) { txt.textSize = u(if (r > u(150)) 26 else 20).toFloat(); txt.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                    val nr = ro - u(46); cv.drawText(v.toString(), cx + ca * nr, cy + sa * nr + txt.textSize * 0.36f, txt) }
            }
            txt.textSize = u(15).toFloat(); txt.typeface = Ui.MEDIUM; txt.letterSpacing = 0.3f; txt.color = Ui.alpha(fg, 0.6f)
            cv.drawText("КМ/Ч", cx, cy - r * 0.32f, txt); txt.letterSpacing = 0f
            // digital window
            p.color = if (light) 0xFFEDE7DA.toInt() else 0xFF000000.toInt(); val dw = r * 0.62f; val dr = RectF(cx - dw / 2, cy + r * 0.38f, cx + dw / 2, cy + r * 0.62f)
            cv.drawRoundRect(dr, u(10).toFloat(), u(10).toFloat(), p)
            p.style = Paint.Style.STROKE; p.strokeWidth = u(1.5f).toFloat(); p.color = Ui.alpha(fg, 0.25f); cv.drawRoundRect(dr, u(10).toFloat(), u(10).toFloat(), p); p.style = Paint.Style.FILL
        } else {
            for (i in 0 until 60) {
                val ang = Math.toRadians(i * 6.0 - 90); val ca = cos(ang).toFloat(); val sa = sin(ang).toFloat()
                val hr = i % 5 == 0; val ro = r - u(26); val ri = ro - u(if (hr) 22 else 8)
                p.strokeWidth = u(if (hr) 5 else 1.5f).toFloat(); p.color = Ui.alpha(fg, if (hr) 1f else 0.55f)
                cv.drawLine(cx + ca * ro, cy + sa * ro, cx + ca * ri, cy + sa * ri, p)
            }
            txt.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD); txt.textSize = u(if (r > u(150)) 34 else 26).toFloat()
            for (hNum in intArrayOf(12, 6, 9)) {
                val ang = Math.toRadians(hNum * 30.0 - 90); val nr = r - u(72)
                cv.drawText(hNum.toString(), cx + cos(ang).toFloat() * nr, cy + sin(ang).toFloat() * nr + txt.textSize * 0.36f, txt)
            }
            // weekday sub-dial at 6 o'clock (marks only; the hand is live)
            val sx = cx; val sy = cy + r * 0.38f; val sr = r * 0.2f
            p.style = Paint.Style.STROKE; p.strokeWidth = u(1.5f).toFloat(); p.color = Ui.alpha(fg, 0.4f); cv.drawCircle(sx, sy, sr, p); p.style = Paint.Style.FILL
            txt.textSize = u(10).toFloat(); txt.typeface = Ui.BOLD; txt.color = Ui.alpha(fg, 0.75f)
            for (d in 0 until 7) { val ang = Math.toRadians(d * 360.0 / 7 - 90); val nr = sr - u(11)
                cv.drawText(days[(d + 1) % 7], sx + cos(ang).toFloat() * nr, sy + sin(ang).toFloat() * nr + u(4), txt) }
            txt.color = fg
            // date window at 3 o'clock
            p.color = if (light) 0xFFFFFFFF.toInt() else 0xFF0B0C0F.toInt()
            val dr = RectF(cx + r * 0.46f, cy - u(18), cx + r * 0.46f + u(46), cy + u(18))
            cv.drawRoundRect(dr, u(6).toFloat(), u(6).toFloat(), p)
            p.style = Paint.Style.STROKE; p.strokeWidth = u(2).toFloat(); p.color = accent; cv.drawRoundRect(dr, u(6).toFloat(), u(6).toFloat(), p); p.style = Paint.Style.FILL
        }
        Dash.chromeRing(cv, cx, cy, r - u(3), u(10).toFloat())
    }

    override fun onDraw(c: Canvas) {
        if (width <= 0) return
        var f = face
        if (f == null || f.width != width || f.height != height) { f?.recycle(); f = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); paintFace(Canvas(f), width, height); face = f }
        c.drawBitmap(f, 0f, 0f, bp)
        val cx = width / 2f; val cy = height / 2f; val r = min(width, height) / 2f - u(10)
        if (speed) {
            if (abs(target - shown) > 0.5f) { shown += (target - shown) * 0.35f; postInvalidateDelayed(40) } else shown = target   // eases for ~0.4 s after a fix
            txt.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD); txt.textSize = r * 0.2f
            txt.color = if (value == null) Ui.alpha(faceFg, 0.35f) else faceFg
            c.drawText(value?.toString() ?: "—", cx, cy + r * 0.5f + txt.textSize * 0.36f, txt)
            needle(c, cx, cy, 135f + 270f * shown / max, r - u(34), accent)
        } else {
            val cal = Calendar.getInstance()
            val m = cal.get(Calendar.MINUTE); val hh = cal.get(Calendar.HOUR) + m / 60f
            txt.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD); txt.textSize = u(22).toFloat(); txt.color = faceFg
            c.drawText(cal.get(Calendar.DAY_OF_MONTH).toString(), cx + r * 0.46f + u(23), cy + u(8), txt)
            // weekday hand
            val sy = cy + r * 0.38f; val sr = r * 0.2f
            val dow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7   // ПН = 0
            hand.color = accent; hand.strokeWidth = u(3).toFloat(); hand.strokeCap = Paint.Cap.ROUND
            val da = Math.toRadians(dow * 360.0 / 7 - 90); c.drawLine(cx, sy, cx + cos(da).toFloat() * (sr - u(18)), sy + sin(da).toFloat() * (sr - u(18)), hand)
            baton(c, cx, cy, hh * 30f - 90f, r * 0.5f, u(11).toFloat(), faceFg)
            baton(c, cx, cy, m * 6f - 90f, r * 0.76f, u(8).toFloat(), faceFg)
            hand.color = accent; c.drawCircle(cx, cy, u(9).toFloat(), hand)
        }
    }

    private fun needle(c: Canvas, cx: Float, cy: Float, deg: Float, len: Float, col: Int) {
        val a = Math.toRadians(deg.toDouble()); val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
        val nx = -sa; val ny = ca; val w0 = u(7).toFloat()
        val path = Path().apply {
            moveTo(cx - ca * u(28) + nx * w0, cy - sa * u(28) + ny * w0); lineTo(cx + ca * len, cy + sa * len)
            lineTo(cx - ca * u(28) - nx * w0, cy - sa * u(28) - ny * w0); close()
        }
        hand.style = Paint.Style.FILL; hand.color = 0x50000000; c.save(); c.translate(u(3).toFloat(), u(4).toFloat()); c.drawPath(path, hand); c.restore()
        hand.color = col; c.drawPath(path, hand)
        hand.shader = RadialGradient(cx - u(4), cy - u(4), u(22).toFloat(), 0xFFF4F6F9.toInt(), 0xFF5B6069.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, u(18).toFloat(), hand); hand.shader = null
    }
    private fun baton(c: Canvas, cx: Float, cy: Float, deg: Float, len: Float, w: Float, col: Int) {
        val a = Math.toRadians(deg.toDouble())
        hand.strokeCap = Paint.Cap.ROUND; hand.strokeWidth = w; hand.color = 0x40000000
        c.drawLine(cx + u(2), cy + u(3), cx + u(2) + cos(a).toFloat() * len, cy + u(3) + sin(a).toFloat() * len, hand)
        hand.color = col; c.drawLine(cx, cy, cx + cos(a).toFloat() * len, cy + sin(a).toFloat() * len, hand)
        hand.color = accent; hand.strokeWidth = w * 0.35f
        c.drawLine(cx + cos(a).toFloat() * len * 0.45f, cy + sin(a).toFloat() * len * 0.45f, cx + cos(a).toFloat() * len * 0.95f, cy + sin(a).toFloat() * len * 0.95f, hand)
    }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); face?.recycle(); face = null }
}

/** Radial hub (Радиал): cached ticks + track + static glow; the speed arc is the only live part (redrawn on a GPS fix). */
class RadialHub(c: Context, private val accent: Int, private val light: Boolean) : View(c), SpeedSink {
    private var face: Bitmap? = null
    private var frac = 0f
    private val bp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val ov = RectF()
    override fun setSpeed(kmh: Int?, status: String) { val f = ((kmh ?: 0) / 200f).coerceIn(0f, 1f); if (f != frac) { frac = f; invalidate() } }
    private fun paintFace(cv: Canvas, w: Int, h: Int) {
        val cx = w / 2f; val cy = h / 2f; val r = min(w, h) / 2f - u(4)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(cx, cy, r, intArrayOf(Ui.alpha(accent, if (light) 0.28f else 0.42f), Ui.alpha(accent, 0.12f), 0), floatArrayOf(0.62f, 0.8f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, r, p); p.shader = null   // static glow
        p.color = if (light) 0xFFF2F0EC.toInt() else 0xFF0C0D10.toInt(); cv.drawCircle(cx, cy, r * 0.84f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = u(14).toFloat(); p.color = if (light) 0x1A000000 else 0x1FFFFFFF
        val ar = r * 0.78f; ov.set(cx - ar, cy - ar, cx + ar, cy + ar); cv.drawArc(ov, 135f, 270f, false, p)
        val fg = Dash.fg(light)
        for (k in 0..40) {
            val ang = Math.toRadians(135.0 + 270.0 * k / 40); val ca = cos(ang).toFloat(); val sa = sin(ang).toFloat()
            val ro = r * 0.9f; val ri = ro - u(if (k % 5 == 0) 12 else 6)
            p.strokeWidth = u(if (k % 5 == 0) 3 else 1.5f).toFloat(); p.color = Ui.alpha(fg, if (k % 5 == 0) 0.85f else 0.4f)
            cv.drawLine(cx + ca * ro, cy + sa * ro, cx + ca * ri, cy + sa * ri, p)
        }
        Dash.chromeRing(cv, cx, cy, r * 0.84f, u(6).toFloat())
    }
    override fun onDraw(c: Canvas) {
        if (width <= 0) return
        var f = face
        if (f == null || f.width != width || f.height != height) { f?.recycle(); f = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); paintFace(Canvas(f), width, height); face = f }
        c.drawBitmap(f, 0f, 0f, bp)
        if (frac > 0.003f) {
            val r = min(width, height) / 2f - u(4); val ar = r * 0.78f
            ov.set(width / 2f - ar, height / 2f - ar, width / 2f + ar, height / 2f + ar)
            arc.strokeWidth = u(14).toFloat(); arc.color = accent; c.drawArc(ov, 135f, 270f * frac, false, arc)
        }
    }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); face?.recycle(); face = null }
}
