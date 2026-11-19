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
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.SystemClock
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import android.view.View
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Minimal SVG path parser for the absolute M / L / C / Q / Z paths of the mockup. */
object Svg {
    fun path(d: String): Path {
        val p = Path()
        val t = Regex("[MLCQZmlcqz]|-?[0-9]*\\.?[0-9]+(?:e-?[0-9]+)?").findAll(d).map { it.value }.toList()
        var i = 0; var cmd = 'M'
        fun n() = t[i++].toFloat()
        while (i < t.size) {
            val s = t[i]
            if (s[0].isLetter()) { cmd = s[0]; i++; if (cmd == 'Z' || cmd == 'z') { p.close(); continue } }
            when (cmd) {
                'M' -> { p.moveTo(n(), n()); cmd = 'L' }
                'L' -> p.lineTo(n(), n())
                'C' -> p.cubicTo(n(), n(), n(), n(), n(), n())
                'Q' -> p.quadTo(n(), n(), n(), n())
                else -> i++
            }
        }
        return p
    }
}

/**
 * Background of the C / D screens: theme gradient + slow waves (D), cover colour, photo scene (blur / brightness baked
 * once into a bitmap). Video and rain / snow are separate views ([VideoBg], [Particles]). Waves move ≤ 15 fps and stop
 * when the view is not shown or «Анимация волн» is off.
 */
class BgView(c: Context) : View(c) {
    var music = false
    /** v1.7.4: the «Фон» screen (its own choice: photo or video) */
    var fon = false
    private var base: Bitmap? = null
    private val waves = arrayOfNulls<Bitmap>(3)
    private var key = ""
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val m = Matrix()
    private var running = false
    private val t0 = SystemClock.uptimeMillis()
    private var lastFrame = 0L
    @Volatile private var building = ""

    init { setWillNotDraw(false) }

    /** what the background shows now: theme | cover | scene | video (video → photo under it as fallback) */
    fun mode(): String {
        val b = if (fon) K.fonKind else K.bg
        return when {
            b == "video" && Videos.chosen() == null -> "scene"
            fon && b != "video" -> "scene"
            // «Обложка» without a cover (nothing plays) → the colour gradient of the theme
            else -> b
        }
    }

    private fun wantKey(): String {
        val md = mode()
        val pal = if (md == "cover") "cover:" + Ck.coverColor else K.theme
        return when {
            md == "scene" || md == "video" -> "scene:" + Scenes.current().id + ":" + K.blur + ":" + K.bright
            // v1.7.4: the cover itself, full screen
            md == "cover" && Ck.coverArt != null -> "art:" + System.identityHashCode(Ck.coverArt) + ":" + K.blur + ":" + K.bright + ":" + K.glow
            Ck.isC -> "C:" + (if (md == "cover") Ck.coverColor.toString() + ":" + K.glow else "0") + ":" + music
            else -> "D:$pal:$music" + (if (md == "cover") ":" + K.glow else "")
        }
    }

    fun refresh() {
        val k = wantKey()
        if (k == key || k == building) { invalidate(); updateRunning(); return }
        building = k
        val w = Ck.W; val h = Ck.H
        val ctx = context.applicationContext
        val isC = Ck.isC; val md = mode(); val cover = Ck.coverColor; val mus = music; val art = Ck.coverArt
        val job = Runnable {
            val res = try { if (md == "cover" && art != null) coverBitmap(art, K.blur, K.bright, w, h) to emptyList() else render(ctx, k, isC, md, cover, mus, w, h) } catch (_: Throwable) { null }
            post {
                if (building != k) return@post
                building = ""
                if (res != null) { base?.recycle(); waves.forEachIndexed { i, b -> b?.recycle(); waves[i] = null }; base = res.first; res.second.forEachIndexed { i, b -> waves[i] = b }; key = k }
                invalidate(); updateRunning()
            }
        }
        if (Robo.sync) job.run() else AppRepo.exec.execute(job)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); refresh() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); running = false }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); updateRunning() }

    private fun animated() = K.waves && waves.any { it != null } && isShown && windowVisibility == VISIBLE
    fun updateRunning() { val r = animated(); if (r && !running) { running = true; postInvalidateOnAnimation() } else if (!r) running = false }

    override fun onDraw(cv: Canvas) { try { drawSafe1(cv) } catch (t: Throwable) { CrashLog.soft(t) } }
    private fun drawSafe1(cv: Canvas) {
        val b = base
        if (b == null) { cv.drawColor(if (Ck.isC) Color.BLACK else Ck.theme().b0); return }
        // v1.7.5: uniform «cover» scale — the layer may be wider / taller than 16:9 (it reaches the screen edges, see Stage)
        val s = maxOf(width / Ck.W.toFloat(), height / Ck.H.toFloat())
        cv.save(); cv.translate((width - Ck.W * s) / 2f, (height - Ck.H * s) / 2f); cv.scale(s, s)
        cv.drawBitmap(b, null, Rect(0, 0, Ck.W, Ck.H), paint)
        val t = (SystemClock.uptimeMillis() - t0) / 1000.0
        val anim = K.waves
        fun ph(period: Double) = if (anim) ((1 - cos(2 * PI * t / period)) / 2).toFloat() else 0f
        waves.forEachIndexed { i, wb ->
            wb ?: return@forEachIndexed
            m.reset(); m.setTranslate(-60f, -40f)
            when (i) {
                0 -> { val p = ph(26.0); m.postTranslate(-60f * p, 8f * p) }
                1 -> { val p = ph(34.0); m.postScale(1f, 1f + 0.04f * p, 640f, 360f); m.postTranslate(70f * p, -10f * p) }
                else -> { val p = ph(29.0); m.postRotate(-1.5f * p, 640f, 360f); m.postTranslate(-26f * p, -18f * p) }
            }
            cv.drawBitmap(wb, m, paint)
        }
        cv.restore()
        if (running) {
            val now = SystemClock.uptimeMillis()
            // ≤ 15 frames per second: the motion is a 26–34 s loop
            val wait = (66 - (now - lastFrame)).coerceAtLeast(0)
            lastFrame = now + wait
            postInvalidateDelayed(wait.coerceAtLeast(16))
        }
    }

    companion object {
        /** palette from the cover colour (same structure as the D themes) */
        fun coverTheme(c: Int): Ck.Theme {
            val hsv = FloatArray(3); Color.colorToHSV(c, hsv)
            // v1.7.3: «Сила подсветки» — more colour (saturation) and brighter light from the cover
            val g = K.glow / 100f; val sm = 0.6f + 0.9f * g; val vm = 0.8f + 0.5f * g
            if (hsv[1] < 0.35f) hsv[1] = 0.35f + 0.3f * g
            fun hv(s: Float, v: Float) = Color.HSVToColor(floatArrayOf(hsv[0], (hsv[1] * s * sm).coerceIn(0f, 1f), (v * vm).coerceIn(0f, 1f)))
            return Ck.Theme("cover", "Обложка", hv(0.7f, 0.13f), hv(0.75f, 0.24f), hv(0.6f, 0.07f), hv(0.7f, 0.5f), hv(0.8f, 0.72f), hv(0.6f, 0.86f), hv(0.45f, 1f), Ck.lift(c), hv(0.5f, 1f))
        }

        fun render(ctx: Context, k: String, isC: Boolean, md: String, cover: Int, music: Boolean, w: Int, h: Int): Pair<Bitmap, List<Bitmap?>> {
            if (md == "scene" || md == "video") return sceneBitmap(ctx, Scenes.current(), K.blur, K.bright, w, h) to emptyList()
            if (isC) {
                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val cv = Canvas(b)
                cv.drawColor(Color.BLACK)
                if (md == "cover" && cover != 0) {
                    val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
                    val g = K.glow / 100f
                    p.shader = RadialGradient(640f, 300f, 620f + 160f * g, intArrayOf(Ck.alpha(Ck.lift(cover), 0.2f + 0.7f * g), Ck.alpha(cover, 0.08f + 0.32f * g), 0), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                    cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
                }
                return b to emptyList()
            }
            val th = if (md == "cover" && cover != 0) coverTheme(cover) else Ck.theme()
            return themeD(th, !music, w, h)
        }

        /** v1.7.4 «Обложка»: the track cover over the WHOLE screen (centre-crop to 1280×720), blurred if «Размытие» is on, darkened for the text */
        fun coverBitmap(src0: Bitmap, blur: Boolean, bright: Int, w: Int, h: Int): Bitmap {
            val sc = maxOf(w / src0.width.toFloat(), h / src0.height.toFloat())
            val cw = w / sc; val chh = h / sc
            val sx = (src0.width - cw) / 2f; val sy = (src0.height - chh) / 2f
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val cv = Canvas(out)
            val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            val k = bright / 100f * 0.82f
            p.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(k, k, k, 1f) })
            val srcR = Rect(sx.toInt(), sy.toInt(), (sx + cw).toInt(), (sy + chh).toInt())
            if (blur) {
                val small = Bitmap.createBitmap(w / 12, h / 12, Bitmap.Config.ARGB_8888)
                Canvas(small).drawBitmap(src0, srcR, Rect(0, 0, small.width, small.height), Paint(Paint.FILTER_BITMAP_FLAG))
                val bl = boxBlur(boxBlur(small, 2), 2)
                cv.drawBitmap(bl, null, Rect(0, 0, w, h), p); small.recycle(); bl.recycle()
            } else cv.drawBitmap(src0, srcR, Rect(0, 0, w, h), p)
            val g = Paint(Paint.DITHER_FLAG)
            g.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0x80000000.toInt(), 0x26000000, 0x33000000, 0x99000000.toInt()), floatArrayOf(0f, 0.25f, 0.6f, 1f), Shader.TileMode.CLAMP)
            cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), g)
            return out
        }

        fun sceneBitmap(ctx: Context, s: Scenes.Scene, blur: Boolean, bright: Int, w: Int, h: Int): Bitmap {
            val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            var src = BitmapFactory.decodeResource(ctx.resources, s.res, o) ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            if (blur) {
                // cheap strong blur: down to 1/12, box-blur, back up with filtering (done once per change)
                val small = Bitmap.createScaledBitmap(src, w / 12, h / 12, true)
                val bl = boxBlur(boxBlur(small, 2), 2)
                src.recycle(); src = Bitmap.createScaledBitmap(bl, w, h, true); small.recycle(); bl.recycle()
            }
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val cv = Canvas(out)
            val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            val k = bright / 100f
            p.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(k, k, k, 1f) })
            cv.drawBitmap(src, null, Rect(0, 0, w, h), p); src.recycle()
            // soft dark gradient top / bottom so text stays readable
            val g = Paint(Paint.DITHER_FLAG)
            g.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0x73000000, 0x14000000, 0x1F000000, 0x8C000000.toInt()), floatArrayOf(0f, 0.25f, 0.6f, 1f), Shader.TileMode.CLAMP)
            cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), g)
            return out
        }

        fun boxBlur(b: Bitmap, r: Int): Bitmap {
            val w = b.width; val h = b.height; val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
            val tmp = IntArray(w * h)
            fun pass(src: IntArray, dst: IntArray, horiz: Boolean) {
                val n = if (horiz) w else h; val lines = if (horiz) h else w
                for (l in 0 until lines) for (i in 0 until n) {
                    var rr = 0; var gg = 0; var bb = 0; var c = 0
                    for (k in -r..r) {
                        val j = (i + k).coerceIn(0, n - 1); val p = if (horiz) src[l * w + j] else src[j * w + l]
                        rr += (p shr 16) and 255; gg += (p shr 8) and 255; bb += p and 255; c++
                    }
                    dst[if (horiz) l * w + i else i * w + l] = (0xFF shl 24) or ((rr / c) shl 16) or ((gg / c) shl 8) or (bb / c)
                }
            }
            pass(px, tmp, true); pass(tmp, px, false)
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }

        /** port of bgD() (mockup c6.js): deep gradient + mists + three silk ribbons (separate layers → slow motion) */
        fun themeD(T: Ck.Theme, home: Boolean, w: Int, h: Int): Pair<Bitmap, List<Bitmap?>> {
            val base = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val cv = Canvas(base)
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
            p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(T.b1, T.b0, T.b2), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            fun radial(cx: Float, cy: Float, r: Float, cols: IntArray, pos: FloatArray) {
                p.shader = RadialGradient(cx, cy, r, cols, pos, Shader.TileMode.CLAMP); cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            }
            val diag = Math.hypot(w.toDouble(), h.toDouble()).toFloat() / 1.41f
            radial(0.2f * w, 0.42f * h, 0.6f * diag, intArrayOf(Ck.alpha(T.mist, .7f), Ck.alpha(T.mist, .2f), Ck.alpha(T.mist, 0f)), floatArrayOf(0f, .6f, 1f))
            radial(0.05f * w, 1f * h, 0.5f * diag, intArrayOf(Ck.alpha(T.c1, .5f), Ck.alpha(T.c1, 0f)), floatArrayOf(0f, 1f))
            radial(0.95f * w, 1f * h, 0.42f * diag, intArrayOf(Ck.alpha(T.c3, .45f), Ck.alpha(T.c2, .14f), Ck.alpha(T.c2, 0f)), floatArrayOf(0f, .5f, 1f))
            // waves → 3 layers, drawn with a margin (60, 40) so the slow motion never shows an edge
            val L = arrayOfNulls<Bitmap>(3)
            val mw = w + 120; val mh = h + 80
            fun layer(i: Int, block: (Canvas) -> Unit) { val b = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888); val c = Canvas(b); c.translate(60f, 40f); block(c); L[i] = b }
            val sp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
            val fp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { style = Paint.Style.FILL }
            fun stroke(c: Canvas, d: Path, color: Int, width: Float, op: Float, blur: Float = 0f, shader: Shader? = null) {
                sp.shader = shader; sp.color = if (shader != null) Ck.alpha(Color.WHITE, op) else Ck.alpha(color, op); sp.strokeWidth = width
                sp.maskFilter = if (blur > 0) BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) else null
                c.drawPath(d, sp); sp.maskFilter = null; sp.shader = null
            }
            fun strands(c: Canvas, d: String, n: Int, dy: Float, color: Int, op: Float, sw: Float = 1f, dx: Float = 0f) {
                val path = Svg.path(d)
                for (k in 0 until n) { c.save(); c.translate(k * dx, k * dy); stroke(c, path, color, sw, op * (1 - k / n.toFloat())); c.restore() }
            }
            fun rimShader() = LinearGradient(-140f, 0f, 1420f, 0f, intArrayOf(Ck.alpha(T.c2, .2f), T.c3, T.c2, Ck.alpha(T.c3, .9f)), floatArrayOf(0f, .3f, .7f, 1f), Shader.TileMode.CLAMP)
            val b1 = "M-140 560 C80 500 300 560 520 640 C640 684 760 712 900 760"
            val b2 = "M-140 650 C140 590 420 610 700 660 C940 702 1140 690 1420 610"
            val br = "M1060 800 C1130 640 1220 520 1420 400"
            val tl = "M-140 300 C60 200 240 110 430 70 C560 44 650 10 720 -60"
            val tr = "M760 -50 C930 40 1120 96 1420 120"
            val hz = "M-140 150 C120 108 300 66 470 94 C600 114 720 150 840 122 C980 88 1120 74 1420 128"
            val bh = "M-140 650 C160 600 460 640 720 640 C960 640 1160 590 1420 560"
            val lo = if (home) bh else b2
            val sx = w / 1280f
            fun scaled(c: Canvas, f: (Canvas) -> Unit) { c.save(); c.scale(sx, h / 720f); f(c); c.restore() }
            layer(0) { c -> scaled(c) { c2 ->
                if (home) {
                    val fill = Svg.path("$hz L1420 -60 L-140 -60 Z")
                    fp.shader = LinearGradient(0f, -60f, 0f, 150f, intArrayOf(Ck.alpha(T.c1, .05f), Ck.alpha(T.c1, .3f), Ck.alpha(T.c2, .45f)), floatArrayOf(0f, .75f, 1f), Shader.TileMode.CLAMP)
                    c2.drawPath(fill, fp); fp.shader = null
                    val hp = Svg.path(hz)
                    stroke(c2, hp, T.c3, 30f, .28f, 26f); stroke(c2, hp, T.c2, 6f, .35f, 14f); stroke(c2, hp, 0, 1.6f, .9f, 0f, rimShader())
                    strands(c2, hz, 8, -6f, T.c1, .4f)
                } else {
                    val tp = Svg.path(tl)
                    stroke(c2, tp, T.c1, 80f, .4f * .45f, 26f); strands(c2, tl, 10, 9f, T.c1, .4f); stroke(c2, tp, T.c2, 1.2f, .45f)
                    strands(c2, tr, 7, 10f, T.c1, .35f, 1.1f); stroke(c2, Svg.path(tr), T.c1, 40f, .18f, 26f)
                }
            } }
            layer(1) { c -> scaled(c) { c2 ->
                if (!home) {
                    fp.shader = LinearGradient(0f, 500f, 0f, 820f, intArrayOf(Ck.alpha(T.c1, .62f), Ck.alpha(T.c1, .38f), Ck.alpha(T.b1, .25f)), floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP)
                    c2.drawPath(Svg.path("$b1 L900 820 L-140 820 Z"), fp); fp.shader = null
                    val bp = Svg.path(b1); stroke(c2, bp, T.c2, 40f, .35f, 26f); stroke(c2, bp, 0, 1.8f, .9f, 0f, rimShader()); strands(c2, b1, 12, 7f, T.c1, .5f)
                }
                fp.shader = LinearGradient(-140f, 0f, 1420f, 0f, intArrayOf(Ck.alpha(T.c1, .55f * .85f), Ck.alpha(T.c2, .35f * .85f), Ck.alpha(T.c3, .55f * .85f)), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
                c2.drawPath(Svg.path("$lo L1420 820 L-140 820 Z"), fp); fp.shader = null
                val lp = Svg.path(lo)
                stroke(c2, lp, T.c3, 44f, .3f, 26f); stroke(c2, lp, T.c2, 8f, .45f, 14f); stroke(c2, lp, 0, 2f, .95f, 0f, rimShader()); strands(c2, lo, 12, 7f, T.c2, .45f)
            } }
            layer(2) { c -> scaled(c) { c2 ->
                fp.color = Ck.alpha(T.c2, .16f); c2.drawPath(Svg.path("$br L1420 820 Z"), fp)
                val rp = Svg.path(br)
                val rib = LinearGradient(0f, 800f, 0f, 400f, intArrayOf(T.c3, Ck.alpha(T.c2, .7f), Ck.alpha(T.c1, .2f), Ck.alpha(T.c1, 0f)), floatArrayOf(0f, .4f, .85f, 1f), Shader.TileMode.CLAMP)
                stroke(c2, rp, 0, 110f, .55f, 26f, rib); stroke(c2, rp, T.c3, 10f, .5f, 14f); stroke(c2, rp, 0, 2.2f, 1f, 0f, rib)
                strands(c2, br, 10, 3f, T.c3, .5f, 1f, 12f)
            } }
            // vignette on the base (under the waves, like the mockup's last rect but softer)
            p.shader = RadialGradient(0.45f * w, 0.45f * h, 0.8f * diag, intArrayOf(0, 0, 0x66000000), floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP)
            cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            return base to L.toList()
        }
    }
}

/** test hook: build bitmaps synchronously in Robolectric */
object Robo { @Volatile var sync = false }

/**
 * Rain / snow over the background (under the interface): ≤ 150 particles drawn in three depth batches with
 * drawLines / drawCircle, no shadows or blur, ≤ 30 fps; stops when hidden or switched off.
 */
class Particles(c: Context) : View(c) {
    var kind = "none"   // rain | snow | none
        set(v) { if (field != v) { field = v; seed(); update() } }
    private val n = 150
    private val qx = FloatArray(n); private val qy = FloatArray(n); private val z = FloatArray(n); private val ph = FloatArray(n)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var last = 0L
    private var running = false
    private val rnd = java.util.Random(7)
    private val lines = FloatArray(n * 4)

    private fun seed() { for (i in 0 until n) { qx[i] = rnd.nextFloat() * Ck.W; qy[i] = rnd.nextFloat() * Ck.H; z[i] = rnd.nextFloat(); ph[i] = rnd.nextFloat() * 6.28f } }
    init { seed() }

    fun update() {
        val want = kind != "none" && isShown && windowVisibility == VISIBLE
        if (want && !running) { running = true; last = 0; postInvalidateOnAnimation() } else if (!want) running = false
        invalidate()
    }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); update() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); update() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); running = false }

    override fun onDraw(cv: Canvas) { try { drawSafe2(cv) } catch (t: Throwable) { CrashLog.soft(t) } }
    private fun drawSafe2(cv: Canvas) {
        if (kind == "none") return
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 0.033f else ((now - last) / 1000f).coerceAtMost(0.1f)
        last = now
        val s = width / Ck.W.toFloat()
        cv.save(); cv.scale(s, height / Ck.H.toFloat())
        val rain = kind == "rain"
        for (layer in 0..2) {
            var cnt = 0
            p.color = if (rain) Ck.alpha(0xFFBFD8FF.toInt(), 0.22f + 0.16f * layer) else Ck.alpha(Color.WHITE, 0.35f + 0.25f * layer)
            p.strokeWidth = if (rain) 1f + 0.4f * layer else 0f
            for (i in 0 until n) {
                val l = (z[i] * 3).toInt().coerceAtMost(2); if (l != layer) continue
                val sp = if (rain) 520f + 520f * z[i] else 28f + 42f * z[i]
                qy[i] += sp * dt
                if (rain) qx[i] -= sp * 0.12f * dt else { ph[i] += dt * (0.6f + z[i]); qx[i] += sin(ph[i].toDouble()).toFloat() * 14f * dt }
                if (qy[i] > Ck.H + 20) { qy[i] = -20f; qx[i] = rnd.nextFloat() * (Ck.W + 120) }
                if (qx[i] < -40) qx[i] += Ck.W + 80f
                if (rain) {
                    val len = 10f + 16f * z[i]
                    lines[cnt * 4] = qx[i]; lines[cnt * 4 + 1] = qy[i]; lines[cnt * 4 + 2] = qx[i] + len * 0.12f; lines[cnt * 4 + 3] = qy[i] - len; cnt++
                } else cv.drawCircle(qx[i], qy[i], 1.2f + 2.2f * z[i], p)
            }
            if (rain && cnt > 0) cv.drawLines(lines, 0, cnt * 4, p)
        }
        cv.restore()
        if (running) postInvalidateDelayed((33 - (SystemClock.uptimeMillis() - now)).coerceIn(8, 33))
    }
}

/**
 * Looped, muted video behind the interface (TextureView + MediaPlayer). Pauses when hidden; on any error calls
 * [onFail] (the photo scene stays underneath anyway).
 */
class VideoBg(c: Context) : TextureView(c), TextureView.SurfaceTextureListener {
    private var mp: MediaPlayer? = null
    private var surface: Surface? = null
    var file: File? = null
        set(v) { if (field?.path != v?.path) { field = v; restart() } }
    var onFail: (() -> Unit)? = null
    private var vw = 0; private var vh = 0

    init { surfaceTextureListener = this; isOpaque = false }

    private fun restart() { release(); if (surface != null && file != null) start() }
    private fun start() {
        val f = file ?: return
        val s = surface ?: return
        try {
            val m = MediaPlayer()
            m.setSurface(s)
            m.setDataSource(f.path)
            m.isLooping = true
            m.setVolume(0f, 0f)
            m.setOnVideoSizeChangedListener { _, w, h -> vw = w; vh = h; fit() }
            m.setOnErrorListener { _, _, _ -> release(); onFail?.invoke(); true }
            m.setOnPreparedListener { it.start(); if (!isShown) it.pause() }
            m.prepareAsync()
            mp = m
        } catch (_: Throwable) { release(); onFail?.invoke() }
    }
    /** centre-crop the video into the view */
    private fun fit() {
        if (vw <= 0 || vh <= 0 || width == 0) return
        val sx = width / vw.toFloat(); val sy = height / vh.toFloat(); val s = maxOf(sx, sy)
        val m = Matrix(); m.setScale(vw * s / width, vh * s / height, width / 2f, height / 2f); setTransform(m)
    }
    fun release() { try { mp?.release() } catch (_: Throwable) {}; mp = null }
    fun pauseIfHidden(show: Boolean) { try { mp?.let { if (show && !it.isPlaying) it.start() else if (!show && it.isPlaying) it.pause() } } catch (_: Throwable) {} }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); pauseIfHidden(isVisible) }
    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { surface = Surface(st); start() }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { fit() }
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { release(); surface?.release(); surface = null; return true }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
}


/** v1.7.3: soft coloured light from the track cover behind it (strength = K.glow), on any background */
class CoverGlow(c: Context) : View(c) {
    private var src: Bitmap? = null
    private var halo: Bitmap? = null
    var playing = true; set(v) { if (field != v) { field = v; invalidate() } }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    fun set(b: Bitmap?) {
        if (b === src) return
        src = b; halo = b?.let { try { make(it) } catch (_: Throwable) { null } }; invalidate()
    }
    private fun make(b: Bitmap): Bitmap {
        val n = 48
        val small = Bitmap.createScaledBitmap(b, 8, 8, true)
        val out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888); val c = Canvas(out)
        val q = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        c.drawBitmap(small, null, RectF(0f, 0f, n.toFloat(), n.toFloat()), q)
        // stronger colours, faded to a soft round edge
        q.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.7f); postConcat(ColorMatrix().apply { setScale(1.6f, 1.6f, 1.6f, 1f) }) })
        c.drawBitmap(out.copy(Bitmap.Config.ARGB_8888, false), 0f, 0f, q); q.colorFilter = null
        q.shader = RadialGradient(n / 2f, n / 2f, n / 2f, intArrayOf(Color.BLACK, Color.BLACK, Ck.alpha(Color.BLACK, .55f), 0), floatArrayOf(0f, .5f, .75f, 1f), Shader.TileMode.CLAMP)
        q.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        c.drawRect(0f, 0f, n.toFloat(), n.toFloat(), q)
        small.recycle()
        return out
    }
    fun strength(): Float = K.glow / 100f * (if (playing) 1f else .45f)
    override fun onDraw(cv: Canvas) {
        try {
            val h = halo ?: return
            val s = strength(); if (s <= 0.01f) return
            p.alpha = (255 * s.coerceIn(0f, 1f)).toInt()
            cv.drawBitmap(h, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), p)
        } catch (t: Throwable) { CrashLog.soft(t) }
    }
}
