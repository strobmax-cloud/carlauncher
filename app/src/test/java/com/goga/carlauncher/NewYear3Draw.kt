package com.goga.carlauncher

import android.content.Context
import android.graphics.*
import android.view.View
import android.widget.FrameLayout
import kotlin.math.*

/**
 * New Year mockup v3 (test sources only): sagging catenary garlands with glass bulbs and bloom, snow caps on the top
 * edges of panels / tiles / the lens frame, a moonlit snowdrift along the bottom (behind the controls, with soft
 * shadows under them for legibility), realistic fir branches resting on corners (with a bauble / cone / a few lights).
 */
object NY3 {
    val WARM = intArrayOf(0xFFFFC56B.toInt(), 0xFFFFE2A8.toInt(), 0xFFFFB35C.toInt(), 0xFFFFD891.toInt())
    val MULTI = intArrayOf(0xFFFF5D5D.toInt(), 0xFFFFC56B.toInt(), 0xFF5DE0A0.toInt(), 0xFF6FB8FF.toInt(), 0xFFFF8AD8.toInt(), 0xFFFFE2A8.toInt())

    class Garland(val anchors: List<PointF>, val sags: List<Float>, val spacing: Float = 30f)
    class Cap(val x0: Float, val x1: Float, val y: Float, val r: Float, val h: Float = 8f)
    /** a photo asset (assets/newyear/<asset>.webp) placed by its centre, drawn [w] px wide, rotated, optionally mirrored;
     *  behind = drawn under the page (panels / the lens frame overlap it), lights = warm bulbs woven in, cones = hanging cones */
    class Prop(val asset: String, val cx: Float, val cy: Float, val w: Float, val rot: Float = 0f, val flip: Boolean = false,
               val behind: Boolean = false, val lights: Int = 0, val cones: List<PointF> = emptyList(), val seed: Int = 1)
    class Spec(val garlands: List<Garland>, val caps: List<Cap>, val lensSnow: Boolean, val props: List<Prop>,
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

class NY3Back(c: Context, val s: NY3.Spec) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    override fun onDraw(cv: Canvas) {
        cv.save(); cv.scale(width / 1280f, height / 720f)
        drift(cv)
        for (pr in s.props) if (pr.behind) NY3Props.draw(context, cv, pr, s.multi)
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
            val y = base - 9f * NY3.noise(11, x * .35f) - 4f * NY3.noise(23, x * .8f)
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
        p.shader = LinearGradient(0f, 660f, 0f, 720f, intArrayOf(0xF2F5F8FF.toInt(), NY3.withA(tint, 0xE6), NY3.withA(NY3.lerpC(tint, 0xFF2A3350.toInt(), .45f), 0xF0)), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
        cv.drawPath(path, p); p.shader = null
        // ridge highlight
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f; p.color = 0xCCFFFFFF.toInt()
        val ridge = Path(); ridge.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) { val a = pts[i - 1]; val b = pts[i]; ridge.quadTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2) }
        cv.drawPath(ridge, p)
        // sparkles
        val r = java.util.Random(5); p.style = Paint.Style.FILL
        repeat(70) { val sx = r.nextFloat() * 1280f; val idx = ((sx + 10f) / 8f).toInt().coerceIn(0, pts.size - 1); val sy = pts[idx].y + 4 + r.nextFloat() * (716 - pts[idx].y)
            p.color = NY3.withA(Color.WHITE, 90 + r.nextInt(140)); cv.drawCircle(sx, sy, .7f + r.nextFloat() * .9f, p) }
        // shadows the controls cast on the snow → white labels / icons stay readable
        for (rc in s.shadows) {
            val cx = rc.centerX(); val cy = rc.centerY(); val rx = rc.width() * .62f; val ry = rc.height() * .75f
            p.shader = RadialGradient(cx, cy, max(rx, ry), intArrayOf(0x8C141A2E.toInt(), 0x59141A2E, 0x00141A2E), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
            cv.save(); cv.scale(rx / max(rx, ry), ry / max(rx, ry), cx, cy); cv.drawCircle(cx, cy, max(rx, ry), p); cv.restore()
        }
        p.shader = null
    }
}

class NY3Front(c: Context, val s: NY3.Spec) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val rnd = java.util.Random(3)
    override fun onDraw(cv: Canvas) {
        cv.save(); cv.scale(width / 1280f, height / 720f)
        for (cp in s.caps) cap(cv, cp)
        if (s.lensSnow) lensSnow(cv)
        for (pr in s.props) if (!pr.behind) NY3Props.draw(context, cv, pr, s.multi)
        for (g in s.garlands) garland(cv, g)
        cv.restore()
    }

    // ---------------- garland ----------------
    private fun garland(cv: Canvas, g: NY3.Garland) {
        val pal = if (s.multi) NY3.MULTI else NY3.WARM
        var k = 0
        for (i in 1 until g.anchors.size) {
            val pts = NY3.catenary(g.anchors[i - 1], g.anchors[i], g.sags[i - 1])
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
        p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.ROUND; p.color = Color.WHITE
        p.shader = RadialGradient(x, by, 24f * z, intArrayOf(NY3.withA(c, (96 * k).toInt()), NY3.withA(c, (30 * k).toInt()), NY3.withA(c, 0)), floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(x, by, 26f * z, p)
        // socket
        p.shader = LinearGradient(x - 2.6f * z, 0f, x + 2.6f * z, 0f, intArrayOf(0xFF2A2F2C.toInt(), 0xFF5A615C.toInt(), 0xFF1E2220.toInt()), null, Shader.TileMode.CLAMP)
        cv.drawRoundRect(RectF(x - 2.4f * z, y - .5f, x + 2.4f * z, y + 3.6f * z), 1.2f, 1.2f, p)
        // glass
        val lit = NY3.lerpC(c, Color.WHITE, .45f)
        p.shader = RadialGradient(x - .8f * z, by - 1.6f * z, 6.5f * z, intArrayOf(Color.WHITE, lit, c, NY3.lerpC(c, Color.BLACK, .25f)), floatArrayOf(0f, .25f, .7f, 1f), Shader.TileMode.CLAMP)
        cv.drawOval(RectF(x - 3.3f * z, y + 3f * z, x + 3.3f * z, y + 12.2f * z), p)
        p.shader = null; p.color = 0xCCFFFFFF.toInt(); cv.drawOval(RectF(x - 1.9f * z, y + 4.4f * z, x - .5f * z, y + 7.4f * z), p)
        cv.restore()
    }

    // ---------------- snow caps ----------------
    /** a natural snow cap: thick in the middle, tapering at the rounded corners, soft low-frequency mounds on top, a few
     *  small lumps hanging over the lip, blurred contact shadow, white → cool-blue shaded underside, sparse glints */
    private fun cap(cv: Canvas, c: NY3.Cap) {
        val seed = (c.x0 * 7 + c.y * 13).toInt()
        fun edgeY(x: Float): Float {
            val l = c.x0 + c.r; val rr = c.x1 - c.r
            return when { x < l -> c.y + c.r - sqrt(max(0f, c.r * c.r - (l - x) * (l - x))); x > rr -> c.y + c.r - sqrt(max(0f, c.r * c.r - (x - rr) * (x - rr))); else -> c.y }
        }
        val xa = c.x0 + c.r * .35f; val xb = c.x1 - c.r * .35f; val span = xb - xa
        val top = ArrayList<PointF>(); val bot = ArrayList<PointF>()
        var x = xa
        while (x <= xb + .1f) {
            val u = (x - xa) / span
            val ends = (min(x - xa, xb - x) / (c.r * 1.2f + 10f)).coerceIn(0f, 1f); val taper = ends * ends * (3 - 2 * ends)
            val belly = .78f + .22f * sin(PI.toFloat() * u)                                   // more snow mid-span
            val mound = NY3.noise(seed, x * .55f) * .7f + NY3.noise(seed + 9, x * 1.4f) * .3f
            val h = c.h * belly * (.55f + .7f * mound) * taper + .4f
            val e = edgeY(x)
            val lump = NY3.noise(seed + 5, x * 2.2f); val drip = if (lump > .66f) (lump - .66f) * 7f * taper else 0f
            top.add(PointF(x, e - h)); bot.add(PointF(x, e + .9f + drip))
            x += 2f
        }
        fun smooth(pts: List<PointF>, path: Path, first: Boolean) {
            if (first) path.moveTo(pts[0].x, pts[0].y) else path.lineTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) { val a = pts[i - 1]; val b = pts[i]; path.quadTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2) }
            path.lineTo(pts.last().x, pts.last().y)
        }
        val path = Path(); smooth(top, path, true); smooth(bot.reversed(), path, false); path.close()
        // blurred contact shadow on the panel below the lip
        p.style = Paint.Style.FILL; p.shader = null; p.color = 0x66050A18
        p.maskFilter = BlurMaskFilter(3.5f, BlurMaskFilter.Blur.NORMAL)
        cv.save(); cv.translate(0f, 2.6f); cv.drawPath(path, p); cv.restore(); p.maskFilter = null
        // body: bright top, cool shaded underside
        p.color = Color.WHITE
        p.shader = LinearGradient(0f, c.y - c.h * 1.3f, 0f, c.y + 2.5f, intArrayOf(0xFFFFFFFF.toInt(), 0xFFF4F8FE.toInt(), 0xFFD5DFEE.toInt(), 0xFFB3C1D8.toInt()), floatArrayOf(0f, .45f, .8f, 1f), Shader.TileMode.CLAMP)
        cv.drawPath(path, p); p.shader = null
        // soft rim light along the top surface
        val rim = Path(); smooth(top.map { PointF(it.x, it.y + .6f) }, rim, true)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = 0xB3FFFFFF.toInt(); cv.drawPath(rim, p)
        // sparse glints
        p.style = Paint.Style.FILL
        val r = java.util.Random(seed.toLong())
        repeat((span / 55).toInt()) { val i = r.nextInt(top.size); val q = top[i]
            p.color = 0xF2FFFFFF.toInt(); cv.drawCircle(q.x, q.y + 1.4f + r.nextFloat() * 1.5f, .55f + r.nextFloat() * .4f, p) }
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
            val h = (2.2f + 6.5f * NY3.noise(77, d)) * up
            if (h > .4f || started) { started = true
                outer.add(PointF(pos[0] + tan[1] * (h + 2.6f), pos[1] - tan[0] * (h + 2.6f))); inner.add(PointF(pos[0] + tan[1] * 1.4f, pos[1] - tan[0] * 1.4f)) }
            d += 3f
        }
        if (outer.size < 3) return
        val path = Path(); path.moveTo(outer[0].x, outer[0].y)
        for (i in 1 until outer.size) { val a = outer[i - 1]; val b = outer[i]; path.quadTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2) }
        for (i in inner.indices.reversed()) path.lineTo(inner[i].x, inner[i].y)
        path.close()
        p.style = Paint.Style.FILL; p.color = Color.WHITE; p.shader = LinearGradient(0f, 120f, 0f, 240f, intArrayOf(0xFFFFFFFF.toInt(), 0xFFDCE4F0.toInt()), null, Shader.TileMode.CLAMP)
        cv.drawPath(path, p); p.shader = null
    }
}

/** photo props (Pixabay Content License, see README credits): fir sprigs / branches / baubles / cones from assets/newyear */
object NY3Props {
    private val cache = HashMap<String, Bitmap>()
    fun bmp(c: Context, n: String): Bitmap = cache.getOrPut(n) { c.assets.open("newyear/$n.webp").use { BitmapFactory.decodeStream(it) } }
    /** halve with filtering until close to the target width (bilinear alone would alias thin needles) */
    fun sized(c: Context, n: String, w: Float): Bitmap = cache.getOrPut("$n@${w.toInt()}") {
        var b = bmp(c, n); while (b.width > w * 1.5f) b = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true); b }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    fun draw(c: Context, cv: Canvas, pr: NY3.Prop, multi: Boolean) {
        val b = bmp(c, pr.asset); val sc = pr.w / b.width; val h = b.height * sc
        val dst = RectF(-pr.w / 2, -h / 2, pr.w / 2, h / 2)
        // soft drop shadow (light from above, slightly left)
        val sb = sized(c, pr.asset, pr.w); val a = sb.extractAlpha()
        cv.save(); cv.translate(pr.cx + 4f, pr.cy + 9f); cv.rotate(pr.rot); if (pr.flip) cv.scale(-1f, 1f)
        p.reset(); p.flags = Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG; p.color = 0x8C02060E.toInt()
        p.maskFilter = BlurMaskFilter(7f, BlurMaskFilter.Blur.NORMAL)
        cv.drawBitmap(a, null, dst, p); p.maskFilter = null
        cv.restore()
        // the photo itself
        cv.save(); cv.translate(pr.cx, pr.cy); cv.rotate(pr.rot); if (pr.flip) cv.scale(-1f, 1f)
        p.reset(); p.flags = Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG
        cv.drawBitmap(sb, null, dst, p)
        // warm micro-lights woven through the needles on a thin dark wire
        if (pr.lights > 0) {
            val r = java.util.Random(pr.seed * 7919L); val pts = ArrayList<PointF>()
            var guard = 0
            while (pts.size < pr.lights && guard++ < 4000) {
                val px = (b.width * (.12f + .8f * r.nextFloat())).toInt(); val py = (b.height * (.1f + .8f * r.nextFloat())).toInt()
                if (Color.alpha(b.getPixel(px, py)) < 230) continue
                val q = PointF(px * sc - pr.w / 2, py * sc - h / 2)
                if (pts.any { hypot(it.x - q.x, it.y - q.y) < pr.w / (pr.lights + 1.5f) }) continue
                pts.add(q)
            }
            pts.sortBy { it.x }
            val wire = Path(); pts.firstOrNull()?.let { wire.moveTo(it.x, it.y) }
            for (i in 1 until pts.size) { val u = pts[i - 1]; val v = pts[i]; wire.quadTo((u.x + v.x) / 2, max(u.y, v.y) + 7f, v.x, v.y) }
            p.style = Paint.Style.STROKE; p.strokeWidth = .9f; p.color = 0x9E101410.toInt(); cv.drawPath(wire, p)
            val pal = if (multi) NY3.MULTI else NY3.WARM
            for ((i, q) in pts.withIndex()) {
                val col = pal[(i + pr.seed) % pal.size]; val k = .65f + .35f * r.nextFloat()
                p.style = Paint.Style.FILL; p.color = Color.WHITE
                p.shader = RadialGradient(q.x, q.y, 22f, intArrayOf(NY3.withA(col, (170 * k).toInt()), NY3.withA(col, (60 * k).toInt()), NY3.withA(col, 0)), floatArrayOf(0f, .28f, 1f), Shader.TileMode.CLAMP)
                cv.drawCircle(q.x, q.y, 22f, p)
                p.shader = RadialGradient(q.x - .5f, q.y - .6f, 3.6f, intArrayOf(Color.WHITE, NY3.lerpC(col, Color.WHITE, .45f), col), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
                cv.drawCircle(q.x, q.y, 3.4f, p); p.shader = null
            }
        }
        cv.restore()
        // cones hanging under the branch (photo cone, slight tilt)
        for ((i, q) in pr.cones.withIndex()) {
            val cb = sized(c, "ny_cone", 24f); val cw = 24f; val ch = cb.height * cw / cb.width
            val d = RectF(-cw / 2, 0f, cw / 2, ch)
            cv.save(); cv.translate(q.x + 3f, q.y + 6f); cv.rotate(if (i % 2 == 0) -9f else 7f)
            p.reset(); p.flags = Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG; p.color = 0x8002060E.toInt(); p.maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
            cv.drawBitmap(cb.extractAlpha(), null, d, p); p.maskFilter = null; cv.restore()
            cv.save(); cv.translate(q.x, q.y); cv.rotate(if (i % 2 == 0) -9f else 7f)
            p.reset(); p.flags = Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG; cv.drawBitmap(cb, null, d, p)
            // a little snow on the cone's shoulders
            p.color = 0xE6F4F8FF.toInt(); p.maskFilter = BlurMaskFilter(1.2f, BlurMaskFilter.Blur.NORMAL)
            cv.drawOval(RectF(-cw * .32f, ch * .06f, cw * .3f, ch * .16f), p); p.maskFilter = null
            cv.restore()
        }
    }
}

object NewYear3 {
    private fun pf(x: Float, y: Float) = PointF(x, y)
    private fun rr(x: Int, y: Int, w: Int, h: Int) = RectF(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat())
    fun spec(name: String): NY3.Spec {
        val multi = name == "C-music" || name == "D-music"
        val dBottom = listOf(rr(28, 640, 64, 64), rr(106, 640, 64, 64), rr(1110, 640, 64, 64), rr(1188, 640, 64, 64), rr(221, 640, 132, 64), rr(405, 650, 150, 60), rr(565, 650, 150, 60), rr(725, 650, 150, 60))
        val cBottom = listOf(rr(20, 640, 146, 62), rr(1114, 640, 146, 62), rr(166, 640, 196, 62), rr(362, 646, 556, 66))
        return when (name) {
            "C-home" -> NY3.Spec(
                garlands = listOf(NY3.Garland(listOf(pf(330f, 10f), pf(530f, 16f), pf(750f, 16f), pf(950f, 10f)), listOf(46f, 62f, 46f), 30f)),
                caps = listOf(NY3.Cap(162f, 458f, 244f, 16f, 7f), NY3.Cap(822f, 1118f, 244f, 16f, 7f), NY3.Cap(162f, 302f, 418f, 14f, 5f), NY3.Cap(318f, 458f, 418f, 14f, 5f), NY3.Cap(822f, 962f, 418f, 14f, 5f), NY3.Cap(978f, 1118f, 418f, 14f, 5f)),
                lensSnow = true,
                props = listOf(
                    NY3.Prop("ny_fir_bough", 125f, 176f, 320f, 6f, behind = true, lights = 4, seed = 1),
                    NY3.Prop("ny_fir_sprig", 1226f, 546f, 150f, -10f, flip = true, cones = listOf(pf(1204f, 566f)), seed = 2)),
                shadows = cBottom, multi = multi, driftTint = 0xFFAAB6CE.toInt())
            "C-music" -> NY3.Spec(
                garlands = listOf(NY3.Garland(listOf(pf(300f, 10f), pf(462f, 16f), pf(624f, 16f), pf(786f, 10f)), listOf(42f, 54f, 42f), 30f)),
                caps = listOf(NY3.Cap(162f, 458f, 244f, 16f, 7f), NY3.Cap(822f, 1118f, 244f, 16f, 7f), NY3.Cap(162f, 458f, 418f, 14f, 5f), NY3.Cap(822f, 1118f, 418f, 14f, 5f), NY3.Cap(232f, 1048f, 548f, 26f, 5f)),
                lensSnow = true,
                props = listOf(
                    NY3.Prop("ny_fir_cones", 1210f, 200f, 170f, -4f, behind = true, seed = 3),
                    NY3.Prop("ny_fir_sprig", 58f, 560f, 150f, -20f, lights = 3, seed = 4)),
                shadows = cBottom, multi = multi, driftTint = 0xFFAAB6CE.toInt())
            "D-home" -> NY3.Spec(
                garlands = listOf(NY3.Garland(listOf(pf(-10f, 0f), pf(226f, 2f), pf(530f, 2f), pf(752f, 2f), pf(928f, 2f), pf(1162f, 0f)), listOf(12f, 64f, 8f, 52f, 12f), 30f)),
                caps = listOf(NY3.Cap(856f, 1252f, 144f, 24f, 8f), NY3.Cap(40f, 440f, 354f, 22f, 7f),
                    NY3.Cap(104f, 306f, 462f, 22f, 6f), NY3.Cap(322f, 524f, 462f, 22f, 6f), NY3.Cap(539f, 741f, 462f, 22f, 6f), NY3.Cap(757f, 959f, 462f, 22f, 6f), NY3.Cap(974f, 1176f, 462f, 22f, 6f)),
                lensSnow = false,
                props = listOf(
                    NY3.Prop("ny_fir_baubles", 918f, 118f, 200f, -4f, seed = 5),
                    NY3.Prop("ny_fir_bough", 100f, 210f, 300f, 8f, behind = true, lights = 4, seed = 6)),
                shadows = dBottom, multi = multi)
            "D-music" -> NY3.Spec(
                garlands = listOf(NY3.Garland(listOf(pf(306f, 6f), pf(560f, 8f), pf(814f, 6f)), listOf(44f, 44f), 30f)),
                caps = listOf(NY3.Cap(100f, 466f, 105f, 10f, 7f), NY3.Cap(96f, 1184f, 490f, 33f, 7f), NY3.Cap(560f, 692f, 404f, 27f, 4f), NY3.Cap(704f, 872f, 404f, 27f, 4f), NY3.Cap(884f, 1041f, 404f, 27f, 4f)),
                lensSnow = false,
                props = listOf(
                    NY3.Prop("ny_fir_sprig", 1190f, 330f, 230f, 15f, flip = true, lights = 3, seed = 7),
                    NY3.Prop("ny_fir_baubles", 438f, 94f, 160f, 5f, seed = 8)),
                shadows = dBottom, multi = multi)
            else -> NY3.Spec(                                                                    // D-fon
                garlands = listOf(NY3.Garland(listOf(pf(-10f, 0f), pf(260f, 2f), pf(514f, 2f), pf(728f, 2f), pf(1150f, 0f)), listOf(9f, 9f, 62f, 10f), 30f)),
                caps = listOf(NY3.Cap(394f, 886f, 570f, 30f, 6f)),
                lensSnow = false,
                props = listOf(
                    NY3.Prop("ny_fir_bough", 170f, 185f, 420f, 7f, lights = 5, seed = 9),
                    NY3.Prop("ny_fir_sprig", 936f, 546f, 150f, -15f, cones = listOf(pf(968f, 578f)), seed = 10)),
                shadows = dBottom, multi = multi)
        }
    }
    fun attach(a: MainActivity, name: String) {
        val st = a.ck.root.stage; val s = spec(name)
        val idx = st.indexOfChild(a.ck.page)
        st.addView(NY3Back(a, s), idx, FrameLayout.LayoutParams(Ck.W, Ck.H))
        st.addView(NY3Front(a, s), st.indexOfChild(a.ck.page) + 1, FrameLayout.LayoutParams(Ck.W, Ck.H))
    }
}
