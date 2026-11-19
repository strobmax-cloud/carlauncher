package com.goga.carlauncher

import android.content.Context
import android.graphics.*
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.Calendar
import kotlin.math.*

/**
 * «Праздничный режим». v1.7.9 = approved design v4b «Minimal» (/workspace/car-design/png-newyear-v4b, png-v179):
 * fairy lights — micro-LEDs on a thin copper wire, one continuous strand along the whole top edge plus a fainter one
 * behind it for depth; on «Фон» also light curtains at both screen edges; frosted-glass ice ferns along the edges and
 * in the corners («Иней по краям»); a thin frost line on the wide cards; a low clean snowdrift along the bottom
 * («Снег и сугроб»). No branches any more (v1.7.8 photos removed from the apk).
 *
 * Everything lives BEHIND the interface: the layers under the page are [NyStatic] «back» (frost, wires, LEDs, drift)
 * and [NyLights] (the only animated part, a glow sprite per LED, ≤ 20 fps, paused when not in front / screen off).
 * So a strand is never cut where a card is: it simply passes behind it. Over free text (clock, weather) the strand
 * gets extra anchors at the text edges and rises to the screen edge, LEDs there are dimmed; the frost is erased under
 * every text and button. Over the page only the frost lines on card edges ([NyStatic] «front»).
 * Both static layers are rendered ONCE into bitmaps (background thread) per layout; no touch, no keys, no system settings.
 */
object Festive {
    /** «Авто»: 20 December … 14 January inclusive (across the new year) */
    fun inSeason(month: Int, day: Int): Boolean = (month == 12 && day >= 20) || (month == 1 && day <= 14)
    fun inSeason(c: Calendar): Boolean = inSeason(c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    fun on(now: Calendar = Calendar.getInstance()): Boolean = when (K.nyMode) { "on" -> true; "off" -> false; else -> inSeason(now) }
    /** «Сейчас» line of the settings */
    fun status(now: Calendar = Calendar.getInstance()): String = when (K.nyMode) {
        "on" -> "Включено вручную"
        "off" -> "Выключено"
        else -> if (inSeason(now)) "Включено (авто) — выключится само после 14 января" else "Включится само 20 декабря, выключится после 14 января"
    }
    val WARM = intArrayOf(0xFFFFC56B.toInt(), 0xFFFFE2A8.toInt(), 0xFFFFB35C.toInt(), 0xFFFFD891.toInt())
    val MULTI = intArrayOf(0xFFFF5D5D.toInt(), 0xFFFFC56B.toInt(), 0xFF5DE0A0.toInt(), 0xFF6FB8FF.toInt(), 0xFFFF8AD8.toInt(), 0xFFFFE2A8.toInt())

    fun noise(seed: Int, x: Float): Float {
        val r = java.util.Random(seed.toLong()); val a = FloatArray(4) { r.nextFloat() * 6.28f }
        return (0.5f + 0.22f * sin(x * 0.045f + a[0]) + 0.16f * sin(x * 0.11f + a[1]) + 0.08f * sin(x * 0.27f + a[2]) + 0.04f * sin(x * 0.6f + a[3])).coerceIn(0f, 1f)
    }
    fun lerpC(a: Int, b: Int, t: Float) = Color.argb((Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(), (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())
    fun withA(c: Int, a: Int) = Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    /** catenary through A and B with [sag] extra at the middle */
    fun catenary(a: PointF, b: PointF, sag: Float, n: Int = 40): List<PointF> {
        val w = b.x - a.x
        var lo = 0.01f; var hi = 100000f
        repeat(50) { val m = (lo + hi) / 2; if (m * (cosh(w / (2 * m)) - 1) > sag) lo = m else hi = m }
        val k = (lo + hi) / 2
        return (0..n).map { i -> val t = i / n.toFloat(); val x = a.x + w * t; val dx = x - (a.x + w / 2)
            PointF(x, a.y + (b.y - a.y) * t + sag - (k * (cosh(dx / k) - 1))) }
    }

    // ------------------------------------------------------------------ the plan (what is drawn where, stage px)
    data class Bulb(val x: Float, val y: Float, val color: Int, val dim: Boolean)
    /** glow centre for the animated layer; [r] radius, [base] alpha 0..1 */
    data class Glow(val x: Float, val y: Float, val r: Float, val color: Int, val base: Float, val phase: Float)
    data class Strand(val pts: List<PointF>, val bulbs: List<Bulb>)
    data class Plan(val vw: Int, val vh: Int, val ox: Int, val oy: Int, val bl: Int, val br: Int, val bb: Int,
                    val snow: Boolean, val frost: Boolean, val strands: List<Strand>, val back: List<Strand>, val curtains: List<Strand>,
                    val frostLines: List<RectF>, val protect: List<RectF>, val shadows: List<RectF>, val glows: List<Glow>) {
        /** the LEDs in front (top strand + curtains) */
        val bulbs: List<Bulb> get() = strands.flatMap { it.bulbs } + curtains.flatMap { it.bulbs }
    }
    /** «Тёплые» micro-LEDs: warm white */
    val LED = intArrayOf(0xFFFFE6B8.toInt(), 0xFFFFDDA4.toInt(), 0xFFFFEDCB.toInt())

    /** what the page shows: big cards (snow caps) and everything that must stay uncovered (text, icons, buttons) */
    class Scene(val cards: List<Pair<RectF, Float>>, val blocks: List<RectF>, val controls: List<RectF>)

    /** walks the page after layout (stage coordinates) */
    fun scan(page: ViewGroup): Scene {
        val cards = ArrayList<Pair<RectF, Float>>(); val blocks = ArrayList<RectF>(); val controls = ArrayList<RectF>()
        val big = Ck.W * Ck.H * 0.22f
        fun rectOf(v: View): RectF { val r = android.graphics.Rect(0, 0, v.width, v.height); page.offsetDescendantRectToMyCoords(v, r); return RectF(r) }
        fun walk(v: View, inCard: Boolean) {
            if (v.visibility != View.VISIBLE || v.alpha < 0.05f || v.width <= 0 || v.height <= 0) return
            val r = rectOf(v)
            val panel = v.background as? Panel
            var card = inCard
            if (panel != null && v !== page) {
                if (!inCard && r.width() >= 100 && r.height() >= 60 && r.top >= 60f && r.top <= 600f) { cards.add(r to panel.radii[0]); card = true }
                else if (r.width() * r.height() < big) blocks.add(r)
            }
            if (v.isClickable && v !== page && r.width() * r.height() < big) controls.add(r)
            if (v is ViewGroup) { for (i in 0 until v.childCount) walk(v.getChildAt(i), card) }
            else if (r.width() * r.height() < big && v !== page) {
                // text: only the part with letters (a wide TextView with short text must not block a whole row)
                if (v is TextView && v.layout != null && v.text.isNotEmpty()) {
                    // v1.7.9 fix: single-line TextViews lay out on a «very wide» line, so getLineLeft() of centred text
                    // is far off-screen — use the line width and the view's horizontal gravity instead
                    val l = v.layout; var lw = 0f
                    for (i in 0 until l.lineCount) lw = max(lw, l.getLineWidth(i))
                    val inner = (r.width() - v.totalPaddingLeft - v.totalPaddingRight).coerceAtLeast(0f)
                    lw = min(lw, inner)
                    val x0 = when (v.gravity and android.view.Gravity.HORIZONTAL_GRAVITY_MASK) {
                        android.view.Gravity.CENTER_HORIZONTAL -> (inner - lw) / 2
                        android.view.Gravity.RIGHT, android.view.Gravity.END -> inner - lw
                        else -> 0f }
                    val x1 = x0 + lw
                    val tx = r.left + v.totalPaddingLeft
                    val ty = r.top + v.totalPaddingTop
                    blocks.add(RectF(max(r.left, tx + x0), max(r.top, ty), min(r.right, tx + x1), min(r.bottom, ty + l.height)))
                } else if (v !is TextView) blocks.add(r)
            }
        }
        for (i in 0 until page.childCount) walk(page.getChildAt(i), false)
        return Scene(cards, blocks, controls)
    }

    /** letters that are not on a card or a chip (clock, date, weather …) */
    fun freeText(sc: Scene): List<RectF> {
        val boxes = sc.cards.map { it.first } + sc.controls
        return sc.blocks.filter { b -> b.width() > 0 && boxes.none { it.contains(b.centerX(), b.centerY()) && it.width() < 1200 } }
    }

    fun plan(c: Context, name: String, sc: Scene, vw: Int, vh: Int, bl: Int, bt: Int, br: Int, bb: Int): Plan {
        val fon = name == "fon"
        val snow = K.nySnow; val frost = K.nyFrost
        val pal = if (K.nyLights == "multi") MULTI else LED
        val free = freeText(sc)
        val l = -bl - 20f; val r = Ck.W + br + 20f
        /** even scallops between [base] x positions; extra anchors at the edges of free text in the top band */
        fun anchors(base: List<Float>, maxSag: Float): Pair<List<PointF>, List<Float>> {
            val xs = ArrayList(base)
            for (t in free) if (t.top < 130f) { xs.add(t.left - 14f); xs.add(t.right + 14f) }
            val sorted = xs.filter { it in l..r }.sorted()
            val out = ArrayList<Float>()
            for (x in sorted) if (out.isEmpty() || x - out.last() > 70f) out.add(x)
            if (out.last() < r) { if (r - out.last() < 40f) out[out.size - 1] = r else out.add(r) }
            val pts = out.map { PointF(it, -3f) }
            return pts to (1 until pts.size).map { i -> ((pts[i].x - pts[i - 1].x) * .16f).coerceIn(8f, maxSag) }
        }
        var k = 0
        val (fa, fs) = anchors(listOf(l, 330f, 660f, 990f, r), 34f)
        val front = strand(fa, fs, 16f, free, pal, k).also { k += it.bulbs.size }
        val (ba, bs) = anchors(listOf(l, 170f, 500f, 830f, 1160f, r), 20f)
        val back = strand(ba, bs, 16f, free, pal, k + 1)
        // «Фон»: light curtains hanging at both screen edges, outside the chips
        val curtains = if (!fon) emptyList() else listOf(-bl + 8f to 260f, -bl + 22f to 190f, Ck.W + br - 22f to 320f, Ck.W + br - 10f to 240f).map { (x, len) ->
            val pts = (0..20).map { i -> PointF(x + 1.5f * sin(i * .7f), -2f + len * i / 20f) }
            val bulbs = (1..(len / 20).toInt()).map { i -> Bulb(x + 1.5f * sin(i * 20f / len * 14f), i * 20f, pal[k++ % pal.size], false) }
            Strand(pts, bulbs)
        }
        // a thin frost line on the top edge of the wide cards (round buttons get none)
        val lines = if (!frost) emptyList() else sc.cards.filter { (rc, rad) -> rc.width() >= 200 && rad <= rc.height() * .36f }.map { it.first }
        // frost is erased under every letter, icon and control
        val protect = (sc.blocks + sc.controls).map { RectF(it) }
        // soft shadows under the controls standing on the drift (white labels stay readable)
        val shadows = ArrayList<RectF>()
        if (snow) for (rc in sc.controls) if (rc.bottom >= 600f && rc.height() < 140f && shadows.none { s -> RectF.intersects(s, rc) }) shadows.add(RectF(rc))
        val rnd = java.util.Random(3)
        val glows = (front.bulbs + curtains.flatMap { it.bulbs }).map { b ->
            Glow(b.x, b.y, 16f, b.color, if (b.dim) .28f else .7f + .3f * rnd.nextFloat(), rnd.nextFloat() * 6.283f) }
        return Plan(vw, vh, bl, bt, bl, br, bb, snow, frost, listOf(front), listOf(back), curtains, lines, protect, shadows, glows)
    }

    /** one continuous strand through [anchors]; LEDs every [spacing] px along the WHOLE path. A span over free text gets
     *  a shallower sag (never cut) and the LEDs near letters are dimmed. */
    fun strand(anchors: List<PointF>, sags: List<Float>, spacing: Float, free: List<RectF>, pal: IntArray, k0: Int): Strand {
        val all = ArrayList<PointF>()
        for (i in 1 until anchors.size) {
            var sag = sags[i - 1]
            var seg: List<PointF>
            while (true) {
                seg = catenary(anchors[i - 1], anchors[i], sag, 36)
                val clear = seg.none { q -> free.any { RectF(it).apply { inset(-8f, -10f) }.contains(q.x, q.y + 8f) } }
                if (clear || sag <= 4f) break
                sag = max(4f, sag * .8f)
            }
            if (all.isNotEmpty()) all.addAll(seg.drop(1)) else all.addAll(seg)
        }
        val path = Path(); path.moveTo(all[0].x, all[0].y); for (q in all.drop(1)) path.lineTo(q.x, q.y)
        val pm = PathMeasure(path, false); val pos = FloatArray(2)
        val bulbs = ArrayList<Bulb>(); var d = spacing * .5f; var k = k0
        while (d < pm.length) { pm.getPosTan(d, pos, null)
            val dim = free.any { RectF(it).apply { inset(-4f, -4f) }.intersects(pos[0] - 4, pos[1] - 4, pos[0] + 4, pos[1] + 12) }
            bulbs.add(Bulb(pos[0], pos[1], pal[k++ % pal.size], dim)); d += spacing }
        return Strand(all, bulbs)
    }
}

/** software rendering of the static layers into bitmaps (background thread) */
object NyRender {
    @Volatile private var frostSrc: Bitmap? = null
    /** the ice-fern edge (procedural, design/newyear-v4/frost4.py), 1280×720 */
    fun frost(c: Context): Bitmap? = frostSrc ?: try {
        c.assets.open("newyear/ny_frost.webp").use { BitmapFactory.decodeStream(it) }?.also { frostSrc = it }
    } catch (t: Throwable) { CrashLog.soft(t); null }
    fun release() { frostSrc = null }

    fun back(c: Context, p: Festive.Plan): Bitmap {
        val b = Bitmap.createBitmap(p.vw, p.vh, Bitmap.Config.ARGB_8888); val cv = Canvas(b)
        cv.translate(p.ox.toFloat(), p.oy.toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
        val full = RectF(-p.bl.toFloat(), -p.oy.toFloat(), (Ck.W + p.br).toFloat(), (p.vh - p.oy).toFloat())
        if (p.frost) frost(c)?.let { f ->
            cv.drawBitmap(f, null, full, paint)
            val q = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT); color = 0xE0000000.toInt(); maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL) }
            for (r in p.protect) cv.drawRoundRect(RectF(r).apply { inset(-10f, -8f) }, 14f, 14f, q)
            corner(cv, paint, full.left, full.top, 1f, 1f); corner(cv, paint, full.right, full.bottom, -1f, -1f)
        }
        for (s in p.back) strand(cv, paint, s, .45f, .75f)
        for (s in p.strands) strand(cv, paint, s, 1f, 1f)
        for (s in p.curtains) strand(cv, paint, s, .9f, .9f)
        if (p.snow) drift(cv, paint, p)
        return b
    }
    fun front(c: Context, p: Festive.Plan): Bitmap {
        val b = Bitmap.createBitmap(p.vw, p.vh, Bitmap.Config.ARGB_8888); val cv = Canvas(b)
        cv.translate(p.ox.toFloat(), p.oy.toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        for (r in p.frostLines) frostLine(cv, paint, r)
        return b
    }
    private fun corner(cv: Canvas, p: Paint, cx: Float, cy: Float, sx: Float, sy: Float) {
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, cy, 260f, intArrayOf(0x40D8E6FF, 0x16D8E6FF, 0x00D8E6FF), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(cx, cy, 260f, p); p.shader = null
    }
    /** copper wire + micro-LED cores with a small fixed halo (the breathing glow is [NyLights]) */
    private fun strand(cv: Canvas, p: Paint, s: Festive.Strand, alpha: Float, size: Float) {
        val w = Path(); w.moveTo(s.pts[0].x, s.pts[0].y); for (q in s.pts.drop(1)) w.lineTo(q.x, q.y)
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.shader = null
        p.strokeWidth = 1.1f * size; p.color = Festive.withA(0xFFC08A55.toInt(), (220 * alpha).toInt()); cv.drawPath(w, p)
        p.style = Paint.Style.FILL
        for ((i, b) in s.bulbs.withIndex()) {
            val k = (if (b.dim) .3f else .75f + .25f * Festive.noise(i + 3, i * 9f)) * alpha
            p.shader = RadialGradient(b.x, b.y, 10f * size, intArrayOf(Festive.withA(b.color, (170 * k).toInt()), Festive.withA(b.color, (40 * k).toInt()), Festive.withA(b.color, 0)), floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP)
            cv.drawCircle(b.x, b.y, 10f * size, p); p.shader = null
            p.color = Festive.withA(Festive.lerpC(b.color, Color.WHITE, .6f), (255 * min(1f, k + .15f)).toInt()); cv.drawCircle(b.x, b.y, 2.3f * size, p)
        }
    }
    /** a low, clean snowdrift along the bottom edge (to the window edges) */
    private fun drift(cv: Canvas, p: Paint, pl: Festive.Plan) {
        val x0 = -pl.bl - 10f; val x1 = Ck.W + pl.br + 10f; val bottom = Ck.H + pl.bb.toFloat()
        val pts = ArrayList<PointF>()
        var x = x0
        while (x <= x1) { val e = (abs(x - 640f) / 640f).coerceIn(0f, 1f)
            pts.add(PointF(x, 707f - 22f * e.pow(2f) - 5f * Festive.noise(11, x * .3f) - 1.5f * Festive.noise(23, x))); x += 8f }
        val path = Path(); path.moveTo(x0, bottom); path.lineTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) { val a = pts[i - 1]; val b = pts[i]; path.quadTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2) }
        path.lineTo(x1, bottom); path.close()
        // shadows under the controls first (labels stay readable over the snow)
        p.style = Paint.Style.FILL
        for (rc in pl.shadows) {
            val cx = rc.centerX(); val cy = rc.centerY(); val rx = rc.width() * .62f; val ry = rc.height() * .75f; val m = max(rx, ry)
            p.shader = RadialGradient(cx, cy, m, intArrayOf(0x66141A2E, 0x40141A2E, 0x00141A2E), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
            cv.save(); cv.scale(rx / m, ry / m, cx, cy); cv.drawCircle(cx, cy, m, p); cv.restore()
        }
        p.shader = LinearGradient(0f, 690f, 0f, 720f, intArrayOf(0xE6EEF2F8.toInt(), 0xCCB9C5D8.toInt(), 0xCC8E9AB0.toInt()), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
        cv.drawPath(path, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f; p.color = 0xB3FFFFFF.toInt()
        val ridge = Path(); ridge.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) { val a = pts[i - 1]; val b = pts[i]; ridge.quadTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2) }
        cv.drawPath(ridge, p)
        val r = java.util.Random(5); p.style = Paint.Style.FILL
        repeat(70) { val sx = x0 + r.nextFloat() * (x1 - x0); val idx = ((sx - x0) / 8f).toInt().coerceIn(0, pts.size - 1); val sy = pts[idx].y + 3 + r.nextFloat() * (bottom - 3 - pts[idx].y)
            p.color = Festive.withA(Color.WHITE, 110 + r.nextInt(130)); cv.drawCircle(sx, sy, .6f + r.nextFloat() * .7f, p) }
    }
    private fun frostLine(cv: Canvas, p: Paint, r: RectF) {
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        p.shader = LinearGradient(r.left + 20, 0f, r.right - 20, 0f, intArrayOf(0x00FFFFFF, 0x8CFFFFFF.toInt(), 0x8CFFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, .25f, .75f, 1f), Shader.TileMode.CLAMP)
        p.strokeWidth = 1.3f; cv.drawLine(r.left + 20, r.top + .7f, r.right - 20, r.top + .7f, p)
        p.strokeWidth = 5f; p.alpha = 40; cv.drawLine(r.left + 30, r.top + 1f, r.right - 30, r.top + 1f, p); p.alpha = 255; p.shader = null
    }
}

/** one of the two static layers (a bitmap rendered once); bleeds to the window edges like the background */
class NyStatic(c: Context) : View(c) {
    var bmp: Bitmap? = null
        set(v) { val old = field; field = v; invalidate(); if (old != null && old !== v) post { old.recycle() } }
    private val p = Paint(Paint.FILTER_BITMAP_FLAG)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; isClickable = false; isFocusable = false }
    override fun onDraw(cv: Canvas) { val b = bmp ?: return; if (!b.isRecycled) cv.drawBitmap(b, null, Rect(0, 0, width, height), p) }
}

/**
 * The only animated part: a glow sprite per bulb, alpha follows a slow sine per bulb («Мерцание гирлянды»).
 * ≤ 20 fps, stopped when the launcher is paused / hidden / the screen is off, or when the twinkle is 0 (static glow).
 */
class NyLights(c: Context) : View(c) {
    var glows: List<Festive.Glow> = emptyList(); set(v) { field = v; invalidate(); update() }
    var ox = 0f; var oy = 0f
    var resumed = false; set(v) { field = v; update() }
    var running = false; private set
    private val sprites = HashMap<Int, Bitmap>()
    private val p = Paint(Paint.FILTER_BITMAP_FLAG)
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private fun sprite(color: Int): Bitmap = sprites.getOrPut(color) {
        val s = 64; val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val cv = Canvas(b)
        val gp = Paint(Paint.ANTI_ALIAS_FLAG)
        gp.shader = RadialGradient(s / 2f, s / 2f, s / 2f, intArrayOf(Festive.withA(color, 170), Festive.withA(color, 56), Festive.withA(color, 0)), floatArrayOf(0f, .32f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(s / 2f, s / 2f, s / 2f, gp); b
    }
    private fun interactive(): Boolean = try { (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive } catch (_: Throwable) { true }
    fun update() {
        val want = glows.isNotEmpty() && K.nyTwinkle > 0 && resumed && isShown && windowVisibility == VISIBLE && interactive()
        if (want && !running) { running = true; postInvalidateOnAnimation() } else if (!want) running = false
    }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); update() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); update() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); update() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); running = false }
    /** intensity of glow [g] at time [t] seconds, 0..1 — smooth (no blinking), deeper and faster with more twinkle */
    fun level(g: Festive.Glow, t: Float, tw: Int = K.nyTwinkle): Float {
        val amt = tw / 100f
        val f = 0.25f + 0.9f * amt
        val s = 0.5f + 0.5f * sin(t * f * 6.283f * (0.7f + 0.3f * (g.phase / 6.283f)) + g.phase)
        return (g.base * (1f - amt * 0.75f * s)).coerceIn(0f, 1f)
    }
    override fun onDraw(cv: Canvas) {
        if (glows.isEmpty()) return
        try {
            val t = SystemClock.uptimeMillis() / 1000f
            cv.save(); cv.translate(ox, oy)
            for (g in glows) {
                val k = if (K.nyTwinkle == 0) g.base else level(g, t)
                p.alpha = (255 * k).toInt()
                dst.set(g.x - g.r, g.y - g.r, g.x + g.r, g.y + g.r); cv.drawBitmap(sprite(g.color), null, dst, p)
                core.color = Festive.withA(Color.WHITE, (90 * k).toInt()); cv.drawCircle(g.x, g.y, 2.2f, core)
            }
            cv.restore()
        } catch (t: Throwable) { CrashLog.soft(t); running = false }
        if (running) { if (!interactive()) running = false else postInvalidateDelayed(50) }
    }
}

/** the three layers of one Cockpit + when to re-plan (layout change, settings, auto date) */
class NyLayer(private val ck: Cockpit) {
    private val a get() = ck.a
    val back = NyStatic(ck.a)
    val front = NyStatic(ck.a)
    val lights = NyLights(ck.a)
    var enabled = false; private set
    var plan: Festive.Plan? = null; private set
    private var gen = 0
    private var lastName = ""
    private var lastAuto = 0L
    private val replan = Runnable { replanNow() }
    private val layoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener { schedule() }

    fun attach(st: StageBox, page: View) {
        // back (frost, wires, LEDs, drift) and the breathing glows sit UNDER the page; only the frost lines above it
        val i = st.indexOfChild(page)
        st.addView(back, i, android.widget.FrameLayout.LayoutParams(Ck.W, Ck.H))
        st.addView(lights, i + 1, android.widget.FrameLayout.LayoutParams(Ck.W, Ck.H))
        st.addView(front, st.indexOfChild(page) + 1, android.widget.FrameLayout.LayoutParams(Ck.W, Ck.H))
        st.bleed.add(back); st.bleed.add(front); st.bleed.add(lights)
        page.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        apply()
    }
    /** settings changed / screen rebuilt */
    fun apply() {
        enabled = try { Festive.on() } catch (_: Throwable) { false }
        val vis = if (enabled) View.VISIBLE else View.GONE
        back.visibility = vis; front.visibility = vis; lights.visibility = vis
        if (!enabled) { plan = null; back.bmp = null; front.bmp = null; lights.glows = emptyList(); NyRender.release(); return }
        schedule()
    }
    fun schedule() { if (!enabled) return; AppRepo.main.removeCallbacks(replan); if (Robo.sync) replanNow() else AppRepo.main.postDelayed(replan, 150) }
    fun onResume() { lights.resumed = true; checkAuto(true) }
    fun onPause() { lights.resumed = false }
    /** «Авто»: the date is checked once a minute (from the Cockpit tick) */
    fun checkAuto(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastAuto < 60_000) return
        lastAuto = now
        if (Festive.on() != enabled) { apply(); ck.refreshFx() }
    }
    fun name(): String = if (ck.fon) "fon" else (if (Ck.isC) "C-" else "D-") + (if (ck.screen == 1) "music" else "home")

    private fun replanNow() {
        if (!enabled) return
        try {
            val st = ck.root.stage
            if (ck.page.width == 0) return
            val vw = Ck.W + st.bl + st.br; val vh = Ck.H + st.bt + st.bb
            val p = Festive.plan(a, name(), Festive.scan(ck.page), vw, vh, st.bl, st.bt, st.br, st.bb)
            if (p == plan && back.bmp != null) return
            plan = p; lastName = name()
            lights.ox = st.bl.toFloat(); lights.oy = st.bt.toFloat()
            val g = ++gen
            val app = a.applicationContext
            val job = Runnable {
                val b = try { NyRender.back(app, p) } catch (t: Throwable) { CrashLog.soft(t); null }
                val f = try { NyRender.front(app, p) } catch (t: Throwable) { CrashLog.soft(t); null }
                val done = Runnable { if (g == gen && enabled) { back.bmp = b; front.bmp = f; lights.glows = p.glows } else { b?.recycle(); f?.recycle() } }
                if (Robo.sync) done.run() else AppRepo.main.post(done)
            }
            if (Robo.sync) job.run() else AppRepo.exec.execute(job)
        } catch (t: Throwable) { CrashLog.soft(t) }
    }
}
