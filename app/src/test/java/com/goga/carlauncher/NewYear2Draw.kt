package com.goga.carlauncher

import android.content.Context
import android.graphics.*
import android.view.View
import android.widget.FrameLayout
import kotlin.math.*

/**
 * New Year mockup v2 (test sources only): sagging catenary garlands with glass bulbs and bloom, snow caps on the top
 * edges of panels / tiles / the lens frame, a moonlit snowdrift along the bottom (behind the controls, with soft
 * shadows under them for legibility), realistic fir branches resting on corners (with a bauble / cone / a few lights).
 */
object NY2 {
    val WARM = intArrayOf(0xFFFFC56B.toInt(), 0xFFFFE2A8.toInt(), 0xFFFFB35C.toInt(), 0xFFFFD891.toInt())
    val MULTI = intArrayOf(0xFFFF5D5D.toInt(), 0xFFFFC56B.toInt(), 0xFF5DE0A0.toInt(), 0xFF6FB8FF.toInt(), 0xFFFF8AD8.toInt(), 0xFFFFE2A8.toInt())

    class Garland(val anchors: List<PointF>, val sags: List<Float>, val spacing: Float = 30f)
    class Cap(val x0: Float, val x1: Float, val y: Float, val r: Float, val h: Float = 8f)
    class Branch(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val bend: Float, val len: Float = 15f,
                 val bauble: PointF? = null, val baubleColor: Int = 0xFFC8283C.toInt(), val cone: PointF? = null, val lights: Int = 3)
    class Spec(val garlands: List<Garland>, val caps: List<Cap>, val lensSnow: Boolean, val branches: List<Branch>,
               val shadows: List<RectF>, val multi: Boolean, val driftTint: Int = 0)

    // ---------------- helpers ----------------
    fun noise(seed: Int, x: Float): Float {
        val r = java.util.Random(seed.toLong()); val a = FloatArray(4) { r.nextFloat() * 6.28f }
        return (0.5f + 0.22f * sin(x * 0.045f + a[0]) + 0.16f * sin(x * 0.11f + a[1]) + 0.08f * sin(x * 0.27f + a[2]) + 0.04f * sin(x * 0.6f + a[3])).coerceIn(0f, 1f)
    }
    fun lerpC(a: Int, b: Int, t: Float) = Color.argb((Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(), (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())
    fun withA(c: Int, a: Int) = Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    /** catenary through A, B (same or different heights) with extra sag at the middle */
    fun catenary(a: PointF, b: PointF, sag: Float, n: Int = 48): List<PointF> {
        val w = b.x - a.x
        // solve s = k(cosh(w/2k) − 1)
        var lo = 0.01f; var hi = 100000f
        repeat(60) { val m = (lo + hi) / 2; if (m * (cosh(w / (2 * m)) - 1) > sag) lo = m else hi = m }
        val k = (lo + hi) / 2
        return (0..n).map { i -> val t = i / n.toFloat(); val x = a.x + w * t; val dx = x - (a.x + w / 2)
            PointF(x, a.y + (b.y - a.y) * t + sag - (k * (cosh(dx / k) - 1))) }
    }
}

class NY2Back(c: Context, val s: NY2.Spec) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    override fun onDraw(cv: Canvas) {
        cv.save(); cv.scale(width / 1280f, height / 720f)
        drift(cv)
        cv.restore()
    }
    private fun drift(cv: Canvas) {
        // silhouette: low in the middle (under the tabs), soft mounds towards the corners
        val path = Path(); path.moveTo(-10f, 720f)
        val pts = ArrayList<PointF>()
        var x = -10f
        while (x <= 1290f) {
            val edge = (abs(x - 640f) / 640f).coerceIn(0f, 1f)
            val base = 706f - 34f * edge.pow(1.8f)
            val y = base - 9f * NY2.noise(11, x * .35f) - 4f * NY2.noise(23, x * .8f)
            pts.add(PointF(x, y)); x += 8f
        }
        path.lineTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) { val a = pts[i - 1]; val b = pts[i]; path.quadTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2) }
        path.lineTo(1290f, 720f); path.close()
        val tint = if (s.driftTint != 0) s.driftTint else 0xFFB9C6E0.toInt()
        // soft glow above the ridge (snow lit by the screen)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 640f, 0f, 720f, intArrayOf(0x00FFFFFF, 0x26DDE6FF, 0x00FFFFFF), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 640f, 1280f, 720f, p)
        p.shader = LinearGradient(0f, 660f, 0f, 720f, intArrayOf(0xF2F5F8FF.toInt(), NY2.withA(tint, 0xE6), NY2.withA(NY2.lerpC(tint, 0xFF2A3350.toInt(), .45f), 0xF0)), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
        cv.drawPath(path, p); p.shader = null
        // ridge highlight
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f; p.color = 0xCCFFFFFF.toInt()
        val ridge = Path(); ridge.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) { val a = pts[i - 1]; val b = pts[i]; ridge.quadTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2) }
        cv.drawPath(ridge, p)
        // sparkles
        val r = java.util.Random(5); p.style = Paint.Style.FILL
        repeat(70) { val sx = r.nextFloat() * 1280f; val idx = ((sx + 10f) / 8f).toInt().coerceIn(0, pts.size - 1); val sy = pts[idx].y + 4 + r.nextFloat() * (716 - pts[idx].y)
            p.color = NY2.withA(Color.WHITE, 90 + r.nextInt(140)); cv.drawCircle(sx, sy, .7f + r.nextFloat() * .9f, p) }
        // shadows the controls cast on the snow → white labels / icons stay readable
        for (rc in s.shadows) {
            val cx = rc.centerX(); val cy = rc.centerY(); val rx = rc.width() * .62f; val ry = rc.height() * .75f
            p.shader = RadialGradient(cx, cy, max(rx, ry), intArrayOf(0x8C141A2E.toInt(), 0x59141A2E, 0x00141A2E), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
            cv.save(); cv.scale(rx / max(rx, ry), ry / max(rx, ry), cx, cy); cv.drawCircle(cx, cy, max(rx, ry), p); cv.restore()
        }
        p.shader = null
    }
}

class NY2Front(c: Context, val s: NY2.Spec) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val rnd = java.util.Random(3)
    override fun onDraw(cv: Canvas) {
        cv.save(); cv.scale(width / 1280f, height / 720f)
        for (cp in s.caps) cap(cv, cp)
        if (s.lensSnow) lensSnow(cv)
        for (b in s.branches) branch(cv, b)
        for (g in s.garlands) garland(cv, g)
        cv.restore()
    }

    // ---------------- garland ----------------
    private fun garland(cv: Canvas, g: NY2.Garland) {
        val pal = if (s.multi) NY2.MULTI else NY2.WARM
        var k = 0
        for (i in 1 until g.anchors.size) {
            val pts = NY2.catenary(g.anchors[i - 1], g.anchors[i], g.sags[i - 1])
            val wire = Path(); wire.moveTo(pts[0].x, pts[0].y); for (q in pts.drop(1)) wire.lineTo(q.x, q.y)
            p.style = Paint.Style.STROKE; p.shader = null; p.strokeCap = Paint.Cap.ROUND
            p.strokeWidth = 1.7f; p.color = 0xD9141A16.toInt(); cv.drawPath(wire, p)
            p.strokeWidth = .7f; p.color = 0x33FFFFFF; cv.save(); cv.translate(0f, -.7f); cv.drawPath(wire, p); cv.restore()
            // bulbs every spacing along the wire
            val pm = PathMeasure(wire, false); val pos = FloatArray(2); val tan = FloatArray(2)
            var d = g.spacing * .5f
            while (d < pm.length - 4) { pm.getPosTan(d, pos, tan); bulb(cv, pos[0], pos[1], tan, pal[k % pal.size], .55f + .45f * rnd.nextFloat()); k++; d += g.spacing }
        }
    }
    /** a small glass bulb hanging from the wire: dark socket, glass with a hotspot, soft bloom (= twinkle phase) */
    fun bulb(cv: Canvas, x: Float, y: Float, tan: FloatArray?, c: Int, k: Float, z: Float = 1f) {
        val ang = if (tan != null) Math.toDegrees(atan2(tan[1].toDouble(), tan[0].toDouble())).toFloat() * .35f else 0f
        cv.save(); cv.rotate(ang, x, y)
        val by = y + 7.5f * z
        // bloom
        p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.ROUND
        p.shader = RadialGradient(x, by, 26f * z, intArrayOf(NY2.withA(c, (110 * k).toInt()), NY2.withA(c, (36 * k).toInt()), NY2.withA(c, 0)), floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(x, by, 26f * z, p)
        // socket
        p.shader = LinearGradient(x - 2.6f * z, 0f, x + 2.6f * z, 0f, intArrayOf(0xFF2A2F2C.toInt(), 0xFF5A615C.toInt(), 0xFF1E2220.toInt()), null, Shader.TileMode.CLAMP)
        cv.drawRoundRect(RectF(x - 2.4f * z, y - .5f, x + 2.4f * z, y + 3.6f * z), 1.2f, 1.2f, p)
        // glass
        val lit = NY2.lerpC(c, Color.WHITE, .45f)
        p.shader = RadialGradient(x - .8f * z, by - 1.6f * z, 6.5f * z, intArrayOf(Color.WHITE, lit, c, NY2.lerpC(c, Color.BLACK, .25f)), floatArrayOf(0f, .25f, .7f, 1f), Shader.TileMode.CLAMP)
        cv.drawOval(RectF(x - 3.3f * z, y + 3f * z, x + 3.3f * z, y + 12.2f * z), p)
        p.shader = null; p.color = 0xCCFFFFFF.toInt(); cv.drawOval(RectF(x - 1.9f * z, y + 4.4f * z, x - .5f * z, y + 7.4f * z), p)
        cv.restore()
    }

    // ---------------- snow caps ----------------
    private fun cap(cv: Canvas, c: NY2.Cap) {
        val seed = (c.x0 * 7 + c.y * 13).toInt()
        fun edgeY(x: Float): Float {
            val l = c.x0 + c.r; val rr = c.x1 - c.r
            return when { x < l -> c.y + c.r - sqrt(max(0f, c.r * c.r - (l - x) * (l - x))); x > rr -> c.y + c.r - sqrt(max(0f, c.r * c.r - (x - rr) * (x - rr))); else -> c.y }
        }
        val xa = c.x0 + c.r * .25f; val xb = c.x1 - c.r * .25f
        val top = ArrayList<PointF>(); val bot = ArrayList<PointF>()
        var x = xa
        while (x <= xb) {
            val tEnd = min((x - xa) / (c.r * 1.4f + 6f), (xb - x) / (c.r * 1.4f + 6f)).coerceIn(0f, 1f)
            val taper = tEnd * tEnd * (3 - 2 * tEnd)
            val h = c.h * (0.45f + 0.75f * NY2.noise(seed, x)) * taper + 0.6f
            val e = edgeY(x)
            top.add(PointF(x, e - h)); bot.add(PointF(x, e + 1.2f + 1.6f * taper * NY2.noise(seed + 3, x * 1.7f)))
            x += 3f
        }
        val path = Path(); path.moveTo(top[0].x, top[0].y)
        for (i in 1 until top.size) { val a = top[i - 1]; val b = top[i]; path.quadTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2) }
        for (i in bot.indices.reversed()) path.lineTo(bot[i].x, bot[i].y)
        path.close()
        // contact shadow, body, rim light
        p.style = Paint.Style.FILL; p.shader = null; p.color = 0x40000814
        cv.save(); cv.translate(0f, 1.8f); cv.drawPath(path, p); cv.restore()
        p.shader = LinearGradient(0f, c.y - c.h * 1.2f, 0f, c.y + 2f, intArrayOf(0xFFFFFFFF.toInt(), 0xFFF1F5FC.toInt(), 0xFFC9D4E6.toInt()), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        cv.drawPath(path, p); p.shader = null
        // a few sparkles
        val r = java.util.Random(seed.toLong())
        repeat(((c.x1 - c.x0) / 40).toInt()) { val sx = xa + r.nextFloat() * (xb - xa); val i = ((sx - xa) / 3f).toInt().coerceIn(0, top.size - 1)
            p.color = NY2.withA(Color.WHITE, 200); cv.drawCircle(sx, top[i].y + 1.2f, .8f, p) }
    }
    /** C «Линза»: snow lying on the upper side of the silver frame line (thicker where the line is flat) */
    private fun lensSnow(cv: Canvas) {
        val pm = PathMeasure(Lens.path, false); val pos = FloatArray(2); val tan = FloatArray(2)
        val outer = ArrayList<PointF>(); val inner = ArrayList<PointF>()
        var d = 0f; var started = false
        while (d < pm.length) {
            pm.getPosTan(d, pos, tan)
            if (d > 60 && pos[0] > 1000 && pos[1] >= Lens.YT - .5f) break
            val up = tan[0].coerceAtLeast(0f).pow(1.6f)     // flat parts of the top line face up
            val h = (2.2f + 6.5f * NY2.noise(77, d)) * up
            if (h > .4f || started) { started = true
                outer.add(PointF(pos[0] + tan[1] * (h + 2.6f), pos[1] - tan[0] * (h + 2.6f))); inner.add(PointF(pos[0] + tan[1] * 1.4f, pos[1] - tan[0] * 1.4f)) }
            d += 3f
        }
        if (outer.size < 3) return
        val path = Path(); path.moveTo(outer[0].x, outer[0].y)
        for (i in 1 until outer.size) { val a = outer[i - 1]; val b = outer[i]; path.quadTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2) }
        for (i in inner.indices.reversed()) path.lineTo(inner[i].x, inner[i].y)
        path.close()
        p.style = Paint.Style.FILL; p.shader = LinearGradient(0f, 120f, 0f, 240f, intArrayOf(0xFFFFFFFF.toInt(), 0xFFDCE4F0.toInt()), null, Shader.TileMode.CLAMP)
        cv.drawPath(path, p); p.shader = null
    }

    // ---------------- fir branch ----------------
    private fun branch(cv: Canvas, b: NY2.Branch) {
        val r = java.util.Random((b.x0 * 31 + b.y0).toLong())
        val mx = (b.x0 + b.x1) / 2; val my = (b.y0 + b.y1) / 2
        val dx = b.x1 - b.x0; val dy = b.y1 - b.y0; val L = sqrt(dx * dx + dy * dy)
        val cx = mx - dy / L * b.bend; val cy = my + dx / L * b.bend
        fun at(t: Float) = PointF((1 - t) * (1 - t) * b.x0 + 2 * (1 - t) * t * cx + t * t * b.x1, (1 - t) * (1 - t) * b.y0 + 2 * (1 - t) * t * cy + t * t * b.y1)
        fun tg(t: Float): PointF { val x = 2 * (1 - t) * (cx - b.x0) + 2 * t * (b.x1 - cx); val y = 2 * (1 - t) * (cy - b.y0) + 2 * t * (b.y1 - cy); val l = sqrt(x * x + y * y); return PointF(x / l, y / l) }
        // twigs: main + side ones (start t, angle, length fraction)
        class Twig(val pts: List<PointF>)
        val twigs = ArrayList<Twig>()
        twigs.add(Twig((0..40).map { at(it / 40f) }))
        for ((t0, side, lf) in listOf(Triple(.22f, 1, .42f), Triple(.38f, -1, .46f), Triple(.55f, 1, .40f), Triple(.70f, -1, .34f), Triple(.84f, 1, .24f))) {
            val s0 = at(t0); val tt = tg(t0); val a = side * 0.62f
            val dxx = tt.x * cos(a) - tt.y * sin(a); val dyy = tt.x * sin(a) + tt.y * cos(a)
            val len = L * lf
            twigs.add(Twig((0..16).map { i -> val f = i / 16f; PointF(s0.x + dxx * len * f + tt.x * len * .25f * f * f, s0.y + dyy * len * f + tt.y * len * .25f * f * f) }))
        }
        // soft shadow under the branch
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.shader = null
        for (tw in twigs) { val path = Path(); path.moveTo(tw.pts[0].x + 3, tw.pts[0].y + 5); for (q in tw.pts) path.lineTo(q.x + 3, q.y + 5)
            p.color = 0x24000000; p.strokeWidth = b.len * 1.9f; cv.drawPath(path, p); p.color = 0x1F000000; p.strokeWidth = b.len * 1.2f; cv.drawPath(path, p) }
        // wood
        for ((i, tw) in twigs.withIndex()) { val path = Path(); path.moveTo(tw.pts[0].x, tw.pts[0].y); for (q in tw.pts) path.lineTo(q.x, q.y)
            p.color = 0xFF4A3424.toInt(); p.strokeWidth = if (i == 0) 3.2f else 1.8f; cv.drawPath(path, p) }
        // needles in three passes: back (dark), middle, front (light, frosted tips)
        val cols = intArrayOf(0xFF0E2E22.toInt(), 0xFF1F5A3C.toInt(), 0xFF3C8656.toInt())
        for (pass in 0..2) for (tw in twigs) {
            val n = tw.pts.size
            for (i in 0 until n - 1) for (rep in 0..1) {
                val f = i / (n - 1f)
                val q = tw.pts[i]; val q2 = tw.pts[i + 1]
                val tx = q2.x - q.x; val ty = q2.y - q.y; val tl = max(.001f, sqrt(tx * tx + ty * ty))
                val ux = tx / tl; val uy = ty / tl
                for (side in intArrayOf(-1, 1)) {
                    val ang = side * (0.95f - 0.25f * pass + r.nextFloat() * .25f)
                    val vx = ux * cos(ang) - uy * sin(ang); val vy = ux * sin(ang) + uy * cos(ang)
                    val len = b.len * (0.55f + 0.45f * sin(PI.toFloat() * min(1f, f * 1.15f + .08f))) * (0.8f + r.nextFloat() * .4f) * (if (tw === twigs[0]) 1f else .8f) * (1f - .18f * pass)
                    val sx = q.x + (q2.x - q.x) * rep * .5f; val sy = q.y + (q2.y - q.y) * rep * .5f
                    p.strokeWidth = 1.5f - .2f * pass
                    p.shader = LinearGradient(sx, sy, sx + vx * len, sy + vy * len, intArrayOf(cols[pass], NY2.lerpC(cols[pass], 0xFF7FB98A.toInt(), .35f + .15f * pass)), null, Shader.TileMode.CLAMP)
                    cv.drawLine(sx, sy, sx + vx * len, sy + vy * len, p)
                    if (pass == 2 && r.nextFloat() < .16f) { p.shader = null; p.color = 0xD9F4F8FF.toInt(); p.strokeWidth = 1.4f
                        cv.drawLine(sx + vx * len * .7f, sy + vy * len * .7f, sx + vx * len, sy + vy * len, p) }
                }
            }
        }
        p.shader = null
        // snow resting on the upper side of the main twig
        p.style = Paint.Style.FILL
        for (i in 6 until 36 step 3) { val q = at(i / 40f); val t = tg(i / 40f); if (t.x < .2f && t.x > -.2f) continue
            val nx = t.y * (if (t.x > 0) 1 else -1); val ny = -abs(t.x)
            val rr = 2.2f + 2.6f * NY2.noise(9, i * 10f)
            p.shader = RadialGradient(q.x + nx * 4, q.y + ny * 4 - 1, rr * 1.4f, intArrayOf(0xFFFFFFFF.toInt(), 0xEEDDE6F2.toInt(), 0x00DDE6F2), floatArrayOf(0f, .7f, 1f), Shader.TileMode.CLAMP)
            cv.drawCircle(q.x + nx * 4, q.y + ny * 4, rr * 1.4f, p) }
        p.shader = null
        // a few warm lights woven in
        val pal = if (s.multi) NY2.MULTI else NY2.WARM
        for (i in 0 until b.lights) { val q = at(.25f + .55f * i / max(1, b.lights - 1).toFloat()); val ox = (r.nextFloat() - .5f) * b.len * 1.4f; val oy = (r.nextFloat() - .5f) * b.len
            glow(cv, q.x + ox, q.y + oy, pal[i % pal.size]) }
        b.cone?.let { cone(cv, it.x, it.y) }
        b.bauble?.let { bauble(cv, it, b.baubleColor) }
    }
    private fun glow(cv: Canvas, x: Float, y: Float, c: Int) {
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(x, y, 16f, intArrayOf(NY2.withA(c, 150), NY2.withA(c, 40), NY2.withA(c, 0)), floatArrayOf(0f, .3f, 1f), Shader.TileMode.CLAMP); cv.drawCircle(x, y, 16f, p)
        p.shader = null; p.color = NY2.lerpC(c, Color.WHITE, .5f); cv.drawCircle(x, y, 2.6f, p)
    }
    private fun cone(cv: Canvas, x: Float, y: Float) {
        // pine cone hanging, scales as overlapping arcs
        cv.save(); cv.rotate(-12f, x, y)
        p.style = Paint.Style.FILL; p.shader = RadialGradient(x - 3, y + 6, 18f, intArrayOf(0xFF8A5A36.toInt(), 0xFF4A2E1C.toInt()), null, Shader.TileMode.CLAMP)
        cv.drawOval(RectF(x - 9f, y, x + 9f, y + 28f), p)
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f
        for (row in 0..5) { val yy = y + 4f + row * 4.2f; val w = 8f * sin(PI.toFloat() * (yy - y) / 28f)
            for (k in -1..1) { p.color = 0xFF2E1B10.toInt(); cv.drawArc(RectF(x + k * w * .66f - w * .38f, yy - 2.5f, x + k * w * .66f + w * .38f, yy + 2.5f), 0f, 180f, false, p)
                p.color = 0x66D9A574; cv.drawArc(RectF(x + k * w * .66f - w * .38f, yy - 3.2f, x + k * w * .66f + w * .38f, yy + 1.8f), 200f, 140f, false, p) } }
        p.color = 0xE6FFFFFF.toInt(); p.style = Paint.Style.FILL; cv.drawOval(RectF(x - 6f, y - 1f, x + 5f, y + 4f), p)   // snow on top
        cv.restore()
    }
    private fun bauble(cv: Canvas, at: PointF, color: Int) {
        val rr = 15f; val x = at.x; val y = at.y
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.shader = null; p.color = 0x99E8D7A8.toInt()
        cv.drawLine(x, y - 26f, x, y - rr - 4f, p)
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(x, y, rr * 2.6f, intArrayOf(NY2.withA(color, 60), 0), null, Shader.TileMode.CLAMP); cv.drawCircle(x, y, rr * 2.6f, p)
        p.shader = LinearGradient(x - 4.5f, 0f, x + 4.5f, 0f, intArrayOf(0xFFA77A2E.toInt(), 0xFFFFE6A6.toInt(), 0xFF9A6E28.toInt()), null, Shader.TileMode.CLAMP)
        cv.drawRoundRect(RectF(x - 4.5f, y - rr - 5f, x + 4.5f, y - rr + 2f), 1.5f, 1.5f, p)
        val hi = NY2.lerpC(color, Color.WHITE, .5f); val lo = NY2.lerpC(color, Color.BLACK, .6f)
        p.shader = RadialGradient(x - rr * .35f, y - rr * .45f, rr * 1.55f, intArrayOf(hi, color, lo), floatArrayOf(0f, .42f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(x, y, rr, p)
        // environment reflection: a window highlight + rim light
        p.shader = null; p.color = 0xCCFFFFFF.toInt()
        cv.save(); cv.rotate(-30f, x, y); cv.drawRoundRect(RectF(x - rr * .55f, y - rr * .72f, x - rr * .2f, y - rr * .28f), 3f, 3f, p); cv.restore()
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f; p.color = 0x40FFFFFF
        cv.drawArc(RectF(x - rr + 1.5f, y - rr + 1.5f, x + rr - 1.5f, y + rr - 1.5f), 20f, 110f, false, p)
        p.color = 0x8CFFE6A6.toInt(); cv.drawArc(RectF(x - rr, y - rr * .25f, x + rr, y + rr * .25f), 0f, 180f, false, p)
    }
}

object NewYear2 {
    private fun pf(x: Float, y: Float) = PointF(x, y)
    private fun rr(x: Int, y: Int, w: Int, h: Int) = RectF(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat())
    fun spec(name: String): NY2.Spec {
        val multi = name == "C-music" || name == "D-music"
        // bottom controls (shadows on the drift)
        val dBottom = listOf(rr(28, 640, 64, 64), rr(106, 640, 64, 64), rr(1110, 640, 64, 64), rr(1188, 640, 64, 64), rr(221, 640, 132, 64), rr(405, 650, 150, 60), rr(565, 650, 150, 60), rr(725, 650, 150, 60))
        val cBottom = listOf(rr(20, 640, 146, 62), rr(1114, 640, 146, 62), rr(166, 640, 196, 62), rr(362, 646, 556, 66))
        return when (name) {
            "C-home", "C-music" -> NY2.Spec(
                garlands = listOf(if (name == "C-home") NY2.Garland(listOf(pf(330f, 10f), pf(530f, 16f), pf(750f, 16f), pf(950f, 10f)), listOf(46f, 62f, 46f), 30f)
                    else NY2.Garland(listOf(pf(300f, 10f), pf(462f, 16f), pf(624f, 16f), pf(786f, 10f)), listOf(42f, 54f, 42f), 30f)),
                caps = if (name == "C-home") listOf(NY2.Cap(162f, 458f, 244f, 16f, 7f), NY2.Cap(822f, 1118f, 244f, 16f, 7f), NY2.Cap(162f, 302f, 418f, 14f, 6f), NY2.Cap(318f, 458f, 418f, 14f, 6f), NY2.Cap(822f, 962f, 418f, 14f, 6f), NY2.Cap(978f, 1118f, 418f, 14f, 6f))
                    else listOf(NY2.Cap(162f, 458f, 244f, 16f, 7f), NY2.Cap(822f, 1118f, 244f, 16f, 7f), NY2.Cap(162f, 458f, 418f, 14f, 6f), NY2.Cap(822f, 1118f, 418f, 14f, 6f), NY2.Cap(232f, 1048f, 548f, 26f, 6f)),
                lensSnow = true,
                branches = listOf(NY2.Branch(40f, 128f, 236f, 166f, -18f, 14f, bauble = pf(196f, 206f), baubleColor = 0xFFC8283C.toInt(), lights = 3),
                    NY2.Branch(1240f, 128f, 1044f, 166f, 18f, 14f, cone = pf(1084f, 176f), lights = 3)),
                shadows = cBottom, multi = multi, driftTint = 0xFFAAB6CE.toInt())
            "D-home" -> NY2.Spec(
                garlands = listOf(NY2.Garland(listOf(pf(-10f, 0f), pf(226f, 2f), pf(530f, 2f), pf(752f, 2f), pf(928f, 2f), pf(1162f, 0f)), listOf(12f, 64f, 8f, 52f, 12f), 30f)),
                caps = listOf(NY2.Cap(856f, 1252f, 144f, 24f, 9f), NY2.Cap(40f, 440f, 354f, 22f, 8f),
                    NY2.Cap(104f, 306f, 462f, 22f, 8f), NY2.Cap(322f, 524f, 462f, 22f, 8f), NY2.Cap(539f, 741f, 462f, 22f, 8f), NY2.Cap(757f, 959f, 462f, 22f, 8f), NY2.Cap(974f, 1176f, 462f, 22f, 8f)),
                lensSnow = false,
                branches = listOf(NY2.Branch(1290f, 104f, 1150f, 150f, -14f, 15f, cone = pf(1206f, 152f), lights = 3),
                    NY2.Branch(-14f, 300f, 112f, 336f, -12f, 13f, lights = 2)),
                shadows = dBottom, multi = multi)
            "D-music" -> NY2.Spec(
                garlands = listOf(NY2.Garland(listOf(pf(306f, 6f), pf(560f, 8f), pf(814f, 6f)), listOf(44f, 44f), 30f)),
                caps = listOf(NY2.Cap(100f, 466f, 105f, 10f, 8f), NY2.Cap(96f, 1184f, 490f, 33f, 8f), NY2.Cap(560f, 692f, 404f, 27f, 5f), NY2.Cap(704f, 872f, 404f, 27f, 5f), NY2.Cap(884f, 1041f, 404f, 27f, 5f)),
                lensSnow = false,
                branches = listOf(NY2.Branch(1296f, 436f, 1150f, 474f, -14f, 14f, cone = pf(1222f, 476f), lights = 3),
                    NY2.Branch(-16f, 438f, 150f, 458f, 8f, 13f, lights = 2)),
                shadows = dBottom, multi = multi)
            else -> NY2.Spec(                                                                    // D-fon
                garlands = listOf(NY2.Garland(listOf(pf(-10f, 0f), pf(260f, 2f), pf(514f, 2f), pf(728f, 2f), pf(1150f, 0f)), listOf(9f, 9f, 62f, 10f), 30f)),
                caps = listOf(NY2.Cap(394f, 886f, 570f, 30f, 7f)),
                lensSnow = false,
                branches = listOf(NY2.Branch(1296f, 500f, 1136f, 542f, -14f, 15f, cone = pf(1196f, 550f), lights = 3),
                    NY2.Branch(-16f, 512f, 150f, 540f, 10f, 14f, bauble = pf(112f, 566f), baubleColor = 0xFFC8283C.toInt(), lights = 3)),
                shadows = dBottom, multi = multi)
        }
    }
    fun attach(a: MainActivity, name: String) {
        val st = a.ck.root.stage; val s = spec(name)
        val idx = st.indexOfChild(a.ck.page)
        st.addView(NY2Back(a, s), idx, FrameLayout.LayoutParams(Ck.W, Ck.H))
        st.addView(NY2Front(a, s), st.indexOfChild(a.ck.page) + 1, FrameLayout.LayoutParams(Ck.W, Ck.H))
    }
}
