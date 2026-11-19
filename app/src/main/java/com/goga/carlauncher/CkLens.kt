package com.goga.carlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.media.audiofx.Visualizer
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Geometry of the C «Линза» frame (mockup c5.js VC + lensPath), design px. */
object Lens {
    const val X = 640f; const val Y = 286f; const val RC = 116f; const val RT = 128f; const val RO = 152f
    const val LX = 384f; const val LY = 372f; const val R = 292f; const val YT = 150f; const val YB = 594f; const val A0 = -58f
    const val VY = 446f
    val path: Path by lazy {
        val ar = Math.toRadians(A0.toDouble())
        val xT = (LX - sqrt(R * R - (LY - YT) * (LY - YT))).toFloat()
        val px = (X + RO * sin(ar)).toFloat(); val py = (Y - RO * cos(ar)).toFloat()
        val tx = cos(ar).toFloat(); val ty = sin(ar).toFloat(); val k = 92f
        val c2x = px - k * tx; val c2y = py - k * ty
        fun m(v: Float) = 1280f - v
        val p = Path()
        p.moveTo(xT, YT); p.cubicTo(xT + 120, YT - 6, c2x, c2y, px, py)
        run { val a1 = angle(px, py); var sw = angle(m(px), py) - a1; if (sw < 0) sw += 360f; p.arcTo(RectF(X - RO, Y - RO, X + RO, Y + RO), a1, sw, false) }
        p.cubicTo(m(c2x), c2y, m(xT + 120), YT - 6, m(xT), YT)
        // right side arc (centre 1280-LX, LY) from top to bottom
        val rc = RectF(m(LX) - R, LY - R, m(LX) + R, LY + R)
        val aTop = Math.toDegrees(Math.atan2((YT - LY).toDouble(), (m(xT) - m(LX)).toDouble())).toFloat()
        val aBot = Math.toDegrees(Math.atan2((YB - LY).toDouble(), (m(xT) - m(LX)).toDouble())).toFloat()
        p.arcTo(rc, aTop, aBot - aTop, false)
        p.quadTo(640f, YB + 40, xT, YB)
        val lc = RectF(LX - R, LY - R, LX + R, LY + R)
        val bTop = Math.toDegrees(Math.atan2((YB - LY).toDouble(), (xT - LX).toDouble())).toFloat()
        val bEnd = Math.toDegrees(Math.atan2((YT - LY).toDouble(), (xT - LX).toDouble())).toFloat()
        var sw = bEnd - bTop; if (sw < 0) sw += 360f
        p.arcTo(lc, bTop, sw, false)
        p.close(); p
    }
    private fun angle(x: Float, y: Float) = Math.toDegrees(Math.atan2((y - Y).toDouble(), (x - X).toDouble())).toFloat()

    /** the silver double line of the mockup (dbl): light gradient 4.6 px + black 2.2 px over it */
    fun dbl(cv: Canvas, p: Path, paint: Paint) {
        paint.style = Paint.Style.STROKE; paint.maskFilter = null
        paint.shader = LinearGradient(0f, 130f, 0f, 640f, intArrayOf(0xFFF2F4F7.toInt(), 0xFF8A9099.toInt(), 0xFF3D4148.toInt(), 0xFFC9CED6.toInt()), floatArrayOf(0f, .35f, .6f, 1f), Shader.TileMode.CLAMP)
        paint.strokeWidth = 4.6f; cv.drawPath(p, paint)
        paint.shader = null; paint.color = Color.BLACK; paint.strokeWidth = 2.2f; cv.drawPath(p, paint)
    }
}

/**
 * C frame: lens (black or smoked glass over a photo / video), road with the night rear S-Cross, centre ring with the
 * cover / radio dial / clock face. Redrawn only when something changes (software layer for the soft glows).
 */
class LensView(c: Context) : View(c) {
    var mode = "clock"         // clock | cover | radio
    var home = true
    var photo = false
    var cover: Bitmap? = null
    var accent = 0xFFFF6A2B.toInt()
    var station = ""; var freq = ""; var freqVal = 0.0
    var stColors = intArrayOf(0xFF3A7BD5.toInt(), 0xFF7B2FF7.toInt())
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val car: Bitmap? by lazy { try { BitmapFactory.decodeResource(resources, R.drawable.ck_car_rear) } catch (_: Throwable) { null } }
    private val ring = Path().apply { addCircle(Lens.X, Lens.Y, Lens.RO, Path.Direction.CW) }

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    private fun blur(r: Float) = BlurMaskFilter(r, BlurMaskFilter.Blur.NORMAL)

    private var haloSrc: Bitmap? = null
    private var haloBmp: Bitmap? = null
    /** round, soft-edged glow from the cover colours (tiny copy, faded to the edge, then scaled up) */
    private fun halo(b: Bitmap): Bitmap {
        if (haloSrc === b && haloBmp != null) return haloBmp!!
        val n = 48
        val small = Bitmap.createScaledBitmap(b, 8, 8, true)
        val out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val q = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        c.drawBitmap(small, null, RectF(0f, 0f, n.toFloat(), n.toFloat()), q)
        q.shader = RadialGradient(n / 2f, n / 2f, n / 2f, intArrayOf(Color.BLACK, Color.BLACK, 0), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
        q.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        c.drawRect(0f, 0f, n.toFloat(), n.toFloat(), q)
        small.recycle()
        haloSrc = b; haloBmp = out
        return out
    }

    override fun onDraw(cv: Canvas) { try { drawSafe1(cv) } catch (t: Throwable) { CrashLog.soft(t) } }
    private fun drawSafe1(cv: Canvas) {
        cv.save(); cv.scale(width / 1280f, height / 720f)
        val lp = Lens.path
        p.reset(); p.isAntiAlias = true; p.isDither = true; p.isFilterBitmap = true
        p.style = Paint.Style.FILL; p.color = if (photo) 0x6B000000 else Color.BLACK
        cv.drawPath(lp, p)
        // ---- road (clipped to the lens) ----
        cv.save(); cv.clipPath(lp)
        val vy = Lens.VY; val yb = Lens.YB + 20; val a = if (photo) accent else 0xFF3EC8FF.toInt()
        p.maskFilter = blur(20f); p.color = Ck.alpha(a, .2f); cv.drawOval(RectF(470f, vy + 6 - 30, 810f, vy + 6 + 30), p); p.maskFilter = null
        val road = Path().apply { moveTo(630f, vy); lineTo(390f, yb); lineTo(890f, yb); lineTo(650f, vy); close() }
        p.shader = LinearGradient(0f, vy, 0f, yb, Ck.alpha(0xFF0D1A24.toInt(), .9f), 0, Shader.TileMode.CLAMP); cv.drawPath(road, p); p.shader = null
        p.style = Paint.Style.STROKE
        for ((w, o, sw) in listOf(Triple(250f, .75f, 1.2f), Triple(400f, .22f, 1f))) {
            p.strokeWidth = sw
            p.shader = LinearGradient(0f, vy, 0f, yb, intArrayOf(0x00FFFFFF, Ck.alpha(a, o), Ck.alpha(a, .1f * o)), floatArrayOf(0f, .3f, 1f), Shader.TileMode.CLAMP)
            cv.drawLine(630f, vy, 640 - w, yb, p); cv.drawLine(650f, vy, 640 + w, yb, p)
        }
        p.shader = null; p.maskFilter = blur(4f); p.strokeWidth = 6f; p.color = Ck.alpha(a, .18f)
        cv.drawLine(630f, vy, 390f, yb, p); cv.drawLine(650f, vy, 890f, yb, p); p.maskFilter = null
        p.style = Paint.Style.FILL
        val cb = vy + if (home) 140 else if (mode == "radio") 92 else 98
        val cw = if (home) 150f else 112f
        p.maskFilter = blur(8f); p.color = Ck.alpha(0xFFFF1F2E.toInt(), .22f)
        val rx = 110f * (if (home) .58f else .5f) / .5f; cv.drawOval(RectF(640 - rx, cb + 4 - 10, 640 + rx, cb + 14), p)
        p.maskFilter = blur(4f); p.color = Ck.alpha(Color.BLACK, .9f)
        val sr = cw * .55f; cv.drawOval(RectF(640 - sr, cb - 7, 640 + sr, cb + 7), p); p.maskFilter = null
        car?.let { b ->
            val ch = cw * b.height / b.width; val x0 = 640 - cw / 2; val y0 = cb + 2 - ch
            p.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(.78f, 0f, 0f, 0f, 0f, 0f, .86f, 0f, 0f, 0f, 0f, 0f, .80f, 0f, 4f, 0f, 0f, 0f, 1f, 0f)))
            cv.drawBitmap(b, null, RectF(x0, y0, x0 + cw, y0 + ch), p); p.colorFilter = null
            p.maskFilter = blur(8f); p.color = Ck.alpha(0xFFFF2A32.toInt(), .6f)
            for (fx in floatArrayOf(.155f, .845f)) cv.drawOval(RectF(x0 + fx * cw - cw * .13f, y0 + .36f * ch - ch * .06f, x0 + fx * cw + cw * .13f, y0 + .36f * ch + ch * .06f), p)
            p.maskFilter = null
        }
        cv.restore()
        // ---- centre ring ----
        val x = Lens.X; val y = Lens.Y; val rc = Lens.RC; val ro = Lens.RO
        if (mode == "cover" && cover != null) {
            // soft halo from the cover: tiny copy scaled up = cheap blur; v1.7.3: strength = «Сила подсветки» (K.glow)
            val g = K.glow / 100f
            p.alpha = (255 * (0.15f + 0.85f * g)).toInt().coerceIn(0, 255); p.maskFilter = null
            val hb = halo(cover!!)
            p.isFilterBitmap = true
            val hr = rc * (1.7f + 0.8f * g)
            cv.drawBitmap(hb, null, RectF(x - hr, y - hr, x + hr, y + hr), p); p.alpha = 255
        } else {
            p.maskFilter = blur(40f); p.color = Ck.alpha(if (mode == "clock") 0xFF3A6A8A.toInt() else accent, if (mode == "clock") .22f else .28f)
            cv.drawCircle(x, y, rc * 1.4f, p); p.maskFilter = null
        }
        p.color = Ck.alpha(Color.BLACK, if (photo) .35f else .55f); cv.drawCircle(x, y, ro - 2, p)
        when (mode) {
            "cover" -> {
                cv.save(); val cp = Path().apply { addCircle(x, y, rc, Path.Direction.CW) }; cv.clipPath(cp)
                cover?.let { b -> val s = maxOf(2 * rc / b.width, 2 * rc / b.height); val w = b.width * s; val h = b.height * s; cv.drawBitmap(b, null, RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2), p) }
                    ?: run { p.shader = RadialGradient(x, y - 30, rc * 1.2f, intArrayOf(Ck.alpha(accent, .55f), 0xFF111318.toInt()), null, Shader.TileMode.CLAMP); cv.drawCircle(x, y, rc, p); p.shader = null }
                p.shader = RadialGradient(x, y, rc, intArrayOf(0, 0, 0x8C000000.toInt()), floatArrayOf(0f, .62f, 1f), Shader.TileMode.CLAMP); cv.drawCircle(x, y, rc, p); p.shader = null
                cv.restore()
                p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = 0x47FFFFFF; cv.drawCircle(x, y, rc, p); p.style = Paint.Style.FILL
            }
            "radio" -> {
                p.shader = RadialGradient(x, y - rc * 0.24f, rc * 1.24f, intArrayOf(Ck.alpha(stColors[0], .45f), Ck.alpha(stColors[1], .12f), 0x33000000), floatArrayOf(0f, .7f, 1f), Shader.TileMode.CLAMP)
                cv.drawCircle(x, y, rc, p); p.shader = null
                p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = 0x38FFFFFF; cv.drawCircle(x, y, rc, p); p.style = Paint.Style.FILL
                p.textAlign = Paint.Align.CENTER
                p.typeface = Ck.juraB; p.textSize = 22f; p.color = 0xD9FFFFFF.toInt(); p.letterSpacing = 0.07f
                cv.drawText(fit(station.uppercase(), 200f), x, y - 46, p)
                p.typeface = Ck.orb; p.textSize = 54f; p.color = Color.WHITE; p.letterSpacing = 0.02f
                p.setShadowLayer(10f, 0f, 0f, accent); cv.drawText(freq, x, y + 20, p); p.clearShadowLayer()
                p.typeface = Ck.juraB; p.textSize = 19f; p.color = 0x99FFFFFF.toInt(); p.letterSpacing = 0.1f
                cv.drawText("FM · МГц", x, y + 50, p); p.letterSpacing = 0f
                p.strokeWidth = 1f
                for (k in 0..20) { val xx = x - 70 + k * 7; val maj = k % 5 == 0; p.color = if (maj) 0x80FFFFFF.toInt() else 0x40FFFFFF; cv.drawLine(xx, y + 64, xx, y + if (maj) 76 else 71, p) }
                if (freqVal > 0) { val fx = (x - 70 + (freqVal - 87.5) / 20.5 * 140).toFloat(); p.strokeWidth = 2f; p.color = accent; cv.drawLine(fx, y + 60, fx, y + 80, p) }
            }
            else -> {
                p.shader = RadialGradient(x, y - rc * .3f, rc * 1.4f, intArrayOf(Ck.alpha(0xFF2A4A66.toInt(), .45f), Ck.alpha(Color.BLACK, .1f)), null, Shader.TileMode.CLAMP)
                cv.drawCircle(x, y, rc, p); p.shader = null
            }
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = 0x38FFFFFF; cv.drawCircle(x, y, rc + 6, p)
        Lens.dbl(cv, ring, p)
        Lens.dbl(cv, lp, p)
        // the lens line passes through the ring: redraw the ring's dark inside over the frame line so the ring stays clean
        p.style = Paint.Style.FILL; p.shader = null
        cv.restore()
    }
    private fun fit(s: String, w: Float): String { var t = s; while (t.length > 3 && p.measureText(t) > w) t = t.dropLast(1); return if (t != s) t.trimEnd() + "…" else t }
}

/** Levels for the equalizers: the global output Visualizer when allowed, otherwise a calm procedural motion. */
object Eq {
    const val NB = 24
    val lev = arrayOf(FloatArray(NB), FloatArray(NB)); val pk = arrayOf(FloatArray(NB), FloatArray(NB))
    private val ph = FloatArray(NB * 2) { (it * 1.7f) % 6.28f }
    private var vis: Visualizer? = null
    private val fft = ByteArray(1024)
    private var real = false
    var playing = false

    fun open(c: Context) {
        if (vis != null || c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        try { vis = Visualizer(0).apply { captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024); enabled = true }; real = true } catch (_: Throwable) { vis = null; real = false }
    }
    fun close() { try { vis?.enabled = false; vis?.release() } catch (_: Throwable) {}; vis = null; real = false }

    fun step(dt: Float, t: Float) {
        val v = vis
        var got = false
        if (v != null && playing) try {
            if (v.getFft(fft) == Visualizer.SUCCESS) {
                got = true
                for (b in 0 until NB) {
                    val i = 2 + (b * b * 0.9f).toInt().coerceAtMost(fft.size / 2 - 2)
                    val re = fft[2 * i].toFloat(); val im = fft[2 * i + 1].toFloat()
                    val m = (Math.log10(1.0 + sqrt(re * re + im * im)) / 2.1).toFloat().coerceIn(0f, 1f)
                    for (s in 0..1) lev[s][b] = maxOf(m * (0.9f + 0.1f * s), lev[s][b] - dt * 1.6f)
                }
            }
        } catch (_: Throwable) {}
        if (!got) for (s in 0..1) for (b in 0 until NB) {
            val target = if (playing) {
                val base = 0.55f - b * 0.012f
                (base + 0.3f * sin(t * (1.3f + b * 0.11f) + ph[s * NB + b]) + 0.15f * sin(t * 3.1f + b)).coerceIn(0.05f, 1f)
            } else 0f
            lev[s][b] += (target - lev[s][b]) * (dt * 6f).coerceAtMost(1f)
        }
        for (s in 0..1) for (b in 0 until NB) pk[s][b] = if (lev[s][b] > pk[s][b]) lev[s][b] else (pk[s][b] - dt * 0.5f).coerceAtLeast(0f)
    }
}

/**
 * Animated overlay: C — segment equalizers along the lens arcs, ring ticks, playback ring, moving road lines;
 * D (music) — side level bars. ≤ 30 fps while playing on a visible screen, otherwise one still frame.
 */
class EqView(c: Context) : View(c) {
    var home = true
    var radio = false
    var accent = 0xFFFF6A2B.toInt()
    var progress = -1f
    var photo = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var last = 0L
    private var off = 0f
    private val t0 = SystemClock.uptimeMillis()
    private val segPath = Path()
    private val palRef = IntArray(34) { i ->
        val t = i / 33f
        val st = arrayOf(0f to intArrayOf(60, 255, 110), .45f to intArrayOf(200, 255, 60), .7f to intArrayOf(40, 220, 255), 1f to intArrayOf(40, 120, 255))
        var col = Color.WHITE
        for (j in 1 until st.size) if (t <= st[j].first) { val (a0, ca) = st[j - 1]; val (a1, cb) = st[j]; val u = (t - a0) / (a1 - a0); col = Color.rgb((ca[0] + (cb[0] - ca[0]) * u).toInt(), (ca[1] + (cb[1] - ca[1]) * u).toInt(), (ca[2] + (cb[2] - ca[2]) * u).toInt()); break }
        col
    }
    private val palVu = IntArray(8) { s -> val f = s / 7f; when { f < .45f -> 0xFF30D158.toInt(); f < .62f -> 0xFF30D158.toInt(); f < .8f -> 0xFFFFD60A.toInt(); f < .95f -> 0xFFFF9500.toInt(); else -> 0xFFFF3B30.toInt() } }

    fun live() = Eq.playing && isShown && windowVisibility == VISIBLE && K.waves
    fun kick() { last = 0; invalidate() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); kick() }

    override fun onDraw(cv: Canvas) { try { drawSafe2(cv) } catch (t: Throwable) { CrashLog.soft(t) } }
    private fun drawSafe2(cv: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 0.033f else ((now - last) / 1000f).coerceAtMost(0.1f)
        last = now
        Eq.step(dt, (now - t0) / 1000f)
        cv.save(); cv.scale(width / 1280f, height / 720f)
        if (Ck.isC) drawC(cv, dt) else drawD(cv)
        cv.restore()
        val anim = live() || (!Eq.playing && Eq.lev[0].any { it > 0.01f })
        if (anim && isShown) postInvalidateDelayed((33 - (SystemClock.uptimeMillis() - now)).coerceIn(8, 33))
    }

    private fun drawD(cv: Canvas) {
        if (home) return
        val n = 22; val h = 14f; val top = 120f
        for (side in 0..1) {
            p.shader = LinearGradient(if (side == 1) 1280f else 0f, 0f, if (side == 1) 1220f else 60f, 0f, accent, 0xE6FFFFFF.toInt(), Shader.TileMode.CLAMP)
            for (i in 0 until n) {
                val v = Eq.lev[side][Math.round(i / (n - 1f) * (Eq.NB - 1))]
                val w = 6 + v * 40; val yy = top + i * h; val xx = if (side == 1) 1280 - 14 - w else 14f
                p.alpha = ((.25f + .6f * v) * 255).toInt()
                cv.drawRoundRect(RectF(xx, yy, xx + w, yy + h - 6), 4f, 4f, p)
            }
        }
        p.shader = null; p.alpha = 255
    }

    private fun drawC(cv: Canvas, dt: Float) {
        val x = Lens.X; val y = Lens.Y; val ly = Lens.LY; val R = Lens.R
        val n = 34; val segs = 8; val len = 6f; val gap = 3.4f; val span = (45 * PI / 180).toFloat(); val step = 2 * span / n
        val dim = if (home) .38f else 1f
        val vu = K.eqPal == "vu"
        p.style = Paint.Style.FILL
        segPath.reset()
        val lit = ArrayList<FloatArray>()
        for (side in 0..1) {
            val cx = if (side == 1) 1280 - Lens.LX else Lens.LX
            for (i in 0 until n) {
                val ang = if (side == 1) (-span + step * (i + .5f)) else ((PI + span).toFloat() - step * (i + .5f))
                val fi = i / (n - 1f) * (Eq.NB - 1); val i0 = fi.toInt(); val i1 = minOf(Eq.NB - 1, i0 + 1); val u = fi - i0
                val v = (Eq.lev[side][i0] * (1 - u) + Eq.lev[side][i1] * u) * dim
                val pv = (Eq.pk[side][i0] * (1 - u) + Eq.pk[side][i1] * u) * dim
                val nl = Math.round(v * segs); val ps = minOf(segs - 1, Math.round(pv * segs)); val w = step * .58f
                for (s in 0 until segs) {
                    val r1 = R + 9 + s * (len + gap); val r2 = r1 + len; val ww = w * R / r1
                    if (s < nl || (s == ps && ps > 0)) lit.add(floatArrayOf(cx, ang, r1, r2, ww, (if (vu) palVu[s] else palRef[i]).toFloat(), if (s == ps && s >= nl) 1f else 0f))
                    else seg(segPath, cx, ly, ang, r1, r2, ww)
                }
            }
        }
        p.color = 0x0DFFFFFF; cv.drawPath(segPath, p)
        val one = Path()
        for (l in lit) {
            one.reset(); seg(one, l[0], ly, l[1], l[2], l[3], l[4])
            p.color = l[5].toInt(); p.alpha = ((if (home) .55f else if (l[6] > 0) .75f else 1f) * 255).toInt()
            cv.drawPath(one, p)
        }
        p.alpha = 255
        // ring ticks
        val T = 120; val tc = if (home) 0xFF8FD0FF.toInt() else accent
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        for (k in 0 until T) {
            val th = k / T.toFloat() * 2 * PI.toFloat(); val side = if (sin(th) < 0) 0 else 1; val bi = Math.round(((1 - cos(th)) / 2) * (Eq.NB - 1))
            val v = if (Eq.playing && !home) Eq.lev[side][bi] else if (home) .12f else 0f
            val maj = k % 10 == 0; val l = (if (maj) 9 else 5) + v * (if (maj) 8 else 10)
            val c = cos(th); val s = sin(th); val r1 = Lens.RT - 2; val r2 = r1 + l
            p.color = if (v > .55f || maj) tc else Color.WHITE
            p.alpha = ((if (maj) (if (home) .6f else .95f) else (.18f + .8f * v * v)) * 255).toInt()
            p.strokeWidth = if (maj) 1.8f else 1.2f
            cv.drawLine(x + r1 * s, y - r1 * c, x + r2 * s, y - r2 * c, p)
        }
        p.alpha = 255
        if (!radio && !home && progress >= 0) {
            val r = Lens.RO - 8; val o = RectF(x - r, y - r, x + r, y + r)
            p.strokeWidth = 1.5f; p.color = 0x1AFFFFFF; cv.drawCircle(x, y, r, p)
            p.color = accent; p.alpha = 64; p.strokeWidth = 6f; cv.drawArc(o, -90f, progress * 360f, false, p)
            p.alpha = 255; p.strokeWidth = 1.8f; cv.drawArc(o, -90f, progress * 360f, false, p)
        }
        // moving road lines (stand still when not playing / animation off)
        if (K.waves) off = (off + dt * (if (Eq.playing) 0.55f else 0.12f)) % 1f
        val vy = Lens.VY; val yb = Lens.YB + 20
        cv.save()
        val road = Path().apply { moveTo(630f, vy); lineTo(390f, yb); lineTo(890f, yb); lineTo(650f, vy); close() }
        cv.clipPath(road)
        val hole = if (home) RectF(558f, vy + 2, 722f, vy + 152) else RectF(578f, vy - 2, 702f, vy + 106)
        p.strokeWidth = 1f; p.color = if (photo) accent else 0xFF8FE3FF.toInt()
        for (k in 0 until 9) {
            val z = (k + 1 - off) / 1.6f; val s = 1 / (1 + z); val yy = vy + (yb - vy) * s; val hw = 10 + 240 * s
            if (yy < vy + 4) continue
            p.alpha = ((.06f + .32f * s * s) * 255).toInt()
            if (yy > hole.top && yy < hole.bottom) { cv.drawLine(640 - hw, yy, hole.left, yy, p); cv.drawLine(hole.right, yy, 640 + hw, yy, p) }
            else cv.drawLine(640 - hw, yy, 640 + hw, yy, p)
        }
        cv.restore(); p.alpha = 255; p.style = Paint.Style.FILL
    }
    private fun seg(path: Path, cx: Float, cy: Float, ang: Float, r1: Float, r2: Float, ww: Float) {
        val a0 = Math.toDegrees((ang - ww / 2).toDouble()).toFloat(); val sw = Math.toDegrees(ww.toDouble()).toFloat()
        path.arcTo(RectF(cx - r1, cy - r1, cx + r1, cy + r1), a0, sw, true)
        path.arcTo(RectF(cx - r2, cy - r2, cx + r2, cy + r2), a0 + sw, -sw, false)
        path.close()
    }
}

/**
 * D car on the podium (mockup podiumD): disc with soft shadow, rear half of the ring behind the car, the photo, contact
 * shadows under the wheels, a faint reflection, front half of the ring in front.
 */
class PodiumView(c: Context) : View(c) {
    var big = false
    var rim = 0xFFFFB08A.toInt()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val car: Bitmap? by lazy { try { BitmapFactory.decodeResource(resources, R.drawable.ck_car_front) } catch (_: Throwable) { null } }
    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }
    override fun onDraw(cv: Canvas) { try { drawSafe3(cv) } catch (t: Throwable) { CrashLog.soft(t) } }
    private fun drawSafe3(cv: Canvas) {
        val b = car ?: return
        cv.save(); cv.scale(width / 1280f, height / 720f)
        val CW = if (big) 390f else 410f; val CH = CW * b.height / b.width
        val cx = if (big) 660f else 664f; val cy = if (big) 380f else 390f; val rx = CW * .5f; val ry = CW * .125f
        val x0 = cx - .545f * CW; val y0 = cy - .85f * CH
        val disc = RectF(cx - rx, cy - ry, cx + rx, cy + ry)
        p.shader = RadialGradient(cx, cy - ry * .16f, rx * 1.2f, intArrayOf(0x8C000000.toInt(), 0x590A0A14, Ck.alpha(rim, .22f)), floatArrayOf(0f, .7f, 1f), Shader.TileMode.CLAMP)
        cv.drawOval(disc, p); p.shader = null
        // rear half of the ring (behind the car)
        p.style = Paint.Style.STROKE; p.strokeWidth = 10f; p.color = Ck.alpha(rim, .25f); p.maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
        cv.drawArc(disc, 180f, 180f, false, p); p.maskFilter = null
        p.strokeWidth = 2f; p.color = Ck.alpha(rim, .7f); cv.drawArc(disc, 180f, 180f, false, p)
        p.style = Paint.Style.FILL
        // reflection (mirrored, faded) inside the disc
        cv.save(); cv.clipPath(Path().apply { addOval(RectF(disc.left + 4, disc.top + 2, disc.right - 4, disc.bottom - 2), Path.Direction.CW) })
        val gy = y0 + .997f * CH
        cv.save(); cv.scale(1f, -1f, 0f, gy); p.alpha = 70; cv.drawBitmap(b, null, RectF(x0, y0, x0 + CW, y0 + CH), p); p.alpha = 255; cv.restore()
        p.shader = LinearGradient(0f, cy - ry * .6f, 0f, cy + ry, 0x00000000, 0xCC000000.toInt(), Shader.TileMode.CLAMP); cv.drawRect(disc, p); p.shader = null
        cv.restore()
        // shadow under the body + contact shadows
        p.color = 0x99000000.toInt(); p.maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
        val sx = x0 + .6f * CW; val sy = y0 + .88f * CH
        cv.save(); cv.rotate(-8f, sx, sy); cv.drawOval(RectF(sx - CW * .33f, sy - ry * .42f, sx + CW * .33f, sy + ry * .42f), p); cv.restore()
        p.color = 0xD9000000.toInt(); p.maskFilter = BlurMaskFilter(2f, BlurMaskFilter.Blur.NORMAL)
        val fw = floatArrayOf(x0 + .463f * CW, y0 + .997f * CH); val rw = floatArrayOf(x0 + .904f * CW, y0 + .779f * CH)
        cv.drawOval(RectF(fw[0] - 28, fw[1] - 8, fw[0] + 28, fw[1] + 6), p); cv.drawOval(RectF(rw[0] - 20, rw[1] - 6, rw[0] + 20, rw[1] + 4), p)
        p.maskFilter = null
        cv.drawBitmap(b, null, RectF(x0, y0, x0 + CW, y0 + CH), p)
        // front half of the ring
        p.style = Paint.Style.STROKE; p.strokeWidth = 10f; p.color = Ck.alpha(rim, .35f); p.maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
        cv.drawArc(disc, 0f, 180f, false, p); p.maskFilter = null
        p.strokeWidth = 2.4f; p.color = Ck.alpha(rim, .95f); cv.drawArc(disc, 0f, 180f, false, p); p.style = Paint.Style.FILL
        cv.restore()
    }
}

/** D speed gauge 0–160 km/h, 240° (mockup gaugeD). */
class GaugeD(c: Context) : View(c) {
    companion object { /** room around the dial for the ring stroke and its glow */ const val M = 24 }
    var speed = 0f
    var accent = 0xFFFFB08A.toInt()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }
    override fun onDraw(cv: Canvas) { try { drawSafe4(cv) } catch (t: Throwable) { CrashLog.soft(t) } }
    private fun drawSafe4(cv: Canvas) {
        // v1.7.9: the view is 288×288 at (106, 118) = the 240×240 dial + [M] on every side. Before, R was half the view, so
        // the 14 px ring (18 px + blur glow at speed) stuck out of the view by half its width and was cut flat on the
        // left, right and top («спидометр обрезанный по краям»).
        val k = width / (240f + 2 * M)
        val cx = width / 2f; val cy = height / 2f; val R = 120f * k
        val a0 = 150f; val sw = 240f; val max = 160f
        val o = RectF(cx - R, cy - R, cx + R, cy + R)
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = 14f; p.color = 0x1AFFFFFF; cv.drawArc(o, a0, sw, false, p)
        val f = (speed / max).coerceIn(0f, 1f)
        if (f > 0.001f) {
            p.strokeWidth = 18f; p.color = Ck.alpha(accent, .45f); p.maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL); cv.drawArc(o, a0, sw * f, false, p); p.maskFilter = null
            p.strokeWidth = 14f; p.shader = LinearGradient(cx - R, cy, cx + R, cy - R, accent, Color.WHITE, Shader.TileMode.CLAMP); cv.drawArc(o, a0, sw * f, false, p); p.shader = null
        }
        p.textAlign = Paint.Align.CENTER; p.typeface = Ck.sans
        var s = 0
        while (s <= 160) {
            val a = Math.toRadians((a0 + sw * s / max).toDouble()); val maj = s % 40 == 0
            val r1 = R - 22; val r2 = r1 - if (maj) 12 else 6
            p.style = Paint.Style.STROKE; p.color = if (maj) 0xD9FFFFFF.toInt() else 0x73FFFFFF; p.strokeWidth = if (maj) 2.2f else 1.4f
            cv.drawLine(cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(), cx + r2 * cos(a).toFloat(), cy + r2 * sin(a).toFloat(), p)
            if (maj && s % 80 == 0) {
                p.style = Paint.Style.FILL; p.textSize = 19f; p.color = 0xB3FFFFFF.toInt()
                val tr = R - 56; cv.drawText(s.toString(), cx + tr * cos(a).toFloat(), cy + tr * sin(a).toFloat() + 7, p)
            }
            s += 10
        }
    }
}
